<script setup>
import { computed, reactive, ref } from 'vue'
import {
  Activity,
  Cable,
  Check,
  CircleAlert,
  Home,
  Loader2,
  Network,
  PlugZap,
  RefreshCw,
  Route,
  Settings,
  ShieldCheck,
  Smartphone
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
  update: null
})

const currentFamily = computed(() => state.selectedFamily)

async function connectServer() {
  state.error = ''
  state.connecting = true
  try {
    await backend.connectServer(state.server, state.authCode)
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
}

async function checkUpdate() {
  state.update = await backend.checkUpdate()
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
</script>

<template>
  <main class="client-shell">
    <header class="topbar">
      <div class="brand">
        <ShieldCheck :size="26" />
        <strong>Go Home</strong>
      </div>
      <nav>
        <button :class="{ active: state.page === 'families' }" @click="state.page = 'families'" :disabled="!state.families.length">
          <Home :size="18" />
          家庭
        </button>
        <button :class="{ active: state.page === 'status' }" @click="state.page = 'status'" :disabled="!state.tunnel">
          <Activity :size="18" />
          状态
        </button>
        <button :class="{ active: state.page === 'settings' }" @click="state.page = 'settings'">
          <Settings :size="18" />
          设置
        </button>
      </nav>
    </header>

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
    </section>

    <section v-if="state.page === 'families'" class="content">
      <div class="section-title">
        <p>可见家庭</p>
        <h2>选择要连接的家庭</h2>
      </div>
      <article v-for="family in state.families" :key="family.id" class="family-row">
        <div>
          <strong>{{ family.name }}</strong>
          <span>{{ family.visibility === 'public' ? '公开家庭' : '私密家庭' }}</span>
          <span>LAN {{ family.lan_cidr || '等待家庭服务器上报' }}</span>
        </div>
        <button :disabled="!family.home_server_online" @click="chooseFamily(family)">
          <Cable :size="18" />
          {{ family.home_server_online ? '连接' : '离线' }}
        </button>
      </article>
    </section>

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
    </section>

    <section v-if="state.page === 'settings'" class="content split">
      <div class="section-title">
        <p>客户端</p>
        <h2>设置</h2>
      </div>
      <div class="config-panel">
        <div class="about-row">
          <Smartphone :size="22" />
          <span>Go Home Client UI 0.1.0</span>
        </div>
        <button @click="checkUpdate">
          <RefreshCw :size="18" />
          检查更新
        </button>
        <p v-if="state.update">
          {{ state.update.update ? `发现新版本 ${state.update.latest}` : '当前已是最新版本' }}
        </p>
      </div>
    </section>
  </main>
</template>
