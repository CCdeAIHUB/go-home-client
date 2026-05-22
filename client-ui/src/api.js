export function api() {
  if (window.GoHomeAPI) return window.GoHomeAPI
  return controlAPI
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
