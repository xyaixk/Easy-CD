<script setup>
import { ref, reactive, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { queryLogs, fetchHistogram, fetchLogServices, fetchLogLabelValues, exportLogs, getLogsTailWsUrl } from '@/api/logs'
import LogSearchBar from './LogSearchBar.vue'
import LogTimePicker from './LogTimePicker.vue'
import LogFacetPanel from './LogFacetPanel.vue'
import LogHistogram from './LogHistogram.vue'
import LogList from './LogList.vue'
import LogContextDialog from './LogContextDialog.vue'
import TracePanel from './TracePanel.vue'

const props = defineProps({
  currentEnvironment: { type: Object, default: null },
  environments: { type: Array, default: () => [] },
  active: { type: Boolean, default: false }
})
const emit = defineEmits(['back', 'update:selectedEnv'])

// ============ 筛选状态（搜索栏 / facet 栏 / 时间选择器直接读写） ============
// levels 为空 = 全部级别（后端不加级别过滤）
const filters = reactive({
  keyword: '', traceId: '', logger: '', containerName: '', thread: '',
  services: [], images: [], levels: [],
  timeRange: '1h', from: '', to: ''
})

// ============ 数据状态 ============
const logs = ref([])           // 时间正序（旧上新下）
const hasMore = ref(false)
const isLoading = ref(false)
const loadingOlder = ref(false)
const buckets = ref([])
const histogramLoading = ref(false)
const availableServices = ref([])
const availableContainers = ref([])
const availableImages = ref([])
const errorMsg = ref('')

// ============ 实时 tail ============
const live = ref(false)
const LIVE_BUFFER_MAX = 5000
let ws = null

// ============ 子组件引用 / 面板状态 ============
const listRef = ref(null)
const ctxVisible = ref(false)
const ctxAnchor = ref(null)
const traceVisible = ref(false)
const traceRow = ref(null)
const facetVisible = ref(true)

const initialized = ref(false)

// ============ 行 key：tsNanos + 同值序号（避免重复时间戳撞 key） ============
const normalizeRows = (items) => {
  const occurrences = new Map()
  return items.map((it) => {
    const sig = it.tsNanos || it.timestamp || ''
    const occurrence = occurrences.get(sig) || 0
    occurrences.set(sig, occurrence + 1)
    return { ...it, key: `${sig}:${occurrence}` }
  })
}

const buildQueryBody = (extra = {}) => ({
  envId: props.currentEnvironment?.id || null,
  envName: props.currentEnvironment?.name || '',
  services: filters.services,
  images: filters.images,
  levels: filters.levels,
  keyword: filters.keyword || null,
  traceId: filters.traceId ? filters.traceId.trim() : null,
  logger: filters.logger ? filters.logger.trim() : null,
  containerName: filters.containerName ? filters.containerName.trim() : null,
  thread: filters.thread ? filters.thread.trim() : null,
  timeRange: filters.timeRange,
  from: filters.timeRange === 'custom' ? (filters.from || null) : null,
  to: filters.timeRange === 'custom' ? (filters.to || null) : null,
  limit: 500,
  ...extra
})

// ============ 查询编排 ============
const loadAvailableServices = async () => {
  const envId = props.currentEnvironment?.id
  if (!envId) { availableServices.value = []; availableContainers.value = []; availableImages.value = []; return }
  const [svc, ctn, img] = await Promise.allSettled([
    fetchLogServices(envId),
    fetchLogLabelValues(envId, 'container_name'),
    fetchLogLabelValues(envId, 'image_name')
  ])
  availableServices.value = svc.status === 'fulfilled' && Array.isArray(svc.value) ? svc.value : []
  availableContainers.value = ctn.status === 'fulfilled' && Array.isArray(ctn.value) ? ctn.value : []
  availableImages.value = img.status === 'fulfilled' && Array.isArray(img.value) ? img.value : []
}

const runQuery = async () => {
  if (!props.currentEnvironment?.id) {
    logs.value = []; buckets.value = []; hasMore.value = false
    return
  }
  // 实时模式下条件变化：重启 tail 而不是走范围查询
  if (live.value) {
    stopLive()
    startLive()
    return
  }
  isLoading.value = true
  errorMsg.value = ''
  loadHistogram()
  try {
    const data = await queryLogs(buildQueryBody())
    // backward 返回新->旧，展示按时间正序（旧上新下）
    const items = ((data && data.items) || []).slice().reverse()
    logs.value = normalizeRows(items)
    hasMore.value = !!(data && data.hasMore)
    await nextTick()
    listRef.value?.scrollToBottom()
  } catch (e) {
    logs.value = []; hasMore.value = false
    errorMsg.value = '日志查询失败'
  } finally {
    isLoading.value = false
  }
}

const loadHistogram = async () => {
  histogramLoading.value = true
  try {
    const data = await fetchHistogram(buildQueryBody())
    buckets.value = Array.isArray(data) ? data : []
  } catch (e) {
    buckets.value = []
  } finally {
    histogramLoading.value = false
  }
}

// 向上翻页：用当前最旧一行的 tsNanos 作游标，结果 prepend 并保持滚动位置
const loadOlder = async () => {
  if (loadingOlder.value || !hasMore.value || !logs.value.length) return
  const oldest = logs.value[0]
  if (!oldest?.tsNanos) return
  loadingOlder.value = true
  try {
    const data = await queryLogs(buildQueryBody({ beforeNanos: oldest.tsNanos }))
    const older = ((data && data.items) || []).slice().reverse()
    if (older.length) {
      logs.value = normalizeRows([...older, ...logs.value])
      listRef.value?.compensatePrepend(older.length)
    }
    hasMore.value = !!(data && data.hasMore)
  } catch (e) {
    errorMsg.value = '加载更早日志失败'
  } finally {
    loadingOlder.value = false
  }
}

// ============ 实时 tail ============
const toggleLive = () => {
  if (live.value) stopLive()
  else startLive()
}
const startLive = () => {
  if (!props.currentEnvironment?.id) return
  errorMsg.value = ''
  const url = getLogsTailWsUrl({
    envId: props.currentEnvironment.id,
    services: filters.services,
    images: filters.images,
    levels: filters.levels,
    keyword: filters.keyword,
    traceId: filters.traceId ? filters.traceId.trim() : '',
    logger: filters.logger ? filters.logger.trim() : '',
    containerName: filters.containerName ? filters.containerName.trim() : '',
    thread: filters.thread ? filters.thread.trim() : ''
  })
  try {
    ws = new WebSocket(url)
  } catch (e) {
    errorMsg.value = '实时连接建立失败'
    return
  }
  live.value = true
  hasMore.value = false
  ws.onmessage = (evt) => {
    let payload
    try { payload = JSON.parse(evt.data) } catch (e) { return }
    if (Array.isArray(payload)) {
      if (!payload.length) return
      let merged = [...logs.value, ...payload]
      // 缓冲上限：丢弃最旧
      if (merged.length > LIVE_BUFFER_MAX) merged = merged.slice(merged.length - LIVE_BUFFER_MAX)
      logs.value = normalizeRows(merged)
    } else if (payload && payload.error) {
      errorMsg.value = payload.error
    }
  }
  ws.onclose = () => {
    if (live.value) {
      live.value = false
      if (!errorMsg.value) errorMsg.value = '实时连接已断开'
    }
    ws = null
  }
  ws.onerror = () => {
    if (live.value) errorMsg.value = '实时连接异常'
  }
}
const stopLive = () => {
  live.value = false
  if (ws) {
    try { ws.close() } catch (e) { /* ignore */ }
    ws = null
  }
}

// ============ 直方图框选 -> 自定义时间范围重查 ============
const fmtLocal = (ms) => {
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
const onBrush = ({ fromMs, toMs }) => {
  filters.timeRange = 'custom'
  filters.from = fmtLocal(fromMs)
  filters.to = fmtLocal(toMs)
  runQuery()
}

// ============ 上下文 / trace ============
const showContext = (row) => { ctxAnchor.value = row; ctxVisible.value = true }
const showTrace = (row) => {
  if (!row?.traceId) return
  traceRow.value = row
  traceVisible.value = true
}

// ============ 导出 ============
const onExport = async () => {
  if (!props.currentEnvironment?.id) return
  try {
    await exportLogs(buildQueryBody(), `logs-${props.currentEnvironment.name}-${Date.now()}.log`)
  } catch (e) {
    errorMsg.value = '日志导出失败'
  }
}

// ============ 初始化 / 环境切换 ============
const init = () => {
  initialized.value = true
  loadAvailableServices()
  runQuery()
}
watch(() => props.active, (v) => {
  if (v && !initialized.value) init()
})
// 首次挂载时 active 可能已为 true（v-if 延迟挂载），watch 不会触发
onMounted(() => {
  if (props.active && !initialized.value) init()
})
onUnmounted(() => stopLive())
watch(() => props.currentEnvironment?.id, () => {
  if (!initialized.value) return
  stopLive()
  filters.services = []
  filters.images = []
  loadAvailableServices()
  runQuery()
})
</script>

<template>
  <div class="logs-page">
    <!-- 顶部工具条：返回 | 环境 | 智能搜索栏 | 时间 | 实时 | 导出 -->
    <div class="page-toolbar">
      <button class="icon-btn" title="返回部署视图" @click="emit('back')">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>
        </svg>
      </button>
      <button class="icon-btn" :class="{ active: facetVisible }" title="字段栏" @click="facetVisible = !facetVisible">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="3" width="7" height="18" rx="1"/><rect x="14" y="3" width="7" height="18" rx="1"/>
        </svg>
      </button>
      <select v-if="environments.length" class="env-select"
        :value="currentEnvironment?.id"
        @change="emit('update:selectedEnv', Number($event.target.value))">
        <option v-for="env in environments" :key="env.id" :value="env.id">{{ env.name }}</option>
      </select>
      <span v-else class="env-badge-warn">未选择环境</span>

      <LogSearchBar
        :filters="filters"
        :available-services="availableServices"
        :available-containers="availableContainers"
        :available-images="availableImages"
        :logs="logs"
        @search="runQuery"/>

      <LogTimePicker :filters="filters" :disabled="live" @change="runQuery"/>

      <button class="live-btn" :class="{ on: live }" @click="toggleLive">
        <span class="live-dot" :class="{ on: live }"></span>
        {{ live ? '停止' : '实时' }}
      </button>
      <button class="icon-btn" title="重新查询" :disabled="live" @click="runQuery">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
        </svg>
      </button>
      <button class="icon-btn" title="导出日志" :disabled="live" @click="onExport">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
        </svg>
      </button>
      <span v-if="errorMsg" class="toolbar-err">{{ errorMsg }}</span>
    </div>

    <!-- 主体：左 facet + 右（直方图 + 列表） -->
    <div class="page-body">
      <LogFacetPanel v-show="facetVisible"
        :filters="filters"
        :available-services="availableServices"
        :available-images="availableImages"
        :logs="logs"
        @search="runQuery"/>

      <div class="content-col">
        <!-- 直方图（实时模式下隐藏） -->
        <LogHistogram v-if="!live" :buckets="buckets" :loading="histogramLoading" @brush="onBrush"/>

        <!-- 日志列表 -->
        <LogList
          ref="listRef"
          :logs="logs"
          :loading="isLoading"
          :loading-older="loadingOlder"
          :has-more="hasMore"
          :live="live"
          @load-older="loadOlder"
          @show-context="showContext"
          @show-trace="showTrace"/>
      </div>
    </div>

    <!-- 上下文弹窗 -->
    <LogContextDialog
      :visible="ctxVisible"
      :env-id="currentEnvironment?.id || null"
      :anchor="ctxAnchor"
      @update:visible="ctxVisible = $event"/>

    <!-- trace 面板 -->
    <TracePanel
      :visible="traceVisible"
      :env-id="currentEnvironment?.id || null"
      :trace-id="traceRow?.traceId || ''"
      :anchor-timestamp="traceRow?.timestamp || ''"
      @update:visible="traceVisible = $event"/>
  </div>
</template>

<style scoped>
.logs-page {
  flex: 1; min-height: 0;
  display: flex; flex-direction: column;
  background: var(--bg-secondary);
}

.page-toolbar {
  display: flex; align-items: flex-start; gap: .5rem;
  padding: .55rem .9rem;
  background: var(--bg-primary); border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}
.icon-btn {
  width: 36px; height: 36px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--border-color); border-radius: 8px;
  background: var(--bg-secondary); color: var(--text-primary); cursor: pointer;
}
.icon-btn:hover:not(:disabled) { border-color: var(--primary-color); color: var(--primary-color); }
.icon-btn:disabled { opacity: .45; cursor: not-allowed; }
.icon-btn.active { border-color: var(--primary-color); color: var(--primary-color); background: var(--primary-light); }

.env-select {
  height: 36px; padding: 0 .6rem; border-radius: 8px; flex-shrink: 0;
  border: 1px solid var(--border-color); background: var(--bg-secondary);
  color: var(--primary-color); font-size: .8rem; font-weight: 600; cursor: pointer;
  max-width: 160px;
}
.env-select:focus { border-color: var(--primary-color); outline: none; }
.env-badge-warn {
  align-self: center; padding: .12rem .6rem; border-radius: 999px;
  background: #fef3c7; color: #b45309; font-size: .72rem; font-weight: 600; flex-shrink: 0;
}

.live-btn {
  height: 36px; padding: 0 .8rem; flex-shrink: 0;
  display: inline-flex; align-items: center; gap: .4rem;
  border: 1px solid var(--border-color); border-radius: 8px;
  background: var(--bg-secondary); color: var(--text-primary);
  font-size: .8rem; font-weight: 600; cursor: pointer;
}
.live-btn:hover { border-color: #10b981; color: #059669; }
.live-btn.on { border-color: #10b981; background: #d1fae5; color: #047857; }
.live-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-tertiary); }
.live-dot.on { background: #10b981; animation: live-pulse 1.2s ease-in-out infinite; }
@keyframes live-pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(16,185,129,.5); }
  50% { opacity: .7; box-shadow: 0 0 0 4px rgba(16,185,129,0); }
}

.toolbar-err { align-self: center; font-size: .78rem; color: #ef4444; flex-shrink: 0; }

.page-body {
  flex: 1; min-height: 0;
  display: flex;
}
.content-col {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column;
}
</style>
