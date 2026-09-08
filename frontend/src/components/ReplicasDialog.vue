<script setup>
import { ref, computed, watch, onUnmounted, defineAsyncComponent } from 'vue'
import { getServiceReplicas } from '@/api/service'
import { enrichWithMockMetrics } from '@/api/monitor'
import LogViewerDialog from './LogViewerDialog.vue'
import SparkLine from './monitor/SparkLine.vue'
import { useBodyScrollLock } from '@/composables/useBodyScrollLock'

// 终端组件按需加载（xterm 体积大，拆到独立 chunk）
const TerminalDialog = defineAsyncComponent(() => import('./TerminalDialog.vue'))

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  service: {
    type: Object,
    required: true
  },
  readOnly: {
    type: Boolean,
    default: false
  }
})

useBodyScrollLock(() => props.visible)

const emit = defineEmits(['update:visible'])

// 副本列表数据
const replicas = ref([])

// 副本级 sparkline 时间窗（每个副本独立）：{ [replicaId]: '5m' | '30m' | '2h' }
const rangeMap = ref({})
const RANGE_OPTIONS = [
  { value: '5m',  label: '5m' },
  { value: '30m', label: '30m' },
  { value: '2h',  label: '2h' }
]
const getRange = (replica) => rangeMap.value[replica.id] || '5m'
const setRange = (replica, range) => {
  if (!replica?.id || rangeMap.value[replica.id] === range) return
  rangeMap.value = { ...rangeMap.value, [replica.id]: range }
  // 切换后立即拉一次，避免等到下一个定时周期
  loadReplicas()
}

// 日志查看对话框
const showLogViewer = ref(false)
const selectedReplica = ref(null)

// Web 终端对话框（进入容器）
const showTerminal = ref(false)
const terminalReplica = ref(null)

watch(() => props.readOnly, (readOnly) => {
  if (!readOnly) return
  showTerminal.value = false
  terminalReplica.value = null
})

// Docker 运行参数展开状态（默认收起）
const showDockerParams = ref(false)

// 内置 key 名单（与 ServiceDialog 保持一致，剩余 key 视为环境变量）
const BUILT_IN_KEYS = new Set([
  'replicas', 'cpus', 'memory', 'memory-reservation', 'cpu-reservation',
  'restart', 'restart-max-attempts', 'restart-delay',
  'publish', 'network', 'endpoint-mode',
  'healthcheck', 'healthcheck_interval', 'healthcheck_timeout',
  'healthcheck_retries', 'healthcheck_start_period',
  'update_parallelism', 'update_delay', 'update_monitor',
  'update_failure_action', 'update_order',
  'rollback_parallelism', 'rollback_delay', 'rollback_monitor',
  'rollback_failure_action', 'rollback_order',
  'container-label', 'container-labels', 'mounts',
  'log-driver', 'log-opts', 'command', 'constraints',
  'stop-grace-period', 'replicas-max-per-node'
])

// 多值字段拆分（换行或逗号分隔）
const splitMulti = (v) => {
  if (v == null || v === '') return []
  if (Array.isArray(v)) return v.map(x => String(x)).filter(x => x.trim())
  const str = String(v)
  const parts = str.includes('\n') ? str.split(/\r?\n/) : str.split(',')
  return parts.map(x => x.trim()).filter(Boolean)
}

/**
 * dockerParams (JSON) → 分类分组（与编辑弹窗同一套分类）
 * 返回 null 表示解析失败（模板降级为原文展示）
 */
const dockerParamGroups = computed(() => {
  let map = {}
  try {
    map = props.service?.dockerParams ? JSON.parse(props.service.dockerParams) : {}
  } catch (_) {
    return null
  }
  const groups = []
  const push = (title, items) => {
    const filtered = items.filter(it =>
      Array.isArray(it.value) ? it.value.length > 0 : (it.value != null && it.value !== '')
    )
    if (filtered.length) groups.push({ title, items: filtered })
  }

  // 环境变量 = 剩余非内置 key（空值也展示）
  const envItems = Object.entries(map)
    .filter(([k]) => !BUILT_IN_KEYS.has(k))
    .map(([k, v]) => ({ label: k, value: v == null ? '' : String(v) }))
  if (envItems.length) groups.push({ title: '环境变量', items: envItems })

  push('网络与端口', [
    { label: 'network', value: map.network },
    { label: 'endpoint-mode', value: map['endpoint-mode'] },
    { label: 'publish', value: splitMulti(map.publish) }
  ])
  push('挂载卷', [
    { label: 'mounts', value: splitMulti(map.mounts) }
  ])
  push('节点约束', [
    { label: 'constraints', value: splitMulti(map.constraints) }
  ])
  push('资源限制', [
    { label: 'limit-cpu', value: map.cpus },
    { label: 'limit-memory', value: map.memory },
    { label: 'reserve-memory', value: map['memory-reservation'] },
    { label: 'reserve-cpu', value: map['cpu-reservation'] },
    { label: 'replicas-max-per-node', value: map['replicas-max-per-node'] }
  ])
  push('重启策略', [
    { label: 'restart', value: map.restart },
    { label: 'max-attempts', value: map['restart-max-attempts'] },
    { label: 'delay', value: map['restart-delay'] },
    { label: 'stop-grace-period', value: map['stop-grace-period'] }
  ])
  push('健康检查', [
    { label: 'cmd', value: map.healthcheck },
    { label: 'interval', value: map.healthcheck_interval },
    { label: 'timeout', value: map.healthcheck_timeout },
    { label: 'retries', value: map.healthcheck_retries },
    { label: 'start_period', value: map.healthcheck_start_period }
  ])
  push('滚动更新', [
    { label: 'parallelism', value: map.update_parallelism },
    { label: 'delay', value: map.update_delay },
    { label: 'monitor', value: map.update_monitor },
    { label: 'failure_action', value: map.update_failure_action },
    { label: 'order', value: map.update_order }
  ])
  push('回滚策略', [
    { label: 'parallelism', value: map.rollback_parallelism },
    { label: 'delay', value: map.rollback_delay },
    { label: 'monitor', value: map.rollback_monitor },
    { label: 'failure_action', value: map.rollback_failure_action },
    { label: 'order', value: map.rollback_order }
  ])
  // log-opts 可能是 JSON 字符串，展开为 key=value 行
  let logOptLines = []
  if (map['log-opts']) {
    try {
      const obj = typeof map['log-opts'] === 'string' ? JSON.parse(map['log-opts']) : map['log-opts']
      logOptLines = Object.entries(obj).map(([k, v]) => `${k}=${v}`)
    } catch (_) {
      logOptLines = splitMulti(map['log-opts'])
    }
  }
  push('日志与标签', [
    { label: 'log-driver', value: map['log-driver'] },
    { label: 'log-opts', value: logOptLines },
    { label: 'labels', value: splitMulti(map['container-labels'] ?? map['container-label']) }
  ])
  push('启动命令', [
    { label: 'command', value: map.command }
  ])
  return groups
})

// 当前选中的参数分类（tab 形式，默认第一个有值的分组）
const activeParamGroup = ref('')
const activeGroupTitle = computed(() => {
  const groups = dockerParamGroups.value
  if (!groups || !groups.length) return ''
  return groups.some(g => g.title === activeParamGroup.value) ? activeParamGroup.value : groups[0].title
})
const currentGroup = computed(() =>
  (dockerParamGroups.value || []).find(g => g.title === activeGroupTitle.value) || null
)

// 定时刷新相关
let refreshTimer = null
const REFRESH_INTERVAL = 10000 // 10秒刷新一次

// 监听对话框显示状态
watch(() => props.visible, (val) => {
  if (val) {
    showDockerParams.value = false
    activeParamGroup.value = ''
    loadReplicas()
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
})

// 加载副本列表数据
const loadReplicas = async () => {
  if (!props.service?.id) return
  
  try {
    const data = await getServiceReplicas(props.service.id)
    // 过滤掉已停止的副本，只显示活跃的副本
    const active = (data || []).filter(r => {
      const status = r.status?.toLowerCase()
      return status !== 'shutdown' && status !== 'complete' && status !== 'remove'
    })
    // 后端指标接入后，enrichWithMockMetrics 已切换为异步拉取真实 summary（按 rangeMap 逐个选时间窗）
    replicas.value = await enrichWithMockMetrics(active, rangeMap.value)
  } catch (error) {
    console.error('加载副本列表失败:', error)
    // 如果API调用失败，使用模拟数据作为降级方案
    loadMockReplicas()
  }
}

// 模拟数据（降级方案）
const loadMockReplicas = () => {
  const instances = props.service.instances || 3
  const mocks = Array.from({ length: instances }, (_, i) => ({
    id: `${props.service.name}.${i + 1}`,
    name: `${props.service.name}.${i + 1}`,
    status: i === 0 ? 'running' : (i === instances - 1 ? 'starting' : 'running'),
    node: `node-${(i % 3) + 1}`,
    uptime: i === instances - 1 ? '刚刚' : `${Math.floor(Math.random() * 24) + 1}小时`
  }))
  Promise.resolve(enrichWithMockMetrics(mocks, rangeMap.value)).then(list => { replicas.value = list })
}

// 启动定时刷新
const startAutoRefresh = () => {
  stopAutoRefresh() // 先清除可能存在的旧定时器
  refreshTimer = setInterval(() => {
    loadReplicas()
  }, REFRESH_INTERVAL)
}

// 停止定时刷新
const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

const handleClose = () => {
  emit('update:visible', false)
}

const getStatusClass = (status) => {
  const statusMap = {
    running: 'status-success',
    starting: 'status-warning',
    preparing: 'status-warning',
    ready: 'status-warning',
    assigned: 'status-warning',
    accepted: 'status-warning',
    stopped: 'status-neutral',
    shutdown: 'status-neutral',
    complete: 'status-neutral',
    remove: 'status-neutral',
    failed: 'status-danger',
    rejected: 'status-danger',
    error: 'status-danger'
  }
  return statusMap[status] || 'status-neutral'
}

const getStatusText = (status) => {
  const statusMap = {
    running: '运行中',
    starting: '启动中',
    preparing: '准备中',
    ready: '就绪',
    assigned: '已分配',
    accepted: '已接受',
    stopped: '已停止',
    shutdown: '关闭中',
    complete: '已完成',
    remove: '移除中',
    failed: '失败',
    rejected: '已拒绝',
    error: '异常'
  }
  return statusMap[status] || status
}

// 查看服务日志
const handleViewServiceLogs = () => {
  console.log('查看服务日志:', props.service)
  selectedReplica.value = { name: props.service.name } // 用于日志对话框标题
  showLogViewer.value = true
}

// 查看指定副本日志
const handleViewReplicaLogs = (replica) => {
  selectedReplica.value = replica
  showLogViewer.value = true
}

// 进入容器：打开 Web 终端（需要副本在运行中且已采到容器 ID）
const canEnterContainer = (replica) =>
  !props.readOnly && replica.status === 'running' && !!replica.containerId

const handleEnterContainer = (replica) => {
  if (!canEnterContainer(replica)) return
  terminalReplica.value = replica
  showTerminal.value = true
}

// 字节格式化：<1024 => B，后面 KB/MB/GB，保留1 位小数
const formatBytes = (bytes) => {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) return '-'
  const units = ['B', 'K', 'M', 'G', 'T']
  let idx = 0
  let v = n
  while (v >= 1024 && idx < units.length - 1) { v /= 1024; idx++ }
  return `${v >= 100 ? v.toFixed(0) : v.toFixed(1)}${units[idx]}`
}

const memHint = (replica) => {
  if (replica.memoryUsage == null) return ''
  const used = formatBytes(replica.memoryUsage)
  const limit = replica.memoryLimit != null ? formatBytes(replica.memoryLimit) : null
  return limit && limit !== '-' ? `${used} / ${limit}` : used
}

onUnmounted(() => {
  stopAutoRefresh()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay">
        <div class="dialog-container" @click.stop>
          <div class="dialog-header">
            <div class="header-content">
              <div class="header-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                  <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
                  <line x1="12" y1="22.08" x2="12" y2="12"/>
                </svg>
              </div>
              <h3>{{ service.name }}</h3>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
          <div class="dialog-body">
            <div class="replicas-summary">
              <div class="summary-item">
                <span class="summary-label">总副本数</span>
                <span class="summary-value">{{ replicas.length }}</span>
              </div>
              <div class="summary-item">
                <span class="summary-label">运行中</span>
                <span class="summary-value success">{{ replicas.filter(r => r.status === 'running').length }}</span>
              </div>
              <div class="summary-item">
                <span class="summary-label">启动中</span>
                <span class="summary-value warning">{{ replicas.filter(r => ['starting', 'preparing', 'ready', 'assigned', 'accepted'].includes(r.status)).length }}</span>
              </div>
              <div class="summary-item">
                <span class="summary-label">失败/异常</span>
                <span class="summary-value danger">{{ replicas.filter(r => ['failed', 'rejected', 'error'].includes(r.status)).length }}</span>
              </div>
              <div class="summary-item">
                <button class="btn btn-view-logs" @click="handleViewServiceLogs">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                    <polyline points="14 2 14 8 20 8"/>
                    <line x1="16" y1="13" x2="8" y2="13"/>
                    <line x1="16" y1="17" x2="8" y2="17"/>
                    <polyline points="10 9 9 9 8 9"/>
                  </svg>
                  查看日志
                </button>
              </div>
            </div>

            <!-- Docker 运行参数（可展开/收起，默认收起） -->
            <div class="docker-params-section">
              <button class="docker-params-toggle" @click="showDockerParams = !showDockerParams">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polyline points="4 17 10 11 4 5"/>
                  <line x1="12" y1="19" x2="20" y2="19"/>
                </svg>
                <span>Docker 运行参数</span>
                <svg
                  class="toggle-arrow"
                  :class="{ rotated: showDockerParams }"
                  width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                >
                  <polyline points="6 9 12 15 18 9"/>
                </svg>
              </button>
              <div v-if="showDockerParams" class="docker-params-body">
                <div class="params-row">
                  <span class="params-label">镜像</span>
                  <code class="params-value">{{ service.dockerImage || '-' }}</code>
                </div>
                <!-- 解析失败降级为原文展示 -->
                <div v-if="dockerParamGroups === null" class="params-row">
                  <span class="params-label">参数</span>
                  <pre class="params-value params-pre">{{ service.dockerParams || '无' }}</pre>
                </div>
                <div v-else-if="!dockerParamGroups.length" class="params-empty">无运行参数</div>
                <div v-else class="params-groups">
                  <!-- 分类 tab chips：点哪个看哪个 -->
                  <div class="params-tabs">
                    <button
                      v-for="group in dockerParamGroups"
                      :key="group.title"
                      class="params-tab"
                      :class="{ active: group.title === activeGroupTitle }"
                      @click="activeParamGroup = group.title"
                    >
                      {{ group.title }}
                      <span class="params-tab-count">{{ group.items.length }}</span>
                    </button>
                  </div>
                  <div v-if="currentGroup" class="params-detail">
                    <template v-for="item in currentGroup.items" :key="item.label">
                      <template v-if="Array.isArray(item.value)">
                        <code v-for="(line, i) in item.value" :key="item.label + '-' + i" class="param-line">
                          <span class="param-line-key">{{ item.label }}:</span>{{ line }}
                        </code>
                      </template>
                      <code v-else class="param-line">
                        <span class="param-line-key">{{ item.label }}:</span>{{ item.value || '-' }}
                      </code>
                    </template>
                  </div>
                </div>
              </div>
            </div>

            <div class="replicas-list">
              <div 
                v-for="replica in replicas" 
                :key="replica.id"
                class="replica-card"
              >
                <div class="replica-header">
                  <div class="replica-name">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="2" y="3" width="20" height="14" rx="2"/>
                      <path d="M8 21h8M12 17v4"/>
                    </svg>
                    {{ replica.name }}
                  </div>
                  <div class="replica-header-right">
                    <div class="range-chips" role="group" aria-label="时间窗切换">
                      <button
                        v-for="opt in RANGE_OPTIONS"
                        :key="opt.value"
                        class="range-chip"
                        :class="{ active: getRange(replica) === opt.value }"
                        :title="'小图时间窗：' + opt.label"
                        @click="setRange(replica, opt.value)"
                      >
                        {{ opt.label }}
                      </button>
                    </div>
                    <div class="replica-actions-inline">
                      <button
                        class="btn-icon"
                        @click="handleViewReplicaLogs(replica)"
                        title="查看副本日志"
                        :aria-label="`查看 ${replica.name} 日志`"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                          <polyline points="14 2 14 8 20 8"/>
                          <line x1="16" y1="13" x2="8" y2="13"/>
                          <line x1="16" y1="17" x2="8" y2="17"/>
                        </svg>
                      </button>
                      <button 
                        class="btn-icon" 
                        @click="handleEnterContainer(replica)"
                        :disabled="!canEnterContainer(replica)"
                        :title="readOnly
                          ? '服务任务处理中，暂不可进入容器终端'
                          : (canEnterContainer(replica) ? '进入容器终端' : '副本未运行或容器 ID 未采集，暂不可进入')"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <polyline points="4 17 10 11 4 5"/>
                          <line x1="12" y1="19" x2="20" y2="19"/>
                        </svg>
                      </button>
                    </div>
                    <span class="replica-status" :class="getStatusClass(replica.status)">
                      {{ getStatusText(replica.status) }}
                    </span>
                  </div>
                </div>
                
                <div class="replica-details">
                  <div class="detail-item">
                    <span class="detail-label">节点</span>
                    <span class="detail-value">{{ replica.nodeIp || replica.node }}</span>
                  </div>
                  <div class="detail-item">
                    <span class="detail-label">容器ID</span>
                    <span class="detail-value">{{ replica.containerId || '-' }}</span>
                  </div>
                  <div class="detail-item">
                    <span class="detail-label">运行时间</span>
                    <span class="detail-value">{{ replica.uptime || '-' }}</span>
                  </div>
                  <div class="detail-item">
                    <span class="detail-label">重启次数</span>
                    <span class="detail-value">{{ replica.restartCount || 0 }}</span>
                  </div>
                </div>

                <!-- 副本实时指标（CPU / 内存 分开两块，左侧 label+value 上下布局，右侧图表） -->
                <div class="replica-metrics">
                  <div class="metrics-section">
                    <div class="metric-row">
                      <div class="metric-text">
                        <span class="metric-label">CPU</span>
                        <span class="metric-value cpu">{{ replica.cpuPercent != null ? replica.cpuPercent.toFixed(1) + '%' : '-' }}</span>
                      </div>
                      <SparkLine
                        :points="replica.cpuSpark || []"
                        :timestamps="replica.timestamps || []"
                        :width="140"
                        :height="52"
                        color="#667eea"
                        :fill="true"
                        :interactive="true"
                        label="CPU"
                        unit="%"
                      />
                    </div>
                  </div>
                  <div class="metrics-section">
                    <div class="metric-row">
                      <div class="metric-text">
                        <span class="metric-label">MEM</span>
                        <span class="metric-value mem">{{ replica.memPercent != null ? replica.memPercent.toFixed(1) + '%' : '-' }}</span>
                        <span v-if="replica.memoryUsage != null" class="metric-hint">{{ memHint(replica) }}</span>
                      </div>
                      <SparkLine
                        :points="replica.memSpark || []"
                        :timestamps="replica.timestamps || []"
                        :width="140"
                        :height="52"
                        color="#f5a623"
                        :fill="true"
                        :interactive="true"
                        label="内存"
                        unit="%"
                        :hint-text="memHint(replica)"
                      />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          
          <div class="dialog-footer">
            <button class="btn btn-secondary" @click="handleClose">关闭</button>
          </div>
        </div>
      </div>
    </Transition>
    
    <!-- 日志查看对话框 -->
    <LogViewerDialog
      v-if="service?.id != null"
      :visible="showLogViewer"
      :replica="selectedReplica || {}"
      :service-id="service.id"
      @update:visible="showLogViewer = $event"
    />

    <!-- Web 终端（进入容器） -->
    <TerminalDialog
      v-if="service?.id != null"
      :visible="showTerminal && !readOnly"
      :service-id="service.id"
      :replica="terminalReplica || {}"
      @update:visible="showTerminal = $event"
    />
  </Teleport>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 1rem;
}

.dialog-container {
  background: var(--bg-secondary);
  border-radius: 16px;
  width: 100%;
  max-width: 800px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  overflow: hidden;
}

.dialog-header {
  padding: 0.875rem 1.5rem;
  background: var(--primary-gradient);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.header-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: white;
}

.btn-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.dialog-body {
  padding: 2rem;
  overflow-y: auto;
  flex: 1;
}

.dialog-body::-webkit-scrollbar {
  width: 8px;
}

.dialog-body::-webkit-scrollbar-track {
  background: var(--bg-primary);
  border-radius: 4px;
}

.dialog-body::-webkit-scrollbar-thumb {
  background: var(--text-tertiary);
  border-radius: 4px;
}

.dialog-body::-webkit-scrollbar-thumb:hover {
  background: var(--text-secondary);
}

.replicas-summary {
  display: flex;
  gap: 1.5rem;
  margin-bottom: 2rem;
  padding: 1.5rem;
  background: var(--bg-primary);
  border-radius: 12px;
  border: 1px solid var(--border-color);
}

.summary-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.summary-item:last-child {
  flex: initial;
  min-width: auto;
  justify-content: center;
}

.btn-view-logs {
  padding: 0.75rem 1.25rem;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.9rem;
  border: none;
  background: var(--primary-gradient);
  color: white;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
}

.btn-view-logs:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.btn-view-logs:active {
  transform: translateY(0);
}

.summary-label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.summary-value {
  font-size: 1.75rem;
  font-weight: 600;
  color: var(--text-primary);
}

.summary-value.success {
  color: var(--success-color);
}

.summary-value.warning {
  color: var(--warning-color);
}

.summary-value.danger {
  color: var(--danger-color);
}

.replicas-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

/* Docker 运行参数折叠区 */
.docker-params-section {
  margin: -1rem 0 1.5rem;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  background: var(--bg-primary);
  overflow: hidden;
}

.docker-params-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.875rem 1.25rem;
  background: transparent;
  border: none;
  color: var(--text-primary);
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}

.docker-params-toggle:hover {
  background: var(--bg-hover);
}

.docker-params-toggle svg:first-child {
  color: var(--primary-color);
}

.toggle-arrow {
  margin-left: auto;
  color: var(--text-tertiary);
  transition: transform 0.2s;
}

.toggle-arrow.rotated {
  transform: rotate(180deg);
}

.docker-params-body {
  padding: 0 1.25rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  border-top: 1px solid var(--border-color);
  padding-top: 0.875rem;
}

.params-row {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
}

.params-label {
  flex-shrink: 0;
  width: 40px;
  font-size: 0.78rem;
  color: var(--text-secondary);
  line-height: 1.6;
}

.params-value {
  flex: 1;
  min-width: 0;
  font-family: 'Courier New', monospace;
  font-size: 0.78rem;
  color: var(--text-primary);
  word-break: break-all;
}

.params-pre {
  margin: 0;
  padding: 0.625rem 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  white-space: pre-wrap;
  line-height: 1.6;
  max-height: 200px;
  overflow-y: auto;
}

.params-empty {
  font-size: 0.8rem;
  color: var(--text-tertiary);
  padding: 0.25rem 0;
}

/* 分类 tab 式展示（与编辑弹窗同一套分类） */
.params-groups {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.params-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.params-tab {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  background: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  line-height: 1;
}

.params-tab:hover:not(.active) {
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.params-tab.active {
  background: var(--primary-color);
  border-color: var(--primary-color);
  color: white;
  box-shadow: 0 2px 6px rgba(102, 126, 234, 0.3);
}

.params-tab-count {
  padding: 0.1rem 0.4rem;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 600;
  background: color-mix(in srgb, var(--primary-color) 12%, transparent);
  color: var(--primary-color);
  line-height: 1.2;
}

.params-tab.active .params-tab-count {
  background: rgba(255, 255, 255, 0.25);
  color: white;
}

/* 当前分类的参数明细：每行 key: value */
.params-detail {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  padding: 0.875rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
}

.param-line {
  display: block;
  font-family: 'Courier New', monospace;
  font-size: 0.85rem;
  color: var(--text-primary);
  line-height: 1.6;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  word-break: break-all;
  white-space: pre-wrap;
}

.param-line-key {
  color: var(--primary-color);
  font-weight: 700;
  margin-right: 0.5rem;
}

.replica-card {
  padding: 1.25rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  transition: all 0.2s;
}

.replica-card:hover {
  border-color: var(--primary-color);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.1);
}

.replica-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border-color);
}

.replica-header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

/* 时间窗切换 chip 组（位于日志/终端图标左侧） */
.range-chips {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
}

.range-chip {
  padding: 2px 8px;
  min-width: 30px;
  height: 22px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.72rem;
  font-weight: 500;
  font-family: 'Courier New', monospace;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  line-height: 1;
}

.range-chip:hover:not(.active) {
  background: color-mix(in srgb, var(--primary-color) 8%, transparent);
  color: var(--primary-color);
}

.range-chip.active {
  background: var(--primary-color);
  color: white;
  box-shadow: 0 1px 3px rgba(102, 126, 234, 0.3);
}

.replica-actions-inline {
  display: flex;
  gap: 0.5rem;
}

.btn-icon {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-icon:hover:not(:disabled) {
  border-color: var(--primary-color);
  color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 5%, transparent);
  transform: translateY(-1px);
}

.btn-icon:active:not(:disabled) {
  transform: translateY(0);
}

.btn-icon:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.replica-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 600;
  color: var(--text-primary);
  font-size: 1rem;
}

.replica-name svg {
  color: var(--primary-color);
}

.replica-status {
  padding: 0.375rem 0.75rem;
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 500;
}

.status-success {
  background: color-mix(in srgb, var(--success-color) 15%, transparent);
  color: var(--success-color);
}

.status-warning {
  background: color-mix(in srgb, var(--warning-color) 15%, transparent);
  color: var(--warning-color);
}

.status-danger {
  background: color-mix(in srgb, var(--danger-color) 15%, transparent);
  color: var(--danger-color);
}

.status-neutral {
  background: color-mix(in srgb, var(--text-tertiary) 15%, transparent);
  color: var(--text-tertiary);
}

.replica-details {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.75rem;
  row-gap: 0.9rem;
  margin-bottom: 0.85rem;
}

/* 副本实时指标：CPU / MEM 并排两块，内部样式与主页服务卡一致 */
.replica-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.metrics-section {
  padding: 0.65rem 0.9rem;
  background: var(--bg-primary);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.metric-row {
  display: flex;
  align-items: center;
  gap: 0.8rem;
}

.metric-text {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 68px;
  flex-shrink: 0;
}

.metric-label {
  font-weight: 700;
  font-size: 0.68rem;
  color: var(--text-secondary);
  letter-spacing: 0.06em;
  line-height: 1;
}

.metric-value {
  font-family: 'Courier New', monospace;
  font-weight: 700;
  font-size: 0.95rem;
  line-height: 1.1;
}

.metric-value.cpu {
  color: var(--primary-color);
}

.metric-value.mem {
  color: #f5a623;
}

/* 内存具体占用提示（如 256M / 1G），放在百分比下方 */
.metric-hint {
  font-family: 'Courier New', monospace;
  font-size: 0.7rem;
  color: var(--text-tertiary);
  line-height: 1.1;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

/* SparkLine 在副本卡下高 52px，与 hover tooltip 交互适配 */
.metric-row :deep(.sparkline) {
  flex: 1;
  min-width: 0;
  height: 52px;
}

/* 交互模式 wrapper 拉伸占满 metric-row 剩余宽度，svg 自动跟随（避免右侧留白） */
.metric-row :deep(.sparkline-wrap) {
  flex: 1 1 0;
  min-width: 0;
  width: auto !important;
  height: 52px !important;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.detail-label {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.detail-value {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary);
}

.node-ip {
  color: var(--text-secondary);
  font-weight: 400;
  margin-left: 0.25rem;
}

.dialog-footer {
  padding: 1.5rem 2rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
  flex-shrink: 0;
  background: var(--bg-secondary);
}

.btn {
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.9rem;
  transition: all 0.2s;
  cursor: pointer;
  border: none;
}

.btn-secondary {
  background: white;
  border: 2px solid var(--border-color);
  color: var(--text-primary);
}

.btn-secondary:hover {
  border-color: var(--border-hover);
  background: var(--bg-hover);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.3s;
}

.dialog-fade-enter-active .dialog-container,
.dialog-fade-leave-active .dialog-container {
  transition: transform 0.3s, opacity 0.3s;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-from .dialog-container,
.dialog-fade-leave-to .dialog-container {
  transform: scale(0.9);
  opacity: 0;
}

@media (max-width: 640px) {
  .dialog-overlay {
    padding: 0.5rem;
  }

  .dialog-container {
    max-height: calc(100vh - 1rem);
    border-radius: 12px;
  }

  .dialog-header {
    padding: 0.75rem;
  }

  .header-content {
    min-width: 0;
    gap: 0.625rem;
  }

  .dialog-header h3 {
    min-width: 0;
    font-size: 1rem;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .dialog-body {
    padding: 0.75rem;
  }

  .replicas-summary {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.75rem;
    margin-bottom: 1rem;
    padding: 1rem;
  }

  .summary-item:last-child {
    grid-column: 1 / -1;
  }

  .btn-view-logs {
    width: 100%;
    justify-content: center;
  }

  .docker-params-section {
    margin: 0 0 1rem;
  }

  .replica-card {
    padding: 0.875rem;
  }

  .replica-header {
    align-items: flex-start;
    gap: 0.75rem;
  }

  .replica-header-right {
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 0.5rem;
  }

  .replica-details {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-value {
    overflow-wrap: anywhere;
  }

  .replica-metrics {
    grid-template-columns: minmax(0, 1fr);
  }

  .dialog-footer {
    padding: 0.75rem;
  }
}
</style>
