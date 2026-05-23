export function api() {
  if (window.GoHomeAPI) return window.GoHomeAPI
  if (window.GoHomeNative) return androidSignalAPI
  return controlAPI
}

const androidSignalAPI = {
  async connectServer(server, authCode) {
    const result = readAndroidJSON(window.GoHomeNative.signalConnect(server, authCode))
    if (result.error) throw new Error(result.error)
    return { ok: true }
  },
  async disconnectServer() {
    window.GoHomeNative.signalDisconnect()
    return { ok: true }
  },
  async getConnectionStatus() {
    return this.getTunnelStatus()
  },
  async listFamilies() {
    return androidRPC('client.family.list', {})
  },
  async checkNetworkConflict(family) {
    const lanCIDR = family.lan_cidr || ''
    return { conflict: lanCIDR ? window.GoHomeNative.localNetworkConflict(lanCIDR) : false, lan_cidr: lanCIDR }
  },
  async requestLayer3Permission() {
    return { status: window.GoHomeNative.requestVpnPermission() }
  },
  async connectFamily(familyID, options) {
    if (window.GoHomeNative.vpnPermissionStatus() !== 'granted') {
      window.GoHomeNative.requestVpnPermission()
      throw new Error('Allow Android VPN permission and connect again')
    }
    const prepared = readAndroidJSON(window.GoHomeNative.prepareTunnel(window.GoHomeNative.deviceId()))
    const offer = await androidRPC('p2p.hole_punch_req', {
      family_id: familyID,
      client_udp_port: prepared.udp_port,
      preferred_mode: options.mode,
      virtual_cidr: options.virtual_cidr || '',
      client_virtual_mac: prepared.client_virtual_mac
    })
    return readAndroidJSON(window.GoHomeNative.connectTunnel(
      JSON.stringify(offer),
      options.mode,
      options.virtual_cidr || ''
    ))
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
  }
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
  }
}

function androidRPC(action, params) {
  const result = readAndroidJSON(window.GoHomeNative.signalRPC(action, JSON.stringify(params)))
  if (result.error) throw new Error(result.error)
  return result
}

function readAndroidJSON(value) {
  if (!value) return {}
  return JSON.parse(value)
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
