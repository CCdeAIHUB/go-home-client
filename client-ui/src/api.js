export function api() {
  if (window.GoHomeAPI) return window.GoHomeAPI
  if (window.GoHomeNative) return androidSignalAPI
  return controlAPI
}

const androidState = {
  ws: null,
  seq: 0,
  pending: new Map(),
  server: '',
  authCode: '',
  connected: false,
  latency: 0,
  socketGeneration: 0,
  intentionalClose: false,
  graceUntil: 0,
  reconnectTimer: null,
  heartbeatTimer: null,
  lastError: ''
}

const androidSignalAPI = {
  async connectServer(server, authCode) {
    androidState.server = server
    androidState.authCode = authCode
    androidState.connected = false
    androidState.intentionalClose = true
    clearAndroidTimers()
    rejectAndroidPending('服务器信令重连中')
    androidState.socketGeneration += 1
    androidState.ws?.close()
    androidState.intentionalClose = false
    androidState.graceUntil = 0
    androidState.lastError = ''
    await openAndroidSignal()
    return { ok: true }
  },
  async disconnectServer() {
    androidState.intentionalClose = true
    clearAndroidTimers()
    androidState.graceUntil = 0
    window.GoHomeNative.disconnectTunnel()
    androidState.ws?.close()
    androidState.ws = null
    androidState.connected = false
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
    if (androidState.connected) {
      const heartbeat = await androidHeartbeat().catch(() => null)
      androidState.latency = heartbeat?.latency_ms || androidState.latency
    }
    return { ...readAndroidJSON(window.GoHomeNative.tunnelStats()), latency_ms: androidState.latency }
  },
  async getTunnelStatus() {
    const tunnel = readAndroidJSON(window.GoHomeNative.tunnelStatus())
    const graceSeconds = androidGraceSeconds()
    return {
      websocket: androidState.connected ? 'connected' : graceSeconds ? 'grace' : 'idle',
      udp: tunnel.udp || 'idle',
      grace_seconds: graceSeconds,
      last_error: tunnel.last_error || androidState.lastError || ''
    }
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

function waitForWebSocket(ws) {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error('服务器连接超时')), 12000)
    ws.addEventListener('open', () => {
      window.clearTimeout(timer)
      resolve()
    }, { once: true })
    ws.addEventListener('error', () => {
      window.clearTimeout(timer)
      reject(new Error('服务器无法连接'))
    }, { once: true })
  })
}

async function openAndroidSignal() {
  const ws = new WebSocket(websocketURL(androidState.server))
  const generation = ++androidState.socketGeneration
  androidState.ws = ws
  ws.addEventListener('message', handleAndroidMessage)
  ws.addEventListener('close', () => handleAndroidClose(generation))
  await waitForWebSocket(ws)
  const timestamp = Math.floor(Date.now() / 1000)
  await androidRPC('device.auth', {
    device_id: window.GoHomeNative.deviceId(),
    device_type: 'client',
    auth_code: androidState.authCode,
    time_key: window.GoHomeNative.timeKey(androidState.authCode, timestamp),
    timestamp
  })
  if (androidState.ws !== ws) {
    throw new Error('服务器信令已切换')
  }
  androidState.connected = true
  androidState.graceUntil = 0
  androidState.lastError = ''
  startAndroidHeartbeat()
}

function androidRPC(action, params) {
  if (!androidState.ws || androidState.ws.readyState !== WebSocket.OPEN) {
    return Promise.reject(new Error('服务器信令未连接'))
  }
  const id = `android-${++androidState.seq}`
  const reply = new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      androidState.pending.delete(id)
      reject(new Error('服务器响应超时'))
    }, 12000)
    androidState.pending.set(id, (env) => {
      window.clearTimeout(timer)
      if (env.error) {
        reject(new Error(env.error.message || env.error.code))
      } else {
        resolve(env.result)
      }
    })
  })
  androidState.ws.send(JSON.stringify({ jsonrpc: '2.0', id, action, params }))
  return reply
}

function handleAndroidMessage(event) {
  const env = JSON.parse(event.data)
  if (env.id && androidState.pending.has(env.id)) {
    const pending = androidState.pending.get(env.id)
    androidState.pending.delete(env.id)
    pending(env)
    return
  }
  if (env.action === 'device.latency_probe') {
    androidRPC('stats.latency_pong', { probe_id: env.params?.probe_id }).catch(() => {})
  }
  if (env.action === 'device.force_offline') {
    androidState.intentionalClose = true
    clearAndroidTimers()
    window.GoHomeNative.disconnectTunnel()
    androidState.ws?.close()
  }
}

function androidHeartbeat() {
  const timestamp = Math.floor(Date.now() / 1000)
  return androidRPC('ping', {
    time_key: window.GoHomeNative.timeKey(androidState.authCode, timestamp),
    timestamp
  })
}

function handleAndroidClose(generation) {
  if (generation !== androidState.socketGeneration) return
  androidState.connected = false
  stopAndroidHeartbeat()
  rejectAndroidPending('服务器信令已断开')
  if (androidState.intentionalClose) return
  const tunnel = readAndroidJSON(window.GoHomeNative.tunnelStatus())
  if (tunnel.udp === 'connected') beginAndroidGrace()
}

function beginAndroidGrace() {
  if (!androidState.graceUntil) {
    androidState.graceUntil = Date.now() + 30000
  }
  scheduleAndroidReconnect(800)
}

function scheduleAndroidReconnect(delay) {
  window.clearTimeout(androidState.reconnectTimer)
  androidState.reconnectTimer = window.setTimeout(async () => {
    if (androidState.intentionalClose) return
    if (androidGraceSeconds() === 0) {
      androidState.lastError = 'WebSocket grace period expired'
      window.GoHomeNative.disconnectTunnel()
      return
    }
    try {
      await openAndroidSignal()
    } catch (_) {
      scheduleAndroidReconnect(1800)
    }
  }, delay)
}

function startAndroidHeartbeat() {
  stopAndroidHeartbeat()
  androidState.heartbeatTimer = window.setInterval(() => {
    androidHeartbeat().then((heartbeat) => {
      androidState.latency = heartbeat?.latency_ms || androidState.latency
    }).catch(() => {})
  }, 25000)
}

function stopAndroidHeartbeat() {
  window.clearInterval(androidState.heartbeatTimer)
  androidState.heartbeatTimer = null
}

function clearAndroidTimers() {
  window.clearTimeout(androidState.reconnectTimer)
  androidState.reconnectTimer = null
  stopAndroidHeartbeat()
}

function rejectAndroidPending(message) {
  for (const pending of androidState.pending.values()) {
    pending({ error: { message } })
  }
  androidState.pending.clear()
}

function androidGraceSeconds() {
  if (!androidState.graceUntil) return 0
  return Math.max(0, Math.ceil((androidState.graceUntil - Date.now()) / 1000))
}

function websocketURL(server) {
  let value = server.trim()
  if (!value.includes('://')) value = `ws://${value}`
  const url = new URL(value)
  if (url.protocol === 'http:') url.protocol = 'ws:'
  if (url.protocol === 'https:') url.protocol = 'wss:'
  if (url.pathname === '/' || !url.pathname) url.pathname = '/ws'
  return url.toString()
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
