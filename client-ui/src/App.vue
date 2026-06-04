<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Activity,
  ArrowLeft,
  Cable,
  Check,
  ChevronRight,
  CircleAlert,
  Gauge,
  Home,
  Loader2,
  Monitor,
  Moon,
  Network,
  PlugZap,
  Radio,
  RefreshCw,
  Route,
  Server,
  Settings,
  ShieldCheck,
  Smartphone,
  Sun,
  Trash2,
  Users,
  Wifi,
  WifiOff,
  Zap
} from '@lucide/vue'
import { api, onServerEvent } from './api'

const backend = api()

const state = reactive({
  page: 'connect',
  server: '',
  authCode: '',
  connecting: false,
  error: '',
  families: [],
  selectedFamily: null,
  conflict: null,
  networkMode: 'real',
  virtualCIDR: '192.168.6.0/24',
  tunnel: null,
  status: { websocket: 'idle', udp: 'idle', grace_seconds: 0 },
  stats: { up: 0, down: 0, loss: 0, latency_ms: 0, tunnel_rtt_ms: 0 },
  update: null,
  savedServers: [],
  // Detail views
  detailFamily: null,
  detailDevice: null,
  // Detail data
  familyDevices: [],
  familyTraffic: { up: 0, down: 0 },
  familyLogs: [],
  deviceInfo: {},
  deviceTraffic: { up: 0, down: 0 },
  // Back navigation
  backPage: null
})

const theme = ref('system')
const activeTheme = ref('light')
const currentFamily = computed(() => state.selectedFamily)
const signalConnected = computed(() => state.status.websocket === 'connected' || state.families.length > 0)
const tunnelConnected = computed(() => state.status.udp === 'connected' && Boolean(state.tunnel))
const activeStep = computed(() => {
  if (state.page === 'status') return 3
  if (state.page === 'network') return 2
  return 1
})

let statusTimer = null
let offServerEvent = null
let themeMediaQuery = null
let themeMediaHandler = null

onMounted(() => {
  theme.value = localStorage.getItem('go-home-client-theme') || 'system'
  applyTheme(theme.value)
  themeMediaQuery = window.matchMedia?.('(prefers-color-scheme: dark)')
  themeMediaHandler = () => {
    if (theme.value === 'system') applyTheme('system')
  }
  if (themeMediaQuery?.addEventListener) {
    themeMediaQuery.addEventListener('change', themeMediaHandler)
  } else if (themeMediaQuery?.addListener) {
    themeMediaQuery.addListener(themeMediaHandler)
  }
  loadSavedServers()
  // Auto-fill last used server on startup
  const lastServer = state.savedServers.find(s => s.server)
  if (lastServer) {
    state.server = lastServer.server
    state.authCode = lastServer.authCode
  }
  statusTimer = window.setInterval(() => {
    if (state.page === 'status' && state.tunnel) {
      refreshStatus().catch(() => {})
    }
  }, 1000)
  // 监听服务器推送的家庭服务器状态变更事件
  offServerEvent = onServerEvent((action, params) => {
    if (action === 'family.home_server_changed' && state.page === 'families') {
      // 刷新家庭列表
      backend.listFamilies().then(families => {
        state.families = families
      }).catch(() => {})
    }
  })
})

function applyTheme(value) {
  activeTheme.value = value === 'system'
    ? (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
    : value
  document.documentElement.dataset.theme = activeTheme.value
}

function setTheme(value) {
  theme.value = value
  localStorage.setItem('go-home-client-theme', value)
  applyTheme(value)
}

function toggleTheme() {
  setTheme(activeTheme.value === 'dark' ? 'light' : 'dark')
}

onBeforeUnmount(() => {
  window.clearInterval(statusTimer)
  if (themeMediaQuery?.removeEventListener && themeMediaHandler) {
    themeMediaQuery.removeEventListener('change', themeMediaHandler)
  } else if (themeMediaQuery?.removeListener && themeMediaHandler) {
    themeMediaQuery.removeListener(themeMediaHandler)
  }
  if (offServerEvent) offServerEvent()
})

// ── Saved servers ──

function loadSavedServers() {
  try {
    const raw = localStorage.getItem('go-home-servers')
    state.savedServers = raw ? JSON.parse(raw) : []
  } catch { state.savedServers = [] }
}

function saveSavedServers() {
  localStorage.setItem('go-home-servers', JSON.stringify(state.savedServers))
}

function addSavedServer(server, authCode) {
  const id = server.trim()
  const existing = state.savedServers.findIndex(s => s.server === id)
  if (existing >= 0) {
    state.savedServers[existing].authCode = authCode
    state.savedServers[existing].lastUsed = Date.now()
  } else {
    state.savedServers.push({ server: id, authCode, lastUsed: Date.now() })
  }
  saveSavedServers()
}

function removeSavedServer(server) {
  state.savedServers = state.savedServers.filter(s => s.server !== server)
  saveSavedServers()
}

async function quickConnect(saved) {
  state.server = saved.server
  state.authCode = saved.authCode
  await connectServer()
}

// ── Connection ──

async function connectServer() {
  state.error = ''
  state.connecting = true
  try {
    await backend.connectServer(state.server, state.authCode)
    addSavedServer(state.server, state.authCode)
    const families = await backend.listFamilies()
    state.families = families
    if (!families || families.length === 0) {
      state.error = '已连接服务器，但当前没有可见的家庭'
    }
    state.page = 'families'
  } catch (error) {
    state.error = error.message || '连接失败'
  } finally {
    state.connecting = false
  }
}

async function chooseFamily(family) {
  state.error = ''
  state.selectedFamily = family
  state.conflict = await backend.checkNetworkConflict(family)
  state.networkMode = state.conflict?.conflict ? 'mapped' : 'real'
  state.page = 'network'
}

async function connectFamily() {
  if (!state.selectedFamily) return
  state.error = ''
  state.connecting = true
  try {
    const options = {
      mode: state.networkMode,
      virtual_cidr: state.networkMode === 'mapped' ? state.virtualCIDR : ''
    }
    state.tunnel = await backend.connectFamily(state.selectedFamily.id, options)
    state.status = await backend.getTunnelStatus()
    state.stats = await backend.getTrafficStats()
    state.page = 'status'
  } catch (error) {
    state.error = error.message || '当前网络环境无法穿透，直连失败'
  } finally {
    state.connecting = false
  }
}

async function refreshStatus() {
  state.status = await backend.getTunnelStatus()
  state.stats = await backend.getTrafficStats()
}

async function disconnect() {
  await backend.disconnectServer()
  state.page = 'connect'
  state.tunnel = null
  state.selectedFamily = null
}

async function checkUpdate() {
  state.error = ''
  try {
    state.update = await backend.checkUpdate()
  } catch (error) {
    state.error = error.message
  }
}

// ── Navigation ──

function navigateTo(page, backPage) {
  state.backPage = backPage || state.page
  state.page = page
}

function navigateToSettings() {
  state.backPage = state.page
  state.page = 'settings'
}

function goBack() {
  if (state.backPage) {
    state.page = state.backPage
    state.backPage = null
  } else if (state.tunnel) {
    state.page = 'status'
  } else if (state.families.length) {
    state.page = 'families'
  } else {
    state.page = 'connect'
  }
  state.detailFamily = null
  state.detailDevice = null
}

// ── Detail views ──

async function openFamilyDetail(family) {
  state.detailFamily = family
  state.detailDevice = null
  navigateTo('familyDetail', 'families')
  try {
    state.familyDevices = await backend.listFamilyDevices(family.id)
  } catch { state.familyDevices = [] }
  try {
    state.familyTraffic = await backend.getFamilyTraffic(family.id)
  } catch { state.familyTraffic = { up: 0, down: 0 } }
  try {
    state.familyLogs = await backend.getFamilyLogs(family.id)
  } catch { state.familyLogs = [] }
}

async function openDeviceDetail(device) {
  state.detailDevice = device
  navigateTo('deviceDetail', 'familyDetail')
  try {
    state.deviceInfo = await backend.getDeviceInfo(device.device_id)
  } catch { state.deviceInfo = device }
  try {
    state.deviceTraffic = await backend.getDeviceTraffic(device.device_id)
  } catch { state.deviceTraffic = { up: 0, down: 0 } }
}

function formatBytes(value) {
  if (!value) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[unit]}`
}

function formatTime(ts) {
  if (!ts) return '-'
  const d = new Date(ts)
  return d.toLocaleString('zh-CN')
}

function latencyText(value) {
  return Number(value) > 0 ? `${value} ms` : '--'
}

function connectionText(value) {
  if (value === 'connected') return '已连接'
  if (value === 'grace') return '宽限期'
  return '未连接'
}

function timeAgo(ts) {
  if (!ts) return '从未'
  const sec = Math.floor((Date.now() - new Date(ts).getTime()) / 1000)
  if (sec < 60) return `${sec}秒前`
  if (sec < 3600) return `${Math.floor(sec / 60)}分钟前`
  if (sec < 86400) return `${Math.floor(sec / 3600)}小时前`
  return `${Math.floor(sec / 86400)}天前`
}
</script>

<template>
  <main class="client-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark"><ShieldCheck :size="21" /></span>
        <span>
          <strong>Go Home</strong>
          <small>Private P2P Network</small>
        </span>
      </div>
      <div class="topbar-actions">
        <span class="connection-chip" :class="{ online: tunnelConnected, signal: signalConnected && !tunnelConnected }">
          <i />
          {{ tunnelConnected ? '直连在线' : signalConnected ? '服务器在线' : '未连接' }}
        </span>
        <nav>
          <button v-if="state.backPage || state.detailFamily || state.detailDevice || state.page === 'settings'" @click="goBack" class="nav-button" title="返回">
            <ArrowLeft :size="18" />
            <span>返回</span>
          </button>
          <button :class="{ active: state.page === 'families' }" class="nav-button" @click="state.page = 'families'; state.detailFamily = null; state.detailDevice = null; state.backPage = null" :disabled="!state.families.length" title="家庭">
            <Home :size="18" />
            <span>家庭</span>
          </button>
          <button :class="{ active: state.page === 'status' }" class="nav-button" @click="state.page = 'status'; state.detailFamily = null; state.detailDevice = null; state.backPage = null" :disabled="!state.tunnel" title="状态">
            <Activity :size="18" />
            <span>状态</span>
          </button>
          <button :class="{ active: state.page === 'settings' }" class="nav-button" @click="navigateToSettings" title="设置">
            <Settings :size="18" />
            <span>设置</span>
          </button>
          <button class="nav-button" type="button" :title="activeTheme === 'dark' ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme">
            <Sun v-if="activeTheme === 'dark'" :size="18" />
            <Moon v-else :size="18" />
            <span>{{ activeTheme === 'dark' ? '浅色' : '深色' }}</span>
          </button>
        </nav>
      </div>
    </header>

    <section v-if="state.page === 'connect'" class="connect-view">
      <div class="connect-heading">
        <p class="eyebrow">安全入口</p>
        <h1>连接公网服务器</h1>
        <p>登录后选择家庭，建立端到端 UDP 直连。</p>
      </div>
      <div class="connect-layout">
        <form class="panel connect-form" @submit.prevent="connectServer">
          <div class="panel-title">
            <Server :size="19" />
            <strong>服务器信息</strong>
          </div>
          <label>
            <span>服务器 IP:端口</span>
              <input v-model="state.server" placeholder="your-server.example.com:8080" autocomplete="url" />
          </label>
          <label>
            <span>授权码</span>
            <input v-model="state.authCode" type="password" autocomplete="current-password" />
          </label>
          <button type="submit" class="primary-button" :disabled="state.connecting || !state.server || !state.authCode">
            <Loader2 v-if="state.connecting" class="spin" :size="18" />
            <PlugZap v-else :size="18" />
            {{ state.connecting ? '正在连接' : '连接服务器' }}
          </button>
          <p v-if="state.error" class="error-text">{{ state.error }}</p>
        </form>

        <section class="history-panel">
          <div class="panel-title">
            <Cable :size="19" />
            <strong>最近使用</strong>
          </div>
          <p v-if="!state.savedServers.length" class="empty-text">暂无历史服务器</p>
          <article v-for="sv in state.savedServers" :key="sv.server" class="saved-row">
            <button class="saved-main" @click="quickConnect(sv)">
              <Server :size="17" />
              <span>
                <strong>{{ sv.server }}</strong>
                <small>{{ timeAgo(sv.lastUsed) }}</small>
              </span>
            </button>
            <button class="icon-button danger-icon" @click.stop="removeSavedServer(sv.server)" title="删除服务器">
              <Trash2 :size="16" />
            </button>
          </article>
        </section>
      </div>
    </section>

    <section v-if="['families', 'network', 'status'].includes(state.page)" class="flow-wrap">
      <ol class="flow-steps">
        <li :class="{ active: activeStep === 1, done: activeStep > 1 }">
          <span>1</span>
          <div><strong>选择家庭</strong><small>家庭服务器</small></div>
        </li>
        <li :class="{ active: activeStep === 2, done: activeStep > 2 }">
          <span>2</span>
          <div><strong>网络配置</strong><small>网段模式</small></div>
        </li>
        <li :class="{ active: activeStep === 3 }">
          <span>3</span>
          <div><strong>直连状态</strong><small>实时链路</small></div>
        </li>
      </ol>
    </section>

    <section v-if="state.page === 'families'" class="content">
      <div class="page-heading">
        <div>
          <p class="eyebrow">可见家庭</p>
          <h2>选择目标家庭</h2>
        </div>
        <span class="quiet-status"><Radio :size="15" /> {{ state.families.length }} 个家庭</span>
      </div>
      <div class="list-stack">
        <article v-for="family in state.families" :key="family.id" class="family-row">
          <button class="family-main" @click="openFamilyDetail(family)">
            <span class="family-icon"><Home :size="19" /></span>
            <span class="family-info">
              <span class="family-title">
                <strong>{{ family.name }}</strong>
                <em class="tag" :class="family.visibility === 'public' ? 'tag-public' : 'tag-private'">
                  {{ family.visibility === 'public' ? '公开' : '私密' }}
                </em>
              </span>
              <small>{{ family.lan_cidr || '等待家庭服务器上报网段' }}</small>
            </span>
          </button>
          <span class="server-state" :class="{ online: family.home_server_online }">
            <i />
            {{ family.home_server_online ? '家庭服务器在线' : '家庭服务器离线' }}
          </span>
          <div class="row-actions">
            <button class="icon-button" @click="openFamilyDetail(family)" title="查看详情">
              <ChevronRight :size="18" />
            </button>
            <button class="primary-button compact" :disabled="!family.home_server_online" @click="chooseFamily(family)">
              <Cable :size="17" />
              选择
            </button>
          </div>
        </article>
      </div>
      <p v-if="!state.families.length" class="empty-state">当前没有可访问的家庭</p>
      <p v-if="state.error" class="error-text page-error">{{ state.error }}</p>
    </section>

    <section v-if="state.page === 'familyDetail' && state.detailFamily" class="content">
      <div class="page-heading">
        <div>
          <p class="eyebrow">家庭详情</p>
          <h2>{{ state.detailFamily.name }}</h2>
        </div>
        <button class="primary-button compact" :disabled="!state.detailFamily.home_server_online" @click="chooseFamily(state.detailFamily)">
          <Cable :size="17" />
          选择家庭
        </button>
      </div>
      <div class="metric-grid">
        <article><span>可见性</span><strong>{{ state.detailFamily.visibility === 'public' ? '公开' : '私密' }}</strong></article>
        <article><span>局域网网段</span><strong>{{ state.detailFamily.lan_cidr || '-' }}</strong></article>
        <article><span>家庭服务器</span><strong>{{ state.detailFamily.home_server_online ? '在线' : '离线' }}</strong></article>
        <article><span>累计上行</span><strong>{{ formatBytes(state.familyTraffic.up) }}</strong></article>
        <article><span>累计下行</span><strong>{{ formatBytes(state.familyTraffic.down) }}</strong></article>
      </div>
      <section class="sub-section">
        <h3><Users :size="18" /> 已授权设备</h3>
        <article v-for="dev in state.familyDevices" :key="dev.device_id" class="device-row" @click="openDeviceDetail(dev)">
          <div class="device-info">
            <Monitor v-if="dev.device_type === 'home-server'" :size="18" />
            <Smartphone v-else :size="18" />
            <div>
              <strong>{{ dev.device_id?.slice(0, 16) }}{{ dev.device_id?.length > 16 ? '...' : '' }}</strong>
              <span>{{ dev.device_type === 'home-server' ? '家庭服务器' : '客户端' }}</span>
              <span class="server-state" :class="{ online: dev.online }"><i />{{ dev.online ? '在线' : '离线' }}</span>
            </div>
          </div>
          <ChevronRight :size="18" />
        </article>
        <p v-if="!state.familyDevices.length" class="empty-text">暂无已授权设备</p>
      </section>
      <section v-if="state.familyLogs.length" class="sub-section">
        <h3>最近日志</h3>
        <article v-for="log in state.familyLogs.slice(0, 10)" :key="log.id" class="log-row">
          <span class="log-level" :class="'level-' + log.level">{{ log.level }}</span>
          <span class="log-msg">{{ log.message }}</span>
          <span class="log-time">{{ formatTime(log.created_at) }}</span>
        </article>
      </section>
    </section>

    <section v-if="state.page === 'deviceDetail' && state.detailDevice" class="content">
      <div class="page-heading">
        <div>
          <p class="eyebrow">设备详情</p>
          <h2>{{ state.detailDevice.device_id }}</h2>
        </div>
      </div>
      <div class="metric-grid">
        <article><span>设备类型</span><strong>{{ state.detailDevice.device_type === 'home-server' ? '家庭服务器' : '客户端' }}</strong></article>
        <article><span>在线状态</span><strong>{{ state.detailDevice.online ? '在线' : '离线' }}</strong></article>
        <article><span>延迟</span><strong>{{ latencyText(state.detailDevice.latency_ms) }}</strong></article>
        <article><span>最后在线</span><strong>{{ timeAgo(state.detailDevice.last_online) }}</strong></article>
        <article><span>局域网网段</span><strong>{{ state.detailDevice.lan_cidr || '-' }}</strong></article>
        <article><span>UDP 端口</span><strong>{{ state.detailDevice.udp_port || '-' }}</strong></article>
        <article><span>累计上行</span><strong>{{ formatBytes(state.deviceTraffic.up) }}</strong></article>
        <article><span>累计下行</span><strong>{{ formatBytes(state.deviceTraffic.down) }}</strong></article>
        <article v-if="state.deviceInfo.ws_endpoint"><span>连接端点</span><strong>{{ state.deviceInfo.ws_endpoint }}</strong></article>
        <article><span>黑名单</span><strong>{{ state.detailDevice.is_blacklisted ? '已拉黑' : '正常' }}</strong></article>
      </div>
    </section>

    <section v-if="state.page === 'network'" class="content">
      <div class="page-heading">
        <div>
          <p class="eyebrow">{{ currentFamily?.name }}</p>
          <h2>选择网络模式</h2>
        </div>
        <button class="secondary-button compact" @click="state.page = 'families'">
          <ArrowLeft :size="17" />
          更换家庭
        </button>
      </div>
      <div class="context-strip">
        <Home :size="18" />
        <span><strong>{{ currentFamily?.name }}</strong><small>{{ currentFamily?.lan_cidr || '未上报网段' }}</small></span>
        <span class="server-state online"><i />家庭服务器在线</span>
      </div>
      <div v-if="state.conflict?.conflict" class="warning">
        <CircleAlert :size="19" />
        本地网络与家庭网段冲突，已切换到备用虚拟网段。
      </div>
      <div class="network-layout">
        <div class="mode-grid">
          <label :class="{ selected: state.networkMode === 'real', disabled: state.conflict?.conflict }">
            <input v-model="state.networkMode" type="radio" value="real" :disabled="state.conflict?.conflict" />
            <Network :size="21" />
            <strong>真实同网段</strong>
            <span>直接使用家庭局域网地址。</span>
          </label>
          <label :class="{ selected: state.networkMode === 'mapped' }">
            <input v-model="state.networkMode" type="radio" value="mapped" />
            <Route :size="21" />
            <strong>虚拟映射</strong>
            <span>用备用网段映射家庭设备。</span>
          </label>
        </div>
        <form class="panel action-panel" @submit.prevent="connectFamily">
          <div class="panel-title"><Zap :size="19" /><strong>建立 UDP 直连</strong></div>
          <label v-if="state.networkMode === 'mapped'">
            <span>备用虚拟网段</span>
            <input v-model="state.virtualCIDR" :disabled="state.connecting" />
          </label>
          <div class="action-summary">
            <span>目标家庭</span><strong>{{ currentFamily?.name }}</strong>
            <span>连接模式</span><strong>{{ state.networkMode === 'mapped' ? '虚拟映射' : '真实同网段' }}</strong>
          </div>
          <button type="submit" class="primary-button" :disabled="state.connecting">
            <Loader2 v-if="state.connecting" class="spin" :size="18" />
            <PlugZap v-else :size="18" />
            {{ state.connecting ? '正在建立直连' : '建立直连' }}
          </button>
          <p v-if="state.error" class="error-text">{{ state.error }}</p>
        </form>
      </div>
    </section>

    <section v-if="state.page === 'status'" class="content">
      <div class="status-heading">
        <div>
          <p class="eyebrow">实时链路</p>
          <h2>{{ currentFamily?.name || '家庭网络' }}</h2>
        </div>
        <span class="live-update"><RefreshCw :size="14" /> 自动更新</span>
      </div>
      <section class="link-overview" :class="{ connected: tunnelConnected }">
        <span class="overview-icon"><Gauge :size="22" /></span>
        <div>
          <small>客户端到家庭服务器</small>
          <strong>{{ tunnelConnected ? 'UDP 直连已建立' : '等待连接' }}</strong>
        </div>
        <span class="overview-latency">
          <small>直连 RTT</small>
          <strong>{{ latencyText(state.stats.tunnel_rtt_ms) }}</strong>
        </span>
      </section>
      <div class="metric-grid status-metrics">
        <article><span>公网服务器 RTT</span><strong>{{ latencyText(state.stats.latency_ms) }}</strong></article>
        <article><span>上行</span><strong>{{ formatBytes(state.stats.up) }}</strong></article>
        <article><span>下行</span><strong>{{ formatBytes(state.stats.down) }}</strong></article>
        <article><span>丢包率</span><strong>{{ state.stats.loss || 0 }}%</strong></article>
      </div>
      <section class="connection-facts">
        <div><span>信令连接</span><strong>{{ connectionText(state.status.websocket) }}</strong></div>
        <div><span>UDP 隧道</span><strong>{{ connectionText(state.status.udp) }}</strong></div>
        <div><span>家庭侧地址</span><strong>{{ state.tunnel?.client_home_ip || '-' }}</strong></div>
        <div v-if="state.status.grace_seconds"><span>宽限期</span><strong>{{ state.status.grace_seconds }} s</strong></div>
      </section>
      <section class="device-map" v-if="state.tunnel?.devices?.length">
        <h3>设备映射</h3>
        <article v-for="device in state.tunnel.devices" :key="device.real_ip">
          <span>{{ device.name }}</span>
          <strong>{{ device.virtual_ip }}</strong>
          <small>真实地址 {{ device.real_ip }}</small>
        </article>
      </section>
      <button class="danger-button" @click="disconnect">
        <PlugZap :size="18" />
        断开连接
      </button>
      <p v-if="state.status.last_error" class="error-text page-error">{{ state.status.last_error }}</p>
    </section>

    <section v-if="state.page === 'settings'" class="content settings-view">
      <div class="page-heading">
        <div>
          <p class="eyebrow">客户端</p>
          <h2>设置</h2>
        </div>
      </div>
      <section class="settings-row">
        <div class="settings-title">
          <Smartphone :size="20" />
          <span><strong>Go Home Client</strong><small>版本 0.2.0</small></span>
        </div>
        <button class="secondary-button" @click="checkUpdate">
          <RefreshCw :size="17" />
          检查更新
        </button>
      </section>
      <p v-if="state.update" class="notice-text">
        {{ !state.update.configured ? '未配置远程版本清单' : state.update.update ? `发现新版本 ${state.update.latest}` : '当前已是最新版本' }}
      </p>
      <p v-if="state.error" class="error-text">{{ state.error }}</p>
    </section>
  </main>
</template>
