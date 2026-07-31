<script setup>
import { ref, computed } from 'vue'
import ServiceActionMenu from './ServiceActionMenu.vue'
import SparkLine from './monitor/SparkLine.vue'

const props = defineProps({
  service: {
    type: Object,
    required: true
  },
  viewDisabled: {
    type: Boolean,
    default: false
  }
})

// 回退：后端 sparkline 未接入前使用本地生成的伪时序（真实指标接入后 service.cpuSpark/memSpark 优先）
const genFallbackSpark = (base = 20) => {
  const arr = []
  let cur = Math.max(5, Math.min(90, Number(base) || 20))
  for (let i = 0; i < 20; i++) {
    cur += (Math.random() - 0.5) * 10
    cur = Math.max(0, Math.min(100, cur))
    arr.push(Number(cur.toFixed(1)))
  }
  return arr
}

// 聚合模式：avg 平均 / max 副本中最高（默认 max，更容易定位异常副本）
const metricMode = ref('max')
const toggleMetricMode = () => {
  metricMode.value = metricMode.value === 'avg' ? 'max' : 'avg'
}

// 根据当前模式挑选服务对象上的字段
// 真实字段：cpuPercent/memoryPercent/cpuSpark/memSpark（AVG）
//         cpuPercentMax/memoryPercentMax/cpuSparkMax/memSparkMax（MAX）
const cpuValue = computed(() => {
  const v = metricMode.value === 'max' ? props.service.cpuPercentMax : props.service.cpuPercent
  // MAX 模式下若后端未提供，回退为 AVG × 1.6 clamp 100
  if (metricMode.value === 'max' && v == null && props.service.cpuPercent != null) {
    return Math.min(100, Math.round(props.service.cpuPercent * 1.6))
  }
  return v
})

const memValue = computed(() => {
  const v = metricMode.value === 'max' ? props.service.memoryPercentMax : props.service.memoryPercent
  if (metricMode.value === 'max' && v == null && props.service.memoryPercent != null) {
    return Math.min(100, Math.round(props.service.memoryPercent * 1.4))
  }
  return v
})

const cpuSparkPoints = computed(() => {
  const key = metricMode.value === 'max' ? 'cpuSparkMax' : 'cpuSpark'
  if (Array.isArray(props.service[key]) && props.service[key].length > 0) return props.service[key]
  return genFallbackSpark(cpuValue.value ?? 15)
})

const memSparkPoints = computed(() => {
  const key = metricMode.value === 'max' ? 'memSparkMax' : 'memSpark'
  if (Array.isArray(props.service[key]) && props.service[key].length > 0) return props.service[key]
  return genFallbackSpark(memValue.value ?? 30)
})

const cpuPercentDisplay = computed(() => (cpuValue.value != null ? `${Math.round(cpuValue.value)}%` : '-'))
const memPercentDisplay = computed(() => (memValue.value != null ? `${Math.round(memValue.value)}%` : '-'))

const modeLabel = computed(() => (metricMode.value === 'max' ? 'MAX' : 'AVG'))
const modeTooltip = computed(() =>
  metricMode.value === 'avg' ? '点击切换到 MAX（副本中最高值）' : '点击切换到 AVG（副本平均值）'
)

const emit = defineEmits(['update', 'rollback', 'restart', 'stop', 'scale', 'view', 'edit', 'copy', 'delete'])

const showActionMenu = ref(false)
const handleCardView = () => {
  if (!props.viewDisabled) emit('view', props.service)
}

const getStatusColor = (status) => {
  const colors = {
    running: '#10b981',      // 绿色 - 运行中
    stopped: '#94a3b8',      // 灰色 - 已停止
    deploying: '#f59e0b',    // 橙色 - 部署中
    failed: '#ef4444',       // 红色 - 失败
    degraded: '#f97316',     // 深橙 - 降级运行
    scaling: '#3b82f6',      // 蓝色 - 扩缩容中
    unknown: '#6b7280'       // 深灰 - 未知
  }
  return colors[status] || '#94a3b8'
}

const getStatusText = (status) => {
  const texts = {
    running: '运行中',
    stopped: '已停止',
    deploying: '部署中',
    failed: '失败',
    degraded: '降级',
    scaling: '扩缩容中',
    unknown: '未知'
  }
  return texts[status] || status
}

// 格式化字节大小
const formatBytes = (bytes) => {
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

// 计算CPU百分比显示
const cpuDisplay = computed(() => {
  const cpu = props.service.cpuPercent
  return cpu != null ? Math.round(cpu) + '%' : '0%'
})

// 计算内存显示
const memoryDisplay = computed(() => {
  const usage = props.service.memoryUsage
  return formatBytes(usage)
})

const descriptionText = computed(() => {
  const description = props.service.description
  return description && description.trim() ? description : '暂无描述'
})
</script>

<template>
  <div class="service-card" @click="handleCardView">
    <div class="service-header">
      <h3 class="service-name" :title="service.name">{{ service.name }}</h3>
      <div class="service-meta-row">
        <span 
          class="service-status"
          :style="{ '--status-color': getStatusColor(service.status) }"
        >
          <span class="status-dot" :class="{ 'spinning': service.status === 'deploying' }"></span>
          {{ getStatusText(service.status) }}
        </span>
        <span v-if="service.version" class="service-version">{{ service.version }}</span>
      </div>
    </div>
    
    <p class="service-description" :class="{ placeholder: !service.description || !service.description.trim() }">
      {{ descriptionText }}
    </p>
    
    <div class="metrics-section" :title="modeTooltip" @click.stop="toggleMetricMode">
      <span class="metric-mode-tag" :class="metricMode">{{ modeLabel }}</span>
      <div class="metric-row">
        <span class="metric-label">CPU</span>
        <span class="metric-value cpu">{{ cpuPercentDisplay }}</span>
        <SparkLine :points="cpuSparkPoints" :width="120" :height="18" color="#667eea" :fill="true" />
      </div>
      <div class="metric-row">
        <span class="metric-label">MEM</span>
        <span class="metric-value mem">{{ memPercentDisplay }}</span>
        <SparkLine :points="memSparkPoints" :width="120" :height="18" color="#f5a623" :fill="true" />
      </div>
    </div>

    <div class="replicas-section">
      <div class="replicas-display">
        <span class="replica-value healthy">{{ service.healthyInstances }}</span>
        <span class="replica-label">健康</span>
        <span class="replica-divider">/</span>
        <span class="replica-value running">{{ service.instances }}</span>
        <span class="replica-label">运行</span>
        <span class="replica-divider">/</span>
        <template v-if="service.serviceMode === 'global'">
          <span class="replica-label">Global</span>
        </template>
        <template v-else>
          <span class="replica-value desired">{{ service.desiredInstances }}</span>
          <span class="replica-label">期望</span>
        </template>
      </div>
    </div>
    
    <div class="service-footer">
      <span class="last-deploy">最后部署: {{ service.lastDeploy }}</span>
      <div class="service-actions" @click.stop>
        <ServiceActionMenu
          :service="service"
          @update="(payload) => emit('update', payload)"
          @rollback="(payload) => emit('rollback', payload)"
          @restart="emit('restart', service)"
          @stop="emit('stop', service)"
          @scale="(payload) => emit('scale', payload)"
          @view="emit('view', service)"
          @edit="emit('edit', service)"
          @copy="emit('copy', service)"
          @delete="emit('delete', service)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.service-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1.25rem;
  cursor: pointer;
  transition: all 0.3s;
}

.service-card:hover {
  box-shadow: var(--shadow-xl);
  border-color: var(--primary-color);
}

.service-header {
  margin-bottom: 0.75rem;
}

.service-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.4rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.service-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}



.service-status {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.25rem 0.75rem;
  border-radius: 6px;
  background: color-mix(in srgb, var(--status-color) 10%, transparent);
  color: var(--status-color);
  font-size: 0.75rem;
  font-weight: 600;
}


.replicas-section {
  padding: 0.625rem 1rem;
  background: var(--bg-primary);
  border-radius: 8px;
  margin-bottom: 0.875rem;
  text-align: center;
}

.metrics-section {
  position: relative;
  padding: 0.55rem 0.85rem;
  background: var(--bg-primary);
  border-radius: 8px;
  margin-bottom: 0.625rem;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  cursor: pointer;
  transition: background 0.18s;
}

.metrics-section:hover {
  background: var(--bg-hover);
}

.metric-mode-tag {
  position: absolute;
  top: 4px;
  right: 8px;
  font-size: 0.6rem;
  font-weight: 700;
  font-family: 'Courier New', monospace;
  letter-spacing: 0.06em;
  line-height: 1;
  pointer-events: none;
  user-select: none;
  opacity: 0.7;
  transition: opacity 0.18s, color 0.18s;
}

.metrics-section:hover .metric-mode-tag {
  opacity: 1;
}

.metric-mode-tag.avg {
  color: var(--primary-color);
}

.metric-mode-tag.max {
  color: var(--warning-color);
}

.metric-row {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  font-size: 0.72rem;
}

.metric-label {
  font-weight: 700;
  color: var(--text-secondary);
  width: 32px;
  letter-spacing: 0.03em;
}

.metric-value {
  font-family: 'Courier New', monospace;
  font-weight: 700;
  font-size: 0.82rem;
  width: 42px;
  text-align: right;
}

.metric-value.cpu {
  color: var(--primary-color);
}

.metric-value.mem {
  color: #f5a623;
}

.metric-row :deep(.sparkline) {
  flex: 1;
  min-width: 0;
  height: 18px;
}

.replicas-display {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 0.375rem;
}

.replica-label {
  font-size: 0.65rem;
  color: var(--text-tertiary);
  font-weight: 500;
  margin-right: 0.25rem;
}

.replica-value {
  font-size: 1.75rem;
  font-weight: 700;
  font-family: 'Courier New', monospace;
  line-height: 1;
}

.replica-value.healthy {
  color: var(--success-color);
}

.replica-value.running {
  color: var(--info-color);
}

.replica-value.desired {
  color: var(--text-secondary);
}

.replica-divider {
  font-size: 1.25rem;
  color: var(--text-tertiary);
  font-weight: 300;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 2s infinite;
}

.status-dot.spinning {
  width: 10px;
  height: 10px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  background: transparent;
  animation: spin 0.8s linear infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.service-version {
  padding: 0.2rem 0.5rem;
  background: var(--bg-hover);
  border-radius: 5px;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-secondary);
  font-family: 'Courier New', monospace;
  white-space: nowrap;
}

.service-description {
  color: var(--text-secondary);
  font-size: 0.85rem;
  line-height: 1.5;
  margin-bottom: 0.875rem;
  min-height: 1.275rem;
}

.service-description.placeholder {
  color: var(--text-tertiary);
}

.service-info {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
  margin-bottom: 0.875rem;
  padding: 0.625rem;
  background: var(--bg-primary);
  border-radius: 8px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.info-item svg {
  color: var(--text-tertiary);
}

.service-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 0.875rem;
  border-top: 1px solid var(--border-color);
}

.last-deploy {
  font-size: 0.8rem;
  color: var(--text-tertiary);
}
</style>
