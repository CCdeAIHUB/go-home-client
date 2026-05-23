<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  Activity,
  ArrowLeft,
  Cable,
  Check,
  ChevronRight,
  CircleAlert,
  Home,
  Loader2,
  Monitor,
  Network,
  PlugZap,
  RefreshCw,
  Route,
  Server,
  Settings,
  ShieldCheck,
  Smartphone,
  Trash2,
  Users,
  Wifi,
  WifiOff
} from '@lucide/vue'
import { api } from './api'

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
  stats: { up: 0, down: 0, loss: 0, latency_ms: 0 },
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

const currentFamily = computed(() => state.selectedFamily)

let statusTimer = null

onMounted(() => {
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
})

onBeforeUnmount(() => {
  window.clearInterval(statusTimer)
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
    state.families = await backend.listFamilies()
    state.page = 'families'
  } catch (error) {
    state.error = error.message
  } finally {
    state.connecting = false
  }
}

async function chooseFamily(family) {
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
        <ShieldCheck :size="26" />
        <strong>Go Home</strong>
      </div>
      <nav>
        <button v-if="state.backPage || state.detailFamily || state.detailDevice || state.page === 'settings'" @click="goBack" class="back-btn">
          <ArrowLeft :size="18" />
          返回
        </button>
        <button :class="{ active: state.page === 'families' }" @click="state.page = 'families'; state.detailFamily = null; state.detailDevice = null; state.backPage = null" :disabled="!state.families.length">
          <Home :size="18" />
          家庭
        </button>
        <button :class="{ active: state.page === 'status' }" @click="state.page = 'status'; state.detailFamily = null; state.detailDevice = null; state.backPage = null" :disabled="!state.tunnel">
          <Activity :size="18" />
          状态
        </button>
        <button :class="{ active: state.page === 'settings' }" @click="navigateToSettings">
          <Settings :size="18" />
          设置
        </button>
      </nav>
    </header>

    <!-- Connect page with saved servers -->
    <section v-if="state.page === 'connect'" class="connect-view">
      <div class="connect-copy">
        <p>服务器连接</p>
        <h1>选择家庭，然后直连回家</h1>
      </div>
      <form class="connect-form" @submit.prevent="connectServer">
        <label>
          服务器 IP:端口
          <input v-model="state.server" placeholder="127.0.0.1:8080" />
        </label>
        <label>
          授权码
          <input v-model="state.authCode" />
        </label>
        <button type="submit" :disabled="state.connecting">
          <Loader2 v-if="state.connecting" class="spin" :size="18" />
          <PlugZap v-else :size="18" />
          {{ state.connecting ? '连接中' : '连接服务器' }}
        </button>
        <p v-if="state.error" class="error-text">{{ state.error }}</p>
      </form>

      <!-- Saved servers list -->
      <div v-if="state.savedServers.length" class="saved-servers">
        <h3>历史服务器</h3>
        <article v-for="sv in state.savedServers" :key="sv.server" class="saved-row">
          <div class="saved-info" @click="quickConnect(sv)">
            <Server :size="18" />
            <div>
              <strong>{{ sv.server }}</strong>
              <span>上次连接 {{ timeAgo(sv.lastUsed) }}</span>
            </div>
          </div>
          <button class="icon-btn danger-icon" @click.stop="removeSavedServer(sv.server)" title="删除">
            <Trash2 :size="16" />
          </button>
        </article>
      </div>
    </section>

    <!-- Families list -->
    <section v-if="state.page === 'families'" class="content">
      <div class="section-title">
        <p>可见家庭</p>
        <h2>选择要连接的家庭</h2>
      </div>
      <article v-for="family in state.families" :key="family.id" class="family-row">
        <div class="family-info" @click="openFamilyDetail(family)">
          <strong>{{ family.name }}</strong>
          <span class="tag" :class="family.visibility === 'public' ? 'tag-public' : 'tag-private'">
            {{ family.visibility === 'public' ? '公开家庭' : '私密家庭' }}
          </span>
          <span>LAN {{ family.lan_cidr || '等待家庭服务器上报' }}</span>
        </div>
        <div class="family-actions">
          <button class="icon-btn" @click="openFamilyDetail(family)" title="详情">
            <ChevronRight :size="18" />
          </button>
          <button :disabled="!family.home_server_online" @click="chooseFamily(family)">
            <Cable :size="18" />
            {{ family.home_server_online ? '连接' : '离线' }}
          </button>
        </div>
      </article>
    </section>

    <!-- Family detail -->
    <section v-if="state.page === 'familyDetail' && state.detailFamily" class="content">
      <div class="section-title">
        <p>家庭详情</p>
        <h2>{{ state.detailFamily.name }}</h2>
      </div>

      <div class="metric-grid">
        <article>
          <span>可见性</span>
          <strong>{{ state.detailFamily.visibility === 'public' ? '公开' : '私密' }}</strong>
        </article>
        <article>
          <span>局域网网段</span>
          <strong>{{ state.detailFamily.lan_cidr || '-' }}</strong>
        </article>
        <article>
          <span>家庭服务器</span>
          <strong>{{ state.detailFamily.home_server_online ? '在线' : '离线' }}</strong>
        </article>
        <article>
          <span>累计上行</span>
          <strong>{{ formatBytes(state.familyTraffic.up) }}</strong>
        </article>
        <article>
          <span>累计下行</span>
          <strong>{{ formatBytes(state.familyTraffic.down) }}</strong>
        </article>
      </div>

      <div class="sub-section">
        <h3><Users :size="18" /> 已授权设备</h3>
        <article v-for="dev in state.familyDevices" :key="dev.device_id" class="device-row" @click="openDeviceDetail(dev)">
          <div class="device-info">
            <Monitor v-if="dev.device_type === 'home-server'" :size="18" />
            <Smartphone v-else :size="18" />
            <div>
              <strong>{{ dev.device_id.substring(0, 16) }}…</strong>
              <span class="tag" :class="dev.online ? 'tag-public' : 'tag-private'">
                {{ dev.device_type === 'home-server' ? '家庭服务器' : '客户端' }}
              </span>
              <span>{{ dev.online ? '在线' : '离线' }}</span>
            </div>
          </div>
          <button class="icon-btn" title="详情">
            <ChevronRight :size="18" />
          </button>
        </article>
        <p v-if="!state.familyDevices.length" class="empty-text">暂无已授权设备</p>
      </div>

      <div v-if="state.familyLogs.length" class="sub-section">
        <h3>最近日志</h3>
        <article v-for="log in state.familyLogs.slice(0, 10)" :key="log.id" class="log-row">
          <span class="log-level" :class="'level-' + log.level">{{ log.level }}</span>
          <span class="log-msg">{{ log.message }}</span>
          <span class="log-time">{{ formatTime(log.created_at) }}</span>
        </article>
      </div>
    </section>

    <!-- Device detail -->
    <section v-if="state.page === 'deviceDetail' && state.detailDevice" class="content">
      <div class="section-title">
        <p>设备详情</p>
        <h2>{{ state.detailDevice.device_id }}</h2>
      </div>

      <div class="metric-grid">
        <article>
          <span>设备类型</span>
          <strong>{{ state.detailDevice.device_type === 'home-server' ? '家庭服务器' : '客户端' }}</strong>
        </article>
        <article>
          <span>在线状态</span>
          <strong>
            <Wifi v-if="state.detailDevice.online" :size="18" class="online-icon" />
            <WifiOff v-else :size="18" class="offline-icon" />
            {{ state.detailDevice.online ? '在线' : '离线' }}
          </strong>
        </article>
        <article>
          <span>延迟</span>
          <strong>{{ state.detailDevice.latency_ms || 0 }} ms</strong>
        </article>
        <article>
          <span>最后在线</span>
          <strong>{{ timeAgo(state.detailDevice.last_online) }}</strong>
        </article>
        <article>
          <span>局域网网段</span>
          <strong>{{ state.detailDevice.lan_cidr || '-' }}</strong>
        </article>
        <article>
          <span>UDP端口</span>
          <strong>{{ state.detailDevice.udp_port || '-' }}</strong>
        </article>
        <article>
          <span>累计上行</span>
          <strong>{{ formatBytes(state.deviceTraffic.up) }}</strong>
        </article>
        <article>
          <span>累计下行</span>
          <strong>{{ formatBytes(state.deviceTraffic.down) }}</strong>
        </article>
        <article v-if="state.deviceInfo.ws_endpoint">
          <span>连接端点</span>
          <strong>{{ state.deviceInfo.ws_endpoint }}</strong>
        </article>
        <article>
          <span>黑名单</span>
          <strong>{{ state.detailDevice.is_blacklisted ? '已拉黑' : '正常' }}</strong>
        </article>
      </div>
    </section>

    <!-- Network mode -->
    <section v-if="state.page === 'network'" class="content split">
      <div>
        <div class="section-title">
          <p>{{ currentFamily?.name }}</p>
          <h2>网络模式</h2>
        </div>
        <div v-if="state.conflict?.conflict" class="warning">
          <CircleAlert :size="20" />
          家庭网段与本地网络冲突，请使用备用虚拟网段。
        </div>
        <div class="mode-grid">
          <label :class="{ selected: state.networkMode === 'real', disabled: state.conflict?.conflict }">
            <input v-model="state.networkMode" type="radio" value="real" :disabled="state.conflict?.conflict" />
            <Network :size="22" />
            <strong>真实同网段</strong>
            <span>客户端使用家庭真实网段 IP。</span>
          </label>
          <label :class="{ selected: state.networkMode === 'mapped' }">
            <input v-model="state.networkMode" type="radio" value="mapped" />
            <Route :size="22" />
            <strong>虚拟映射</strong>
            <span>用备用网段映射家庭设备。</span>
          </label>
        </div>
      </div>
      <form class="config-panel" @submit.prevent="connectFamily">
        <label v-if="state.networkMode === 'mapped'">
          备用虚拟网段
          <input v-model="state.virtualCIDR" />
        </label>
        <button type="submit" :disabled="state.connecting">
          <Loader2 v-if="state.connecting" class="spin" :size="18" />
          <PlugZap v-else :size="18" />
          {{ state.connecting ? '正在打洞' : '建立直连' }}
        </button>
        <p v-if="state.error" class="error-text">{{ state.error }}</p>
      </form>
    </section>

    <!-- Status -->
    <section v-if="state.page === 'status'" class="content">
      <div class="status-header">
        <div class="section-title">
          <p>隧道状态</p>
          <h2>{{ state.tunnel?.mode === 'mapped' ? '虚拟映射模式' : '真实同网段模式' }}</h2>
        </div>
        <button @click="refreshStatus">
          <RefreshCw :size="18" />
          刷新
        </button>
      </div>

      <div class="metric-grid">
        <article>
          <span>WebSocket</span>
          <strong>{{ state.status.websocket }}</strong>
        </article>
        <article>
          <span>UDP</span>
          <strong>{{ state.status.udp }}</strong>
        </article>
        <article>
          <span>家庭侧地址</span>
          <strong>{{ state.tunnel?.client_home_ip || '-' }}</strong>
        </article>
        <article v-if="state.status.grace_seconds">
          <span>宽限期</span>
          <strong>{{ state.status.grace_seconds }} s</strong>
        </article>
      </div>

      <div class="metric-grid">
        <article>
          <span>上行</span>
          <strong>{{ formatBytes(state.stats.up) }}</strong>
        </article>
        <article>
          <span>下行</span>
          <strong>{{ formatBytes(state.stats.down) }}</strong>
        </article>
        <article>
          <span>丢包率</span>
          <strong>{{ state.stats.loss }}%</strong>
        </article>
        <article>
          <span>公网服务器延迟</span>
          <strong>{{ state.stats.latency_ms || 0 }} ms</strong>
        </article>
      </div>

      <section class="device-map" v-if="state.tunnel?.devices?.length">
        <h3>设备映射</h3>
        <article v-for="device in state.tunnel.devices" :key="device.real_ip">
          <span>{{ device.name }}</span>
          <strong>{{ device.virtual_ip }}</strong>
          <small>真实地址 {{ device.real_ip }}</small>
        </article>
      </section>

      <button class="danger" @click="disconnect">
        <PlugZap :size="18" />
        断开连接
      </button>
      <p v-if="state.status.last_error" class="error-text">{{ state.status.last_error }}</p>
    </section>

    <!-- Settings -->
    <section v-if="state.page === 'settings'" class="content split">
      <div class="section-title">
        <p>客户端</p>
        <h2>设置</h2>
      </div>
      <div class="config-panel">
        <div class="about-row">
          <Smartphone :size="22" />
          <span>Go Home Client UI 0.2.0</span>
        </div>
        <button @click="checkUpdate">
          <RefreshCw :size="18" />
          检查更新
        </button>
        <p v-if="state.update">
          {{ !state.update.configured ? '未配置远程版本清单' : state.update.update ? `发现新版本 ${state.update.latest}` : '当前已是最新版本' }}
        </p>
        <p v-if="state.error" class="error-text">{{ state.error }}</p>
      </div>
    </section>
  </main>
</template>
