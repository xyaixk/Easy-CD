<script setup>
import { computed } from 'vue'
import MetricBar from './MetricBar.vue'

const props = defineProps({
  host: {
    type: Object,
    required: true
  }
})

defineEmits(['click'])

const roleLabel = computed(() => {
  const role = props.host?.swarmRole || 'unknown'
  return role === 'manager' ? 'Manager' : role === 'worker' ? 'Worker' : role
})

const statusOk = computed(() => (props.host?.swarmStatus || '').toLowerCase() === 'ready')

const uptimeText = computed(() => {
  const s = Number(props.host?.uptimeSeconds) || 0
  if (s < 60) return `${s}s`
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  if (days > 0) return `${days}d ${hours}h`
  const mins = Math.floor((s % 3600) / 60)
  return `${hours}h ${mins}m`
})

function formatSize(bytes) {
  if (!bytes) return '-'
  const gb = bytes / (1024 * 1024 * 1024)
  if (gb >= 1) return `${gb.toFixed(1)}G`
  const mb = bytes / (1024 * 1024)
  return `${mb.toFixed(0)}M`
}

const memHint = computed(() => {
  if (!props.host?.memTotal) return ''
  return `${formatSize(props.host.memUsed)} / ${formatSize(props.host.memTotal)}`
})

const diskHint = computed(() => {
  if (!props.host?.diskTotal) return ''
  return `${formatSize(props.host.diskUsed)} / ${formatSize(props.host.diskTotal)}`
})
</script>

<template>
  <div class="host-card" @click="$emit('click', host)">
    <!-- 左：身份信息 -->
    <div class="col-identity">
      <div class="host-title">
        <span class="status-dot" :class="{ ok: statusOk, off: !statusOk }"></span>
        <span class="hostname">{{ host.hostname }}</span>
        <span class="role-badge" :class="{ manager: host.swarmRole === 'manager' }">{{ roleLabel }}</span>
      </div>
      <div class="host-meta">
        <span class="ip">{{ host.ip }}</span>
        <span class="dot">·</span>
        <span>{{ host.cpuCores }}C</span>
        <span class="dot">·</span>
        <span>up {{ uptimeText }}</span>
      </div>
    </div>

    <!-- 中：三条指标 -->
    <div class="col-metrics">
      <div class="metric-cell">
        <MetricBar label="CPU" :value="host.cpuPercent" />
      </div>
      <div class="metric-cell">
        <MetricBar label="MEM" :value="host.memPercent" :hint="memHint" />
      </div>
      <div class="metric-cell">
        <MetricBar label="DISK" :value="host.diskPercent" :hint="diskHint" />
      </div>
    </div>

    <!-- 右：副本数 + 详情 -->
    <div class="col-actions">
      <span class="replica-count">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="3" width="7" height="7" rx="1"/>
          <rect x="14" y="3" width="7" height="7" rx="1"/>
          <rect x="3" y="14" width="7" height="7" rx="1"/>
          <rect x="14" y="14" width="7" height="7" rx="1"/>
        </svg>
        {{ host.replicaCount ?? 0 }} 副本
      </span>
      <span class="detail-hint">
        详情
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </span>
    </div>
  </div>
</template>

<style scoped>
.host-card {
  display: grid;
  grid-template-columns: minmax(200px, 240px) 1fr minmax(120px, 150px);
  align-items: center;
  gap: 1.5rem;
  padding: 0.75rem 1.1rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  min-height: 56px;
}

.host-card:hover {
  border-color: var(--primary-color);
  transform: translateX(2px);
  box-shadow: 0 3px 12px rgba(102, 126, 234, 0.1);
}

/* 左：身份 */
.col-identity {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}

.host-title {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.ok {
  background: var(--success-color);
  box-shadow: 0 0 6px var(--success-color);
}

.status-dot.off {
  background: var(--danger-color);
}

.hostname {
  font-weight: 700;
  font-size: 0.95rem;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-badge {
  padding: 1px 6px;
  font-size: 0.65rem;
  font-weight: 600;
  border-radius: 4px;
  background: var(--bg-hover);
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  flex-shrink: 0;
}

.role-badge.manager {
  background: color-mix(in srgb, var(--primary-color) 15%, transparent);
  color: var(--primary-color);
}

.host-meta {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.7rem;
  color: var(--text-tertiary);
  font-family: 'Courier New', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dot {
  opacity: 0.5;
}

/* 中：指标区 */
.col-metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.25rem;
  min-width: 0;
}

.metric-cell {
  min-width: 0;
}

/* 右：操作区 */
.col-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.35rem;
  font-size: 0.72rem;
  color: var(--text-secondary);
}

.replica-count {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  white-space: nowrap;
}

.detail-hint {
  display: flex;
  align-items: center;
  gap: 0.2rem;
  color: var(--primary-color);
  font-weight: 500;
  white-space: nowrap;
}

.host-card:hover .detail-hint {
  gap: 0.35rem;
}

/* 窄屏适配：<960px 时右列吸附合并 */
@media (max-width: 960px) {
  .host-card {
    grid-template-columns: minmax(160px, 200px) 1fr;
    row-gap: 0.6rem;
  }
  .col-actions {
    display: none;
  }
}
</style>
