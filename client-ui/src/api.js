const mockFamilies = [
  {
    id: 1,
    name: '示例家庭',
    visibility: 'public',
    home_server_online: true,
    lan_cidr: '192.168.3.0/24'
  }
]

export function api() {
  if (window.GoHomeAPI) return window.GoHomeAPI
  return mockAPI
}

const wait = (value, delay = 350) =>
  new Promise((resolve) => setTimeout(() => resolve(value), delay))

const mockAPI = {
  async connectServer(server, authCode) {
    if (!server || !authCode) throw new Error('请输入服务器地址和授权码')
    return wait({ ok: true })
  },
  async disconnectServer() {
    return wait({ ok: true })
  },
  async getConnectionStatus() {
    return wait({ websocket: 'connected', udp: 'idle', grace_seconds: 0 })
  },
  async listFamilies() {
    return wait(mockFamilies)
  },
  async checkNetworkConflict(family) {
    return wait({ conflict: family.lan_cidr === '192.168.3.0/24', lan_cidr: family.lan_cidr })
  },
  async requestLayer3Permission() {
    return wait({ granted: true })
  },
  async connectFamily(familyID, options) {
    return wait({
      family_id: familyID,
      mode: options.mode,
      client_home_ip: '192.168.3.200',
      virtual_cidr: options.virtual_cidr || '',
      devices: [
        { name: 'NAS', real_ip: '192.168.3.5', virtual_ip: options.virtual_cidr ? '192.168.6.5' : '192.168.3.5' },
        { name: 'Camera', real_ip: '192.168.3.22', virtual_ip: options.virtual_cidr ? '192.168.6.22' : '192.168.3.22' }
      ]
    })
  },
  async getTrafficStats() {
    return wait({ up: 128000, down: 952000, loss: 0.1, latency_ms: 28 })
  },
  async getTunnelStatus() {
    return wait({ websocket: 'connected', udp: 'connected', grace_seconds: 0 })
  },
  async checkUpdate() {
    return wait({ latest: '0.1.0', current: '0.1.0', update: false })
  }
}
