<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

const props = defineProps({
  visible: { type: Boolean, default: false },
  currentEnvironment: { type: Object, default: null }
})
const emit = defineEmits(['update:visible'])

// ============ 筛选条件 ============
const keyword = ref('')
const traceId = ref('')
const logger = ref('')
const containerName = ref('')
const thread = ref('')
const selectedServices = ref([])
const selectedLevels = ref(['INFO', 'WARN', 'ERROR'])
const timeRange = ref('1h')
const customFrom = ref('')
const customTo = ref('')
// 已应用的自定义时间（仅在点击“应用”后同步），用于摘要显示
const appliedFrom = ref('')
const appliedTo = ref('')
// 自定义时间浮层
const showCustomPicker = ref(false)
const customPickerRef = ref(null)

// 可选服务列表（在打开/切换环境时由后端聚合返回）
const availableServices = ref([])
const allLevels = ['DEBUG', 'INFO', 'WARN', 'ERROR']
const timePresets = [
  { key: '15m', label: '近 15 分钟' },
  { key: '1h',  label: '近 1 小时' },
  { key: '6h',  label: '近 6 小时' },
  { key: '24h', label: '近 24 小时' },
  { key: 'custom', label: '自定义' }
]

// ============ 筛选区展开/收起 ============
const filterExpanded = ref(false)

// ============ 视图模式 ============
const viewMode = ref('table') // 'table' | 'console'

// ============ 分页（真分页，从后端按 page+size 拉） ============
const pageSize = ref(100)
const currentPage = ref(1)
const pageSizeOptions = [100, 500, 1000, 5000, -1]

// ============ 自动刷新 ============
const autoRefreshInterval = ref(0)
const autoRefreshOptions = [
  { value: 0,     label: '关闭' },
  { value: 1000,  label: '1s' },
  { value: 5000,  label: '5s' },
  { value: 10000, label: '10s' },
  { value: 30000, label: '30s' }
]
let refreshTimer = null

// ============ 查询状态 ============
const isLoading = ref(false)
const total = ref(0)
const logs = ref([])
const expandedKey = ref(null)
const consoleRef = ref(null)

// 当前锁定的环境名
const lockedEnv = computed(() => props.currentEnvironment?.name || '')
const totalPages = computed(() => pageSize.value === -1 ? 1 : Math.max(1, Math.ceil(total.value / pageSize.value)))
const pageStart = computed(() => total.value === 0 ? 0 : (pageSize.value === -1 ? 1 : (currentPage.value - 1) * pageSize.value + 1))
const pageEnd = computed(() => pageSize.value === -1 ? total.value : Math.min(total.value, currentPage.value * pageSize.value))

// ============ Mock 移除：服务名读数据库 app_service 表 ============
const loadAvailableServices = async () => {
  const envId = props.currentEnvironment?.id
  if (!envId) { availableServices.value = []; return }
  try {
    const list = await request.get('/observability/logs/services', { params: { envId } })
    availableServices.value = Array.isArray(list) ? list : []
  } catch (e) {
    availableServices.value = []
  }
}

// ============ 查询（POST /observability/logs/search） ============
const buildQueryBody = () => ({
  envId: props.currentEnvironment?.id || null,
  envName: lockedEnv.value,
  services: selectedServices.value,
  levels: selectedLevels.value,
  keyword: keyword.value || null,
  traceId: traceId.value ? traceId.value.trim() : null,
  logger: logger.value ? logger.value.trim() : null,
  containerName: containerName.value ? containerName.value.trim() : null,
  thread: thread.value ? thread.value.trim() : null,
  timeRange: timeRange.value,
  from: timeRange.value === 'custom' ? (appliedFrom.value || customFrom.value || null) : null,
  to: timeRange.value === 'custom' ? (appliedTo.value || customTo.value || null) : null,
  page: currentPage.value,
  size: pageSize.value
})

const search = async () => {
  if (!lockedEnv.value) {
    logs.value = []; total.value = 0
    return
  }
  isLoading.value = true
  try {
    const data = await request.post('/observability/logs/search', buildQueryBody())
    const items = (data && data.items) || []
    // 为每行补上唯一 key（虚拟列表与展开依赖）
    logs.value = items.map((it, idx) => ({
      ...it,
      key: `${it.timestamp || ''}-${it.traceId || ''}-${idx}`,
      // 兼容原有模板：row.biz_message / row.container_name
      biz_message: it.bizMessage,
      container_name: it.containerName
    }))
    total.value = (data && data.total) || 0
  } catch (e) {
    logs.value = []; total.value = 0
  } finally {
    isLoading.value = false
  }
}

const onSearch = () => { currentPage.value = 1; search() }
const reset = () => {
  keyword.value = ''; traceId.value = ''
  logger.value = ''; containerName.value = ''
  thread.value = ''
  selectedServices.value = []
  selectedLevels.value = ['INFO', 'WARN', 'ERROR']
  timeRange.value = '1h'
  customFrom.value = ''; customTo.value = ''
  appliedFrom.value = ''; appliedTo.value = ''
  showCustomPicker.value = false
  currentPage.value = 1; search()
}

// ============ 时间范围 chip 交互 ============
const onTimeChipClick = (key) => {
  if (key === 'custom') {
    timeRange.value = 'custom'
    showCustomPicker.value = !showCustomPicker.value
  } else {
    timeRange.value = key
    showCustomPicker.value = false
    appliedFrom.value = ''
    appliedTo.value = ''
  }
}
const applyCustomTime = () => {
  appliedFrom.value = customFrom.value
  appliedTo.value = customTo.value
  showCustomPicker.value = false
  onSearch()
}
const clearCustomTime = () => {
  customFrom.value = ''; customTo.value = ''
  appliedFrom.value = ''; appliedTo.value = ''
}
const fmtCustom = (s) => (s ? s.replace('T', ' ') : '')
// 本地当前时间 -> YYYY-MM-DDTHH:mm时间字符串
const nowLocal = () => {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
const setNow = (field) => {
  if (field === 'from') customFrom.value = nowLocal()
  else customTo.value = nowLocal()
}
// ============ 点击浮层外关闭 ============
const onDocMouseDown = (e) => {
  if (!showCustomPicker.value) return
  const el = customPickerRef.value
  if (el && !el.contains(e.target)) {
    // 点击“自定义”chip 本身不关闭（交给 chip 的 toggle 处理）
    const chip = e.target.closest && e.target.closest('[data-time-key="custom"]')
    if (chip) return
    showCustomPicker.value = false
  }
}

// ============ 虚拟列表（大数据量下避免 DOM 过多） ============
const VLIST_THRESHOLD = 500
const TABLE_ROW_H = 38
const CONSOLE_ROW_H = 22
const resultBodyRef = ref(null)
const scrollTop = ref(0)
const viewportH = ref(600)
const useVirtual = computed(() => logs.value.length >= VLIST_THRESHOLD)
const rowH = computed(() => viewMode.value === 'console' ? CONSOLE_ROW_H : TABLE_ROW_H)
const vRange = computed(() => {
  const n = logs.value.length
  if (!useVirtual.value) return { start: 0, end: n }
  const buf = 20
  const start = Math.max(0, Math.floor(scrollTop.value / rowH.value) - buf)
  const end = Math.min(n, Math.ceil((scrollTop.value + viewportH.value) / rowH.value) + buf)
  return { start, end }
})
const visibleLogs = computed(() => useVirtual.value ? logs.value.slice(vRange.value.start, vRange.value.end) : logs.value)
const topPad = computed(() => useVirtual.value ? vRange.value.start * rowH.value : 0)
const bottomPad = computed(() => useVirtual.value ? Math.max(0, (logs.value.length - vRange.value.end)) * rowH.value : 0)
const onResultScroll = (e) => { scrollTop.value = e.target.scrollTop }
watch(logs, async () => {
  await nextTick()
  if (resultBodyRef.value) {
    if (viewMode.value === 'console') {
      resultBodyRef.value.scrollTop = resultBodyRef.value.scrollHeight
    } else {
      resultBodyRef.value.scrollTop = 0
    }
    scrollTop.value = resultBodyRef.value.scrollTop
    viewportH.value = resultBodyRef.value.clientHeight || 600
  }
})
watch(viewMode, async () => {
  await nextTick()
  if (resultBodyRef.value) {
    scrollTop.value = resultBodyRef.value.scrollTop
    viewportH.value = resultBodyRef.value.clientHeight || 600
  }
})

const toggleLevel = (lv) => {
  const idx = selectedLevels.value.indexOf(lv)
  if (idx >= 0) selectedLevels.value.splice(idx, 1); else selectedLevels.value.push(lv)
}
const toggleService = (s) => {
  const idx = selectedServices.value.indexOf(s)
  if (idx >= 0) selectedServices.value.splice(idx, 1); else selectedServices.value.push(s)
}
const pickTraceId = (tid) => { if (!tid) return; traceId.value = tid; onSearch() }
const toggleExpand = (k) => { expandedKey.value = expandedKey.value === k ? null : k }

const goPage = (p) => {
  if (p < 1 || p > totalPages.value || p === currentPage.value) return
  currentPage.value = p; search()
}
const onPageSizeChange = () => { currentPage.value = 1; search() }

// 自动刷新
const setupAutoRefresh = () => {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
  if (autoRefreshInterval.value > 0 && props.visible) {
    refreshTimer = setInterval(() => { if (!isLoading.value) search() }, autoRefreshInterval.value)
  }
}
watch(autoRefreshInterval, setupAutoRefresh)

const formatTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso); const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 下载走后端 POST + Blob（后端 export 接口为 POST）
const downloadLogs = async () => {
  if (!lockedEnv.value) return
  try {
    const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
    const token = getToken()
    const resp = await fetch(`${baseURL}/observability/logs/export`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify(buildQueryBody())
    })
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `logs-${lockedEnv.value}-${Date.now()}.log`
    document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    console.error('日志导出失败：', e)
  }
}

const handleClose = () => emit('update:visible', false)

watch(() => props.visible, (v) => {
  if (v) {
    // 打开时先清空上次的数据，避免旧结果一闪
    logs.value = []
    total.value = 0
    expandedKey.value = null
    scrollTop.value = 0
    document.body.style.overflow = 'hidden'
    currentPage.value = 1
    loadAvailableServices()
    search()
    setupAutoRefresh()
  } else {
    document.body.style.overflow = ''
    if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
    // 关闭时释放数据，避免常驻内存占用
    logs.value = []
    total.value = 0
    expandedKey.value = null
    isLoading.value = false
    scrollTop.value = 0
  }
})
watch(() => props.currentEnvironment?.id, () => {
  if (props.visible) {
    currentPage.value = 1
    loadAvailableServices()
    search()
  }
})

onMounted(() => { document.addEventListener('mousedown', onDocMouseDown) })
onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  document.removeEventListener('mousedown', onDocMouseDown)
})

const levelClass = (lv) => ({
  'lvl-DEBUG': lv === 'DEBUG', 'lvl-INFO': lv === 'INFO',
  'lvl-WARN':  lv === 'WARN',  'lvl-ERROR': lv === 'ERROR'
})
const shortTrace = (t) => (t ? t.slice(0, 8) + '…' : '-')
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay" @click="handleClose">
        <div class="dialog-container" @click.stop>
          <!-- Header -->
          <div class="dialog-header">
            <div class="header-content">
              <div class="header-icon">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="8" y1="13" x2="16" y2="13"/>
                  <line x1="8" y1="17" x2="16" y2="17"/>
                </svg>
              </div>
              <div>
                <h3>日志查询</h3>
                <p class="header-sub">
                  基于 OpenSearch 的全局日志检索
                  <span v-if="lockedEnv" class="env-badge">环境：{{ lockedEnv }}</span>
                  <span v-else class="env-badge env-badge-warn">未选择环境</span>
                </p>
              </div>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <!-- Filter Bar（默认一行紧凑，高级可展开） -->
          <div class="filter-bar" :class="{ 'is-expanded': filterExpanded }">
            <!-- 主筛选行 -->
            <div class="filter-row main-row">
              <div class="filter-item grow">
                <label>关键字</label>
                <input v-model="keyword" type="text" placeholder="搜索 message / biz_message..." @keyup.enter="onSearch"/>
              </div>
              <div class="filter-item">
                <label>级别</label>
                <div class="level-chips">
                  <button v-for="lv in allLevels" :key="lv"
                    class="chip" :class="[{ active: selectedLevels.includes(lv) }, levelClass(lv)]"
                    @click="toggleLevel(lv)">{{ lv }}</button>
                </div>
              </div>
              <div class="filter-item">
                <label>
                  时间范围
                  <span v-if="timeRange === 'custom' && (appliedFrom || appliedTo)" class="time-summary">
                    ({{ fmtCustom(appliedFrom) || '...' }} ~ {{ fmtCustom(appliedTo) || '...' }})
                  </span>
                </label>
                <div class="time-chip-wrap">
                  <div class="time-chips">
                    <button v-for="t in timePresets" :key="t.key"
                      class="chip" :class="{ active: timeRange === t.key }"
                      :data-time-key="t.key"
                      @click="onTimeChipClick(t.key)">{{ t.label }}</button>
                  </div>
                  <div v-if="showCustomPicker" class="custom-picker" ref="customPickerRef">
                    <div class="cp-row">
                      <span class="cp-label">开始</span>
                      <input v-model="customFrom" type="datetime-local"/>
                      <button type="button" class="cp-now" @click="setNow('from')" title="填入当前时间">此刻</button>
                    </div>
                    <div class="cp-row">
                      <span class="cp-label">结束</span>
                      <input v-model="customTo" type="datetime-local"/>
                      <button type="button" class="cp-now" @click="setNow('to')" title="填入当前时间">此刻</button>
                    </div>
                    <div class="cp-actions">
                      <button class="btn btn-secondary" @click="clearCustomTime">清空</button>
                      <button class="btn btn-primary" :disabled="!customFrom || !customTo" @click="applyCustomTime">应用</button>
                    </div>
                  </div>
                </div>
              </div>
              <div class="filter-item">
                <label>自动刷新</label>
                <select v-model.number="autoRefreshInterval" class="mini-select field-select">
                  <option v-for="o in autoRefreshOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
              </div>
              <div class="filter-actions">
                <button class="toolbar-btn" @click="downloadLogs" title="按当前筛选条件下载全部命中">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                  下载全部
                </button>
                <span class="action-divider"></span>
                <button class="btn btn-primary" :disabled="isLoading" @click="onSearch">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
                  </svg>
                  查询
                </button>
                <button class="btn btn-secondary" @click="reset">重置</button>
                <button class="btn btn-ghost" @click="filterExpanded = !filterExpanded"
                  :title="filterExpanded ? '收起高级筛选' : '展开高级筛选（traceId / 服务）'">
                  {{ filterExpanded ? '收起' : '高级' }}
                  <svg class="caret" :class="{ rotated: filterExpanded }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </button>
              </div>
            </div>

            <!-- 高级筛选区 -->
            <div v-show="filterExpanded" class="adv-rows">
              <div class="filter-row adv-inputs">
                <div class="filter-item">
                  <label>traceId</label>
                  <input v-model="traceId" type="text" placeholder="精确匹配链路ID" @keyup.enter="onSearch"/>
                </div>
                <div class="filter-item">
                  <label>logger（前缀）</label>
                  <input v-model="logger" type="text" placeholder="如 c.b.f.mapper" @keyup.enter="onSearch"/>
                </div>
                <div class="filter-item">
                  <label>container_name（包含）</label>
                  <input v-model="containerName" type="text" placeholder="如 ubp-service.1" @keyup.enter="onSearch"/>
                </div>
                <div class="filter-item">
                  <label>thread（前缀）</label>
                  <input v-model="thread" type="text" placeholder="如 http-nio / scheduling" @keyup.enter="onSearch"/>
                </div>
              </div>
              <div class="filter-row">
                <div class="filter-item grow">
                  <label>服务</label>
                  <div class="service-chips">
                    <button v-for="s in availableServices" :key="s"
                      class="chip" :class="{ active: selectedServices.includes(s) }"
                      @click="toggleService(s)">{{ s }}</button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Result Body -->
          <div class="result-body" :class="{ 'is-console': viewMode === 'console' }"
            ref="resultBodyRef" @scroll="onResultScroll">
            <div v-if="isLoading && !logs.length" class="state-block">
              <div class="spinner"></div><p>查询中...</p>
            </div>
            <div v-else-if="!logs.length" class="state-block"><p>无匹配的日志</p></div>

            <!-- 表格视图 -->
            <table v-else-if="viewMode === 'table'" class="logs-table">
              <thead>
                <tr>
                  <th class="col-time">时间</th>
                  <th class="col-svc">服务</th>
                  <th class="col-lv">级别</th>
                  <th>消息</th>
                  <th class="col-trace">traceId</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="useVirtual && topPad > 0" class="vspacer"><td colspan="5" :style="{ height: topPad + 'px' }"></td></tr>
                <template v-for="row in visibleLogs" :key="row.key">
                  <tr class="log-row" :class="{ expanded: !useVirtual && expandedKey === row.key }" @click="!useVirtual && toggleExpand(row.key)">
                    <td class="col-time mono">{{ formatTime(row.timestamp) }}</td>
                    <td class="col-svc">{{ row.service }}</td>
                    <td class="col-lv"><span class="lv-tag" :class="levelClass(row.level)">{{ row.level }}</span></td>
                    <td class="col-msg">{{ row.biz_message || row.message }}</td>
                    <td class="col-trace mono">
                      <a v-if="row.traceId" href="#" @click.stop.prevent="pickTraceId(row.traceId)">{{ shortTrace(row.traceId) }}</a>
                      <span v-else class="muted">-</span>
                    </td>
                  </tr>
                  <tr v-if="!useVirtual && expandedKey === row.key" class="log-detail">
                    <td colspan="5">
                      <div class="detail-grid">
                        <div><span class="k">logger</span><span class="v mono">{{ row.logger }}</span></div>
                        <div><span class="k">container_name</span><span class="v mono">{{ row.container_name }}</span></div>
                        <div><span class="k">image_name</span><span class="v mono">{{ row.image_name }}</span></div>
                        <div><span class="k">source_host</span><span class="v mono">{{ row.source_host }}</span></div>
                        <div><span class="k">thread</span><span class="v mono">{{ row.thread }}</span></div>
                        <div><span class="k">traceId</span><span class="v mono">{{ row.traceId || '-' }}</span></div>
                      </div>
                      <pre class="detail-message">{{ row.message }}</pre>
                    </td>
                  </tr>
                </template>
                <tr v-if="useVirtual && bottomPad > 0" class="vspacer"><td colspan="5" :style="{ height: bottomPad + 'px' }"></td></tr>
              </tbody>
            </table>

            <!-- 控制台视图（风格对齐 LogViewerDialog，仅按级别染色） -->
            <div v-else class="console-view" ref="consoleRef">
              <div v-if="useVirtual && topPad > 0" :style="{ height: topPad + 'px' }"></div>
              <div v-for="row in visibleLogs" :key="row.key" class="console-line"><!--
             --><span class="c-time">{{ formatTime(row.timestamp) }}</span> <span class="c-lv" :class="levelClass(row.level)">{{ row.level.padEnd(5, ' ') }}</span> <span class="c-svc">{{ row.service }}</span> <span class="c-thread">[{{ row.thread }}]</span> <span class="c-logger">{{ row.logger }}</span> <span class="c-trace"><a v-if="row.traceId" href="#" @click.stop.prevent="pickTraceId(row.traceId)">({{ shortTrace(row.traceId) }})</a><span v-else>(-)</span></span> <span class="c-msg">{{ row.biz_message || row.message }}</span>
              </div>
              <div v-if="useVirtual && bottomPad > 0" :style="{ height: bottomPad + 'px' }"></div>
            </div>
          </div>

          <!-- 底部分页栏 -->
          <div class="pagination">
            <span class="pg-info">第 {{ pageStart }} - {{ pageEnd }} 条 / 共 <b>{{ total }}</b> 条</span>

            <div class="grow"></div>

            <div class="inline-field">
              <span class="inline-label">每页</span>
              <select v-model.number="pageSize" class="mini-select" @change="onPageSizeChange">
                <option v-for="n in pageSizeOptions" :key="n" :value="n">{{ n === -1 ? '全部' : n }}</option>
              </select>
            </div>
            <div class="pg-buttons">
              <button class="pg-btn" :disabled="currentPage <= 1" @click="goPage(1)">首页</button>
              <button class="pg-btn" :disabled="currentPage <= 1" @click="goPage(currentPage - 1)">上一页</button>
              <span class="pg-current">{{ currentPage }} / {{ totalPages }}</span>
              <button class="pg-btn" :disabled="currentPage >= totalPages" @click="goPage(currentPage + 1)">下一页</button>
              <button class="pg-btn" :disabled="currentPage >= totalPages" @click="goPage(totalPages)">末页</button>
            </div>
            <div class="seg-group" title="视图模式">
              <button class="seg" :class="{ active: viewMode === 'table' }" @click="viewMode = 'table'">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="3" y1="15" x2="21" y2="15"/><line x1="9" y1="3" x2="9" y2="21"/></svg>

              </button>
              <button class="seg" :class="{ active: viewMode === 'console' }" @click="viewMode = 'console'">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>

              </button>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dialog-overlay {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.5); backdrop-filter: blur(4px);
  display: flex; z-index: 10000;
}
.dialog-container {
  background: var(--bg-secondary);
  width: 100vw; height: 100vh;
  display: flex; flex-direction: column; overflow: hidden;
}
.dialog-header {
  padding: 1.25rem 2rem;
  background: var(--primary-gradient);
  display: flex; align-items: center; justify-content: space-between;
  flex-shrink: 0; color: white;
}
.header-content { display: flex; align-items: center; gap: 1rem; }
.header-icon {
  width: 40px; height: 40px; border-radius: 10px;
  background: rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center;
}
.dialog-header h3 { margin: 0; font-size: 1.2rem; font-weight: 600; }
.header-sub { margin: .15rem 0 0; font-size: .8rem; opacity: .9; display: flex; align-items: center; gap: .5rem; }
.env-badge {
  padding: .12rem .55rem; border-radius: 999px;
  background: rgba(255,255,255,0.22); font-size: .72rem; font-weight: 600;
}
.env-badge-warn { background: rgba(255,196,0,0.35); }
.btn-close {
  width: 36px; height: 36px; border-radius: 8px;
  background: rgba(255,255,255,0.2); border: none; color: white;
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.btn-close:hover { background: rgba(255,255,255,0.3); }

/* Filter Bar */
.filter-bar {
  padding: 1rem 2rem; background: var(--bg-primary);
  border-bottom: 1px solid var(--border-color);
  display: flex; flex-direction: column; gap: .75rem; flex-shrink: 0;
}
.filter-row { display: flex; gap: 1rem; align-items: flex-end; flex-wrap: wrap; }
.filter-item { display: flex; flex-direction: column; gap: .35rem; min-width: 180px; }
.filter-item.grow { flex: 1; }
.filter-item label { font-size: .75rem; color: var(--text-secondary); font-weight: 600; }
.filter-item input[type=text], .filter-item input[type=datetime-local] {
  height: 34px; padding: 0 .75rem; border: 1px solid var(--border-color);
  border-radius: 8px; background: var(--bg-secondary); color: var(--text-primary); font-size: .85rem;
}
.filter-item input:focus { border-color: var(--primary-color); outline: none; }
.custom-time { display: flex; align-items: center; gap: .5rem; }

/* 时间范围 chip + 浮层 */
.time-chip-wrap { position: relative; }
.time-summary {
  margin-left: .35rem; color: var(--text-tertiary); font-weight: 500;
  font-size: .72rem; letter-spacing: 0;
}
.custom-picker {
  position: absolute; top: calc(100% + .35rem); left: 0; z-index: 50;
  min-width: 320px; padding: .85rem;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 10px; box-shadow: 0 8px 24px rgba(0,0,0,.12);
  display: flex; flex-direction: column; gap: .55rem;
}
.custom-picker .cp-row { display: flex; align-items: center; gap: .5rem; }
.custom-picker .cp-label {
  width: 36px; font-size: .8rem; color: var(--text-secondary); font-weight: 600;
}
.custom-picker .cp-row input[type=datetime-local] {
  flex: 1; height: 32px; padding: 0 .55rem;
  border: 1px solid var(--border-color); border-radius: 6px;
  background: var(--bg-primary); color: var(--text-primary); font-size: .82rem;
}
.custom-picker .cp-row input:focus { border-color: var(--primary-color); outline: none; }
.custom-picker .cp-now {
  height: 32px; padding: 0 .65rem; border-radius: 6px;
  border: 1px solid var(--border-color); background: var(--bg-primary); color: var(--text-secondary);
  font-size: .78rem; cursor: pointer; white-space: nowrap;
}
.custom-picker .cp-now:hover { border-color: var(--primary-color); color: var(--primary-color); }
.custom-picker .cp-actions {
  display: flex; justify-content: flex-end; gap: .5rem; margin-top: .25rem;
}
.custom-picker .cp-actions .btn { height: 30px; padding: 0 .85rem; font-size: .8rem; }

.service-chips, .level-chips, .time-chips { display: flex; flex-wrap: wrap; gap: .35rem; }
.chip {
  padding: .3rem .7rem; border: 1px solid var(--border-color); border-radius: 999px;
  background: var(--bg-secondary); color: var(--text-secondary); font-size: .8rem;
  cursor: pointer; transition: all .15s; line-height: 1;
  white-space: nowrap;
}
.chip:hover { border-color: var(--primary-color); color: var(--primary-color); }
.chip.active { border-color: var(--primary-color); background: var(--primary-color); color: white; }
.chip.lvl-ERROR.active { background: #ef4444; border-color: #ef4444; }
.chip.lvl-WARN.active  { background: #f59e0b; border-color: #f59e0b; }
.chip.lvl-INFO.active  { background: #10b981; border-color: #10b981; }
.chip.lvl-DEBUG.active { background: #6b7280; border-color: #6b7280; }

/* chip 高度对齐输入框，保持表单控件水平整齐 */
.chip {
  height: 34px;
  padding: 0 .85rem;
  border-radius: 8px;
  font-size: .82rem;
  display: inline-flex;
  align-items: center;
}
.level-chips,
.time-chips,
.service-chips { gap: .4rem; }

.filter-actions { display: flex; gap: .5rem; align-items: center; margin-left: auto; flex-wrap: wrap; }
.action-divider { width: 1px; height: 22px; background: var(--border-color); margin: 0 .15rem; }
.btn {
  height: 34px; padding: 0 1rem; border-radius: 8px;
  font-size: .85rem; font-weight: 500; border: none; cursor: pointer;
  display: inline-flex; align-items: center; gap: .35rem; transition: all .15s;
}
.btn-primary { background: var(--primary-gradient); color: white; }
.btn-primary:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 12px rgba(102,126,234,0.3); }
.btn-primary:disabled { opacity: .6; cursor: not-allowed; }
.btn-secondary { background: var(--bg-secondary); border: 1px solid var(--border-color); color: var(--text-primary); }
.btn-secondary:hover { border-color: var(--primary-color); color: var(--primary-color); }
.btn-ghost { background: transparent; border: 1px dashed var(--border-color); color: var(--text-secondary); }
.btn-ghost:hover { border-color: var(--primary-color); color: var(--primary-color); border-style: solid; }
.btn-ghost .caret { transition: transform .2s; }
.btn-ghost .caret.rotated { transform: rotate(180deg); }

.adv-rows { display: flex; flex-direction: column; gap: .75rem; padding-top: .25rem; border-top: 1px dashed var(--border-color); }
.adv-rows .adv-inputs { gap: .75rem; flex-wrap: nowrap; }
.adv-rows .adv-inputs .filter-item { flex: 1 1 0; min-width: 0; }
@media (max-width: 1100px) {
  .adv-rows .adv-inputs { flex-wrap: wrap; }
  .adv-rows .adv-inputs .filter-item { flex: 1 1 calc(50% - .375rem); }
}

/* Result toolbar */
.result-toolbar {
  display: flex; align-items: center; gap: .75rem;
  padding: .6rem 2rem; border-bottom: 1px solid var(--border-color);
  background: var(--bg-primary); flex-shrink: 0;
}
.result-count { font-size: .85rem; color: var(--text-secondary); }
.result-count b { color: var(--primary-color); font-size: 1rem; }
.grow { flex: 1; }

.seg-group { display: inline-flex; border: 1px solid var(--border-color); border-radius: 8px; overflow: hidden; height: 34px; }
.seg {
  height: 34px; padding: 0 .8rem; background: var(--bg-secondary); color: var(--text-secondary);
  font-size: .82rem; border: none; cursor: pointer; display: inline-flex; align-items: center; gap: .35rem;
}
.seg + .seg { border-left: 1px solid var(--border-color); }
.seg:hover { color: var(--primary-color); }
.seg.active { background: var(--primary-color); color: white; }

.inline-field { display: inline-flex; align-items: center; gap: .35rem; }
.inline-label { font-size: .82rem; color: var(--text-secondary); }
.mini-select {
  height: 34px; padding: 0 .55rem; border: 1px solid var(--border-color); border-radius: 8px;
  background: var(--bg-secondary); color: var(--text-primary); font-size: .82rem;
}

.toolbar-btn {
  height: 34px; padding: 0 .85rem; border-radius: 8px;
  border: 1px solid var(--border-color); background: var(--bg-secondary); color: var(--text-primary);
  font-size: .82rem; cursor: pointer; display: inline-flex; align-items: center; gap: .35rem;
}
.toolbar-btn:hover:not(:disabled) { border-color: var(--primary-color); color: var(--primary-color); }
.toolbar-btn:disabled { opacity: .5; cursor: not-allowed; }

/* Result body */
.result-body { flex: 1; overflow: auto; padding: 0 2rem 1rem; background: var(--bg-secondary); border-top: 1px solid var(--border-color); }
.result-body.is-console { padding: 0; background: #1e1e1e; }
.state-block {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 4rem; color: var(--text-secondary);
}
.spinner {
  width: 36px; height: 36px;
  border: 3px solid var(--border-color); border-top-color: var(--primary-color);
  border-radius: 50%; animation: spin .8s linear infinite; margin-bottom: 1rem;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Table */
.logs-table { width: 100%; border-collapse: collapse; margin-top: 1rem; font-size: .85rem; }
.logs-table thead th {
  position: sticky; top: 0; background: var(--bg-primary); text-align: left;
  font-weight: 600; color: var(--text-secondary); padding: .65rem .75rem;
  border-bottom: 2px solid var(--border-color);
  font-size: .78rem; text-transform: uppercase; letter-spacing: .05em; z-index: 1;
}
.logs-table tbody td { padding: .55rem .75rem; border-bottom: 1px solid var(--border-color); vertical-align: top; }
.col-time { width: 168px; white-space: nowrap; color: var(--text-secondary); }
.col-svc  { width: 180px; color: var(--text-primary); font-weight: 500; }
.col-lv   { width: 80px; }
.col-trace{ width: 100px; }
.col-msg  { color: var(--text-primary); word-break: break-all; }
.mono { font-family: 'Consolas', 'Monaco', monospace; font-size: .8rem; }
.muted { color: var(--text-tertiary); }

.log-row { cursor: pointer; transition: background .15s; }
.log-row:hover { background: var(--bg-hover); }
.log-row.expanded { background: var(--primary-light); }

.lv-tag { display: inline-block; padding: .15rem .5rem; border-radius: 4px; font-size: .72rem; font-weight: 700; line-height: 1; }
.lv-tag.lvl-ERROR { background: #fee2e2; color: #b91c1c; }
.lv-tag.lvl-WARN  { background: #fef3c7; color: #b45309; }
.lv-tag.lvl-INFO  { background: #d1fae5; color: #047857; }
.lv-tag.lvl-DEBUG { background: #e5e7eb; color: #4b5563; }

.col-trace a { color: var(--primary-color); text-decoration: none; }
.col-trace a:hover { text-decoration: underline; }

.log-detail td { background: var(--bg-primary); padding: 1rem 1.25rem !important; }
/* 虚拟列表占位行：不参与边框/内边距，仅提供高度 */
.logs-table .vspacer td { padding: 0 !important; border: none !important; background: transparent; }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: .5rem .75rem; margin-bottom: .75rem; }
.detail-grid > div { display: flex; gap: .5rem; }
.detail-grid .k { color: var(--text-tertiary); font-size: .75rem; min-width: 110px; }
.detail-grid .v { color: var(--text-primary); font-size: .8rem; word-break: break-all; }
.detail-message {
  margin: 0; padding: .75rem; background: #1e1e1e; color: #d4d4d4;
  border-radius: 6px; font-family: 'Consolas', 'Monaco', monospace;
  font-size: .78rem; line-height: 1.55; white-space: pre-wrap; word-break: break-all;
}

/* Console（对齐 LogViewerDialog 风格：朴素 pre 文本流 + 按级别染色） */
.console-view {
  padding: 1.5rem; background: #1e1e1e; color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 0.875rem; line-height: 1.6;
}
/* 控制台模式下由外层 result-body 提供滚动与自定义滚动条 */
.result-body.is-console::-webkit-scrollbar { width: 8px; }
.result-body.is-console::-webkit-scrollbar-track { background: #2d2d2d; }
.result-body.is-console::-webkit-scrollbar-thumb { background: #555; border-radius: 4px; }
.result-body.is-console::-webkit-scrollbar-thumb:hover { background: #666; }
.console-line {
  display: block;
  white-space: pre-wrap; word-break: break-all;
}
.console-line:hover { background: rgba(255,255,255,0.04); }
.c-time   { color: #858585; }
.c-lv     { font-weight: 700; white-space: pre; }
.c-lv.lvl-ERROR { color: #f48771; }
.c-lv.lvl-WARN  { color: #ffcc66; }
.c-lv.lvl-INFO  { color: #89d185; }
.c-lv.lvl-DEBUG { color: #888; }
.c-svc    { color: #569cd6; }
.c-thread { color: #c586c0; }
.c-logger { color: #9cdcfe; }
.c-trace  { color: #b5cea8; }
.c-trace a { color: #b5cea8; text-decoration: none; }
.c-trace a:hover { text-decoration: underline; }
.c-msg    { color: #d4d4d4; }

/* 底部合并栏：原 toolbar + 分页 */
.pagination {
  display: flex; align-items: center; gap: .55rem;
  padding: .55rem 2rem; border-top: 1px solid var(--border-color);
  background: var(--bg-primary); flex-shrink: 0; font-size: .82rem;
  flex-wrap: wrap;
}
.pg-info { color: var(--text-secondary); }
.pg-info b { color: var(--primary-color); font-size: 1rem; }
.pg-buttons { display: inline-flex; align-items: center; gap: .35rem; }
.pg-btn {
  height: 28px; padding: 0 .7rem; border-radius: 6px;
  border: 1px solid var(--border-color); background: var(--bg-secondary); color: var(--text-primary);
  font-size: .78rem; cursor: pointer;
}
.pg-btn:hover:not(:disabled) { border-color: var(--primary-color); color: var(--primary-color); }
.pg-btn:disabled { opacity: .5; cursor: not-allowed; }
.pg-current { padding: 0 .35rem; color: var(--text-secondary); font-weight: 600; }

/* 底部栏内控件统一风格：28px 高 / 6px 圆角 / .78rem 字号 */
.pagination .mini-select {
  height: 28px; padding: 0 .5rem; border-radius: 6px;
  font-size: .78rem;
}
.pagination .inline-label { font-size: .78rem; }
.pagination .seg-group { height: 28px; border-radius: 6px; }
.pagination .seg {
  height: 28px; padding: 0 .65rem; font-size: .78rem; gap: .3rem;
}
.pagination .seg svg { width: 12px; height: 12px; }

.dialog-fade-enter-active, .dialog-fade-leave-active { transition: opacity .25s; }
.dialog-fade-enter-from, .dialog-fade-leave-to { opacity: 0; }
</style>
