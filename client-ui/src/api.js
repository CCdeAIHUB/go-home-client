export function api() {
  if (window.GoHomeAPI) return window.GoHomeAPI
  if (window.GoHomeNative) return androidSignalAPI
  return controlAPI
}

// Global callback map for async tunnel results
if (!window._goHomeTunnelCallbacks) {
  window._goHomeTunnelCallbacks = {}
  window._goHomeTunnelResult = function (callbackId, jsonResult) {
    const handler = window._goHomeTunnelCallbacks[callbackId]
    if (handler) {
      delete window._goHomeTunnelCallbacks[callbackId]
      handler(jsonResult)
    }
  }
}

// Global callback for server push events (home server online/offline etc.)
if (!window._goHomeServerEventHandlers) {
  window._goHomeServerEventHandlers = []
  window._goHomeServerEvent = function (action, paramsStr) {
    try {
      const params = JSON.parse(paramsStr)
      window._goHomeServerEventHandlers.forEach(handler => {
        try { handler(action, params) } catch (e) { console.error('server event handler error', e) }
      })
    } catch (e) { console.error('parse server event error', e) }
  }
}

export function onServerEvent(handler) {
  window._goHomeServerEventHandlers.push(handler)
  return () => {
    const idx = window._goHomeServerEventHandlers.indexOf(handler)
    if (idx >= 0) window._goHomeServerEventHandlers.splice(idx, 1)
  }
}

const androidSignalAPI = {
  async connectServer(server, authCode) {
    const result = readAndroidJSON(window.GoHomeNative.signalConnect(server, authCode))
    if (result.error) throw new Error(result.error)
    return { ok: true }
  },
  async disconnectServer() {
    if (window.GoHomeNative.disconnectTunnel) {
      window.GoHomeNative.disconnectTunnel()
    } else {
      window.GoHomeNative.signalDisconnect()
    }
    return { ok: true }
  },
  async getConnectionStatus() {
    return this.getTunnelStatus()
  },
  async listFamilies() {
    const result = await androidRPC('client.family.list', {})
    return Array.isArray(result) ? result : (result.families || [])
  },
  async checkNetworkConflict(family) {
    const lanCIDR = family.lan_cidr || ''
    return { conflict: lanCIDR ? window.GoHomeNative.localNetworkConflict(lanCIDR) : false, lan_cidr: lanCIDR }
  },
  async requestLayer3Permission() {
    // Check current status first
    const currentStatus = window.GoHomeNative.vpnPermissionStatus()
    if (currentStatus === 'granted') return { status: 'granted' }
    // Request permission and wait for user response (runs on background thread in Kotlin)
    const result = window.GoHomeNative.requestVpnPermission()
    return { status: result }
  },
  async connectFamily(familyID, options) {
    // Step 1: Ensure VPN permission is granted before connecting
    if (window.GoHomeNative.vpnPermissionStatus() !== 'granted') {
      const permResult = window.GoHomeNative.requestVpnPermission()
      if (permResult !== 'granted') {
        throw new Error('需要 VPN 权限才能建立直连，请在弹出的对话框中允许')
      }
    }

    let lastError
    for (let attempt = 0; attempt < 2; attempt += 1) {
      // A fresh socket set gives symmetric NAT a new mapping opportunity
      // without disturbing mappings while an individual attempt is active.
      const prepared = readAndroidJSON(window.GoHomeNative.prepareTunnel(window.GoHomeNative.deviceId()))
      if (prepared.error) throw new Error(prepared.error)
      if (window.GoHomeNative.registerTunnelEndpoint) {
        const registered = readAndroidJSON(window.GoHomeNative.registerTunnelEndpoint())
        if (registered.error) console.warn('UDP endpoint registration failed:', registered.error)
      }

      const offer = await androidRPC('p2p.hole_punch_req', {
        family_id: familyID,
        client_udp_port: prepared.udp_port,
        preferred_mode: options.mode,
        virtual_cidr: options.virtual_cidr || '',
        client_virtual_mac: prepared.client_virtual_mac
      })

      try {
        if (window.GoHomeNative.connectTunnelAsync) {
          return await connectTunnelAsync(offer, options.mode, options.virtual_cidr || '')
        }
        return readAndroidJSON(window.GoHomeNative.connectTunnel(
          JSON.stringify(offer),
          options.mode,
          options.virtual_cidr || ''
        ))
      } catch (error) {
        lastError = error
        if (attempt === 0) {
          console.warn('UDP direct attempt failed, retrying with fresh sockets:', error)
        }
      }
    }
    throw lastError || new Error('UDP direct tunnel handshake timed out')
  },
  async getTrafficStats() {
    const status = readAndroidJSON(window.GoHomeNative.signalStatus())
    return { ...readAndroidJSON(window.GoHomeNative.tunnelStats()), latency_ms: status.latency_ms || 0 }
  },
  async getTunnelStatus() {
    return readAndroidJSON(window.GoHomeNative.signalStatus())
  },
  async checkUpdate() {
    return { current: '0.2.0', latest: '0.2.0', update: false, configured: false }
  },
  // Family detail APIs
  async listFamilyDevices(familyID) {
    try {
      const result = await androidRPC('client.family.devices', { family_id: familyID })
      return Array.isArray(result) ? result : (result.devices || [])
    } catch { return [] }
  },
  async getFamilyTraffic(familyID) {
    try {
      return await androidRPC('client.family.traffic', { family_id: familyID })
    } catch { return { up: 0, down: 0 } }
  },
  async getFamilyLogs(familyID) {
    try {
      const result = await androidRPC('client.family.logs', { family_id: familyID, limit: 20 })
      return Array.isArray(result) ? result : (result.logs || [])
    } catch { return [] }
  },
  // Device detail APIs
  async getDeviceInfo(deviceID) {
    try {
      return await androidRPC('client.device.info', { device_id: deviceID })
    } catch { return {} }
  },
  async getDeviceTraffic(deviceID) {
    try {
      return await androidRPC('client.device.traffic', { device_id: deviceID })
    } catch { return { up: 0, down: 0 } }
  }
}

/**
 * Connect tunnel asynchronously - returns a Promise that resolves
 * when the native side calls window._goHomeTunnelResult().
 * This keeps the JS thread free so the UI can render loading states.
 */
function connectTunnelAsync(offer, mode, virtualCIDR) {
  return new Promise((resolve, reject) => {
    const callbackId = window.GoHomeNative.connectTunnelAsync(
      JSON.stringify(offer), mode, virtualCIDR
    )
    const timeout = setTimeout(() => {
      delete window._goHomeTunnelCallbacks[callbackId]
      reject(new Error('连接超时'))
    }, 55000)

    window._goHomeTunnelCallbacks[callbackId] = (jsonStr) => {
      clearTimeout(timeout)
      const result = readAndroidJSON(jsonStr)
      if (result.error) {
        reject(new Error(result.error))
      } else {
        resolve(result)
      }
    }
  })
}

const controlAPI = {
  async connectServer(server, authCode) {
    return request('/api/connect', {
      method: 'POST',
      body: { server, auth_code: authCode }
    })
  },
  async disconnectServer() {
    return request('/api/disconnect', { method: 'POST' })
  },
  async getConnectionStatus() {
    return request('/api/status')
  },
  async listFamilies() {
    return request('/api/families')
  },
  async checkNetworkConflict(family) {
    return request('/api/conflict', {
      method: 'POST',
      body: { lan_cidr: family.lan_cidr || '' }
    })
  },
  async requestLayer3Permission() {
    return { granted: true }
  },
  async connectFamily(familyID, options) {
    return request('/api/tunnel/connect', {
      method: 'POST',
      body: {
        family_id: familyID,
        mode: options.mode,
        virtual_cidr: options.virtual_cidr || '',
        client_virtual_mac: options.client_virtual_mac || ''
      }
    })
  },
  async getTrafficStats() {
    return request('/api/stats')
  },
  async getTunnelStatus() {
    return request('/api/status')
  },
  async checkUpdate() {
    return request('/api/update')
  },
  async listFamilyDevices(familyID) {
    return request(`/api/families/${familyID}/devices`)
  },
  async getFamilyTraffic(familyID) {
    return request(`/api/families/${familyID}/traffic`)
  },
  async getFamilyLogs(familyID) {
    return request(`/api/families/${familyID}/logs`)
  },
  async getDeviceInfo(deviceID) {
    return request(`/api/devices/${encodeURIComponent(deviceID)}`)
  },
  async getDeviceTraffic(deviceID) {
    return request(`/api/devices/${encodeURIComponent(deviceID)}/traffic`)
  }
}

function androidRPC(action, params) {
  const result = readAndroidJSON(window.GoHomeNative.signalRPC(action, JSON.stringify(params)))
  if (result.error) throw new Error(result.error)
  return result
}

function readAndroidJSON(value) {
  if (!value) return {}
  const parsed = JSON.parse(value)
  // If the Kotlin bridge returns a JSON array string, keep it as an array
  return parsed
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: options.body ? JSON.stringify(options.body) : undefined
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new Error(payload.error || '客户端后端不可用')
  }
  return payload
}
