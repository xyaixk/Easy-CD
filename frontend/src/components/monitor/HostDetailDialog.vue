<script setup>
import { ref, computed, watch } from 'vue'
import SparkLine from './SparkLine.vue'
import MetricBar from './MetricBar.vue'
import { getHostMetrics, getHostReplicas } from '@/api/monitor'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  host: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:visible'])

const range = ref('5m')
const rangeOptions = [
  { label: '5m', value: '5m' },
  { label: '1h', value: '1h' },
  { label: '6h', value: '6h' },
  { label: '24h', value: '24h' },
  { label: '7d', value: '7d' }
]

const metricsData = ref(null)
const replicas = ref([])
const loading = ref(false)

function close() {
  emit('update:visible', false)
}

async function loadAll() {
  if (!props.host?.id) return
  loading.value = true
  try {
    const [m, r] = await Promise.all([
      getHostMetrics(props.host.id, range.value),
      getHostReplicas(props.host.id)
    ])
    metricsData.value = m
    replicas.value = r
  } catch (e) {
    console.warn('[HostDetailDialog] load failed', e)
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.visible, props.host?.id],
  ([v]) => {
    if (v) loadAll()
    else {
      metricsData.value = null
      replicas.value = []
    }
  },
  { immediate: true }
)

watch(range, () => {
  if (props.visible) loadAll()
})

function formatSize(bytes) {
  if (!bytes) return '-'
  const gb = bytes / (1024 * 1024 * 1024)
  if (gb >= 1) return `${gb.toFixed(2)} GB`
  const mb = bytes / (1024 * 1024)
  return `${mb.toFixed(0)} MB`
}

const roleLabel = computed(() => {
  const r = props.host?.swarmRole || 'unknown'
  return r === 'manager' ? 'Manager' : r === 'worker' ? 'Worker' : r
})

function replicaLevelClass(v) {
  if (v >= 90) return 'danger'
  if (v >= 70) return 'warning'
  return 'ok'
}
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="visible" class="dialog-overlay" @click.self="close">
        <div class="dialog-container">
          <!-- Header -->
          <header class="dialog-header">
            <div class="header-left">
              <div class="header-icon">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="2" y="3" width="20" height="14" rx="2"/>
                  <line x1="8" y1="21" x2="16" y2="21"/>
                  <line x1="12" y1="17" x2="12" y2="21"/>
                </svg>
              </div>
              <div>
                <h3>{{ host?.hostname || '-' }}</h3>
                <div class="header-meta">
                  <span class="role" :class="{ manager: host?.swarmRole === 'manager' }">{{ roleLabel }}</span>
                  <span>{{ host?.ip }}</span>
                  <span>·</span>
                  <span>{{ host?.cpuCores }} 核</span>
                  <span>·</span>
                  <span>{{ formatSize(host?.memTotal) }} 内存</span>
                </div>
              </div>
            </div>
            <button class="btn-close" @click="close">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </header>

          <!-- Body -->
          <div class="dialog-body">
            <!-- 当前指标概览 -->
            <section class="section overview">
              <div class="overview-item">
                <MetricBar label="CPU" :value="host?.cpuPercent" :height="8" />
              </div>
              <div class="overview-item">
                <MetricBar label="内存" :value="host?.memPercent" :height="8"
                           :hint="`${formatSize(host?.memUsed)} / ${formatSize(host?.memTotal)}`" />
              </div>
              <div class="overview-item">
                <MetricBar label="磁盘" :value="host?.diskPercent" :height="8"
                           :hint="`${formatSize(host?.diskUsed)} / ${formatSize(host?.diskTotal)}`" />
              </div>
              <div class="overview-item load">
                <div class="load-label">Load Avg</div>
                <div class="load-values">
                  <span>{{ host?.load1 ?? '-' }}</span>
                  <span class="sep">/</span>
                  <span>{{ host?.load5 ?? '-' }}</span>
                  <span class="sep">/</span>
                  <span>{{ host?.load15 ?? '-' }}</span>
                </div>
                <div class="load-hint">1 / 5 / 15 min</div>
              </div>
            </section>

            <!-- 时序图 -->
            <section class="section">
              <div class="section-header">
                <h4>指标趋势</h4>
                <div class="range-tabs">
                  <button
                    v-for="opt in rangeOptions"
                    :key="opt.value"
                    :class="{ active: range === opt.value }"
                    @click="range = opt.value"
                  >{{ opt.label }}</button>
                </div>
              </div>

              <div class="chart-grid">
                <div class="chart-card">
                  <div class="chart-title">
                    <span>CPU</span>
                    <span class="chart-current">{{ host?.cpuPercent ?? '-' }}%</span>
                  </div>
                  <SparkLine
                    :points="metricsData?.cpu || []"
                    :timestamps="metricsData?.timestamps || []"
                    :width="500"
                    :height="80"
                    color="#667eea"
                    :fill="true"
                    :interactive="true"
                    label="CPU"
                    unit="%"
                  />
                </div>
                <div class="chart-card">
                  <div class="chart-title">
                    <span>内存</span>
                    <span class="chart-current">{{ host?.memPercent ?? '-' }}%</span>
                  </div>
                  <SparkLine
                    :points="metricsData?.mem || []"
                    :timestamps="metricsData?.timestamps || []"
                    :width="500"
                    :height="80"
                    color="#f5a623"
                    :fill="true"
                    :interactive="true"
                    label="内存"
                    unit="%"
                  />
                </div>
                <div class="chart-card">
                  <div class="chart-title">
                    <span>磁盘</span>
                    <span class="chart-current">{{ host?.diskPercent ?? '-' }}%</span>
                  </div>
                  <SparkLine
                    :points="metricsData?.disk || []"
                    :timestamps="metricsData?.timestamps || []"
                    :width="500"
                    :height="80"
                    color="#26a69a"
                    :fill="true"
                    :interactive="true"
                    label="磁盘"
                    unit="%"
                  />
                </div>
                <div class="chart-card">
                  <div class="chart-title">
                    <span>Load1</span>
                    <span class="chart-current">{{ host?.load1 ?? '-' }}</span>
                  </div>
                  <SparkLine
                    :points="metricsData?.load1 || []"
                    :timestamps="metricsData?.timestamps || []"
                    :width="500"
                    :height="80"
                    color="#ab47bc"
                    :fill="true"
                    :max="host?.cpuCores ? host.cpuCores * 2 : 8"
                    :interactive="true"
                    label="Load1"
                    unit=""
                    :value-format="(v) => Number(v).toFixed(2)"
                  />
                </div>
              </div>
            </section>

            <!-- 容器列表 -->
            <section class="section">
              <div class="section-header">
                <h4>节点上的容器 ({{ replicas.length }})</h4>
              </div>
              <div class="replicas-table">
                <div class="tr th">
                  <div class="td name">容器名</div>
                  <div class="td service">所属服务</div>
                  <div class="td status">状态</div>
                  <div class="td metric">CPU</div>
                  <div class="td metric">MEM</div>
                </div>
                <div v-for="r in replicas" :key="r.replicaId" class="tr">
                  <div class="td name">{{ r.replicaName }}</div>
                  <div class="td service">{{ r.serviceName }}</div>
                  <div class="td status">
                    <span class="status-tag" :class="r.status">{{ r.status }}</span>
                  </div>
                  <div class="td metric" :class="replicaLevelClass(r.cpuPercent)">{{ r.cpuPercent }}%</div>
                  <div class="td metric" :class="replicaLevelClass(r.memPercent)">{{ r.memPercent }}%</div>
                </div>
                <div v-if="replicas.length === 0" class="empty">该节点暂无容器</div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </Transition>
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
  max-width: 1100px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.dialog-header {
  padding: 0.875rem 1.5rem;
  background: var(--primary-gradient);
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: white;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 0.85rem;
}

.header-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
}

.dialog-header h3 {
  margin: 0 0 4px 0;
  font-size: 1.15rem;
  font-weight: 600;
}

.header-meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.8rem;
  opacity: 0.9;
  font-family: 'Courier New', monospace;
}

.role {
  padding: 1px 6px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  font-size: 0.7rem;
  letter-spacing: 0.03em;
}

.role.manager {
  background: rgba(255, 255, 255, 0.3);
  font-weight: 600;
}

.btn-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.dialog-body {
  padding: 1.5rem 1.75rem;
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.section-header h4 {
  margin: 0;
  font-size: 0.95rem;
  color: var(--text-primary);
  font-weight: 600;
}

.overview {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1.25rem;
  padding: 1rem 1.25rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
}

.load {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.load-label {
  font-size: 0.7rem;
  color: var(--text-secondary);
  font-weight: 600;
}

.load-values {
  font-family: 'Courier New', monospace;
  font-size: 1rem;
  font-weight: 700;
  color: var(--primary-color);
  display: flex;
  gap: 0.4rem;
}

.load-values .sep {
  color: var(--text-tertiary);
}

.load-hint {
  font-size: 0.65rem;
  color: var(--text-tertiary);
}

.range-tabs {
  display: flex;
  gap: 0.25rem;
  padding: 3px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.range-tabs button {
  padding: 4px 10px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 0.75rem;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.15s;
}

.range-tabs button:hover {
  color: var(--primary-color);
}

.range-tabs button.active {
  background: var(--primary-color);
  color: white;
  font-weight: 600;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.85rem;
}

.chart-card {
  padding: 0.85rem 1rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
}

.chart-title {
  display: flex;
  justify-content: space-between;
  margin-bottom: 0.35rem;
  font-size: 0.78rem;
  color: var(--text-secondary);
  font-weight: 600;
}

.chart-current {
  font-family: 'Courier New', monospace;
  color: var(--primary-color);
  font-weight: 700;
}

.chart-card :deep(.sparkline-wrap) {
  width: 100% !important;
  height: 80px !important;
}

.chart-card :deep(.sparkline) {
  width: 100%;
  height: 80px;
}

.replicas-table {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg-primary);
}

.tr {
  display: grid;
  grid-template-columns: 2fr 1.5fr 1fr 1fr 1fr;
  padding: 0.6rem 1rem;
  border-bottom: 1px solid var(--border-color);
  font-size: 0.82rem;
  align-items: center;
}

.tr:last-child {
  border-bottom: none;
}

.tr.th {
  background: var(--bg-hover);
  font-weight: 600;
  color: var(--text-secondary);
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}

.td.name {
  font-family: 'Courier New', monospace;
  color: var(--text-primary);
}

.td.service {
  color: var(--text-secondary);
}

.td.metric {
  font-family: 'Courier New', monospace;
  font-weight: 600;
}

.td.metric.ok {
  color: var(--success-color);
}

.td.metric.warning {
  color: var(--warning-color);
}

.td.metric.danger {
  color: var(--danger-color);
}

.status-tag {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 0.7rem;
  font-weight: 500;
  background: color-mix(in srgb, var(--success-color) 15%, transparent);
  color: var(--success-color);
}

.status-tag.starting {
  background: color-mix(in srgb, var(--warning-color) 15%, transparent);
  color: var(--warning-color);
}

.empty {
  padding: 2rem;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 0.85rem;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
