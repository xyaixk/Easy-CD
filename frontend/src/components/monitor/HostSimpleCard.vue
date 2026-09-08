<script setup>
import { computed } from 'vue'
import MetricRing from './MetricRing.vue'
import toast from '@/utils/toast'

const props = defineProps({
  host: {
    type: Object,
    required: true
  },
  refreshFailed: {
    type: Boolean,
    default: false
  }
})

defineEmits(['click'])

const roleType = computed(() => (props.host?.swarmRole || '').toLowerCase())

const roleLabel = computed(() => {
  const role = roleType.value
  if (role === 'manager') return '管理节点'
  if (role === 'worker') return '工作节点'
  return role || '未知角色'
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

const cpuHint = computed(() => {
  const cores = Number(props.host?.cpuCores)
  return cores > 0 ? `${cores} 核 CPU` : ''
})

function copyWithLegacyApi(text) {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0;'
  document.body.appendChild(textarea)
  textarea.select()

  try {
    if (!document.execCommand('copy')) throw new Error('浏览器未允许复制操作')
  } finally {
    document.body.removeChild(textarea)
  }
}

async function copyHostValue(value, label) {
  if (!value) return

  let clipboardError = null
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      toast.success(`已复制 ${label}：${value}`)
      return
    } catch (error) {
      clipboardError = error
    }
  }

  try {
    copyWithLegacyApi(value)
    toast.success(`已复制 ${label}：${value}`)
  } catch (fallbackError) {
    console.error(`复制宿主机 ${label} 失败`, clipboardError || fallbackError)
    toast.error('复制失败，请检查浏览器剪贴板权限')
  }
}

function copyIp() {
  return copyHostValue(props.host?.ip, 'IP')
}

function copyHostname() {
  return copyHostValue(props.host?.hostname, 'hostname')
}
</script>

<template>
  <article
    class="host-card"
    @click="$emit('click', host)"
  >
    <div class="host-card-header">
      <div class="host-title-row">
        <div class="host-title">
          <span class="status-dot" :class="{ ok: statusOk, off: !statusOk }"></span>
          <button
            class="hostname"
            type="button"
            :disabled="!host.hostname"
            :title="host.hostname ? `复制 hostname：${host.hostname}` : '暂无 hostname'"
            :aria-label="host.hostname ? `复制宿主机 hostname ${host.hostname}` : '暂无宿主机 hostname'"
            @click.stop="copyHostname"
          >
            {{ host.hostname || '-' }}
          </button>
        </div>
        <span
          class="role-badge"
          :class="{
            manager: roleType === 'manager',
            worker: roleType === 'worker'
          }"
        >
          {{ roleLabel }}
        </span>
      </div>
      <div class="host-meta">
        <button
          class="meta-tag ip"
          type="button"
          :disabled="!host.ip"
          :title="host.ip ? `复制 IP：${host.ip}` : '暂无 IP'"
          :aria-label="host.ip ? `复制宿主机 IP ${host.ip}` : '暂无宿主机 IP'"
          @click.stop="copyIp"
        >
          {{ host.ip || '-' }}
        </button>
        <span class="meta-tag">{{ host.cpuCores || '-' }}C</span>
        <span class="meta-tag">{{ formatSize(host.memTotal) }} RAM</span>
        <span class="meta-tag">up {{ uptimeText }}</span>
      </div>
    </div>

    <div class="host-metrics" :class="{ 'refresh-failed': refreshFailed }">
      <div v-if="refreshFailed" class="metrics-refresh-alert" role="alert">
        <span class="metrics-alert-icon" aria-hidden="true">!</span>
        <span>宿主机数据刷新失败</span>
      </div>
      <template v-else>
        <MetricRing label="CPU" :value="host.cpuPercent" :hint="cpuHint" />
        <MetricRing label="MEM" :value="host.memPercent" :hint="memHint" />
        <MetricRing label="DISK" :value="host.diskPercent" :hint="diskHint" />
      </template>
    </div>

    <div class="host-card-footer">
      <span class="replica-count">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <rect x="3" y="3" width="7" height="7" rx="1"/>
          <rect x="14" y="3" width="7" height="7" rx="1"/>
          <rect x="3" y="14" width="7" height="7" rx="1"/>
          <rect x="14" y="14" width="7" height="7" rx="1"/>
        </svg>
        {{ host.replicaCount ?? 0 }} 副本
      </span>
      <button
        class="detail-button"
        type="button"
        :aria-label="`查看宿主机 ${host.hostname || host.ip || ''} 详情`"
        @click.stop="$emit('click', host)"
      >
        查看详情
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </button>
    </div>
  </article>
</template>

<style scoped>
.host-card {
  display: grid;
  gap: 0.8rem;
  min-width: 0;
  padding: 0.9rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.host-card:hover {
  border-color: color-mix(in srgb, var(--primary-color) 55%, var(--border-color));
  transform: translateY(-1px);
  box-shadow: 0 5px 16px color-mix(in srgb, var(--primary-color) 10%, transparent);
}

.host-card:focus-within {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.host-card-header {
  display: grid;
  gap: 0.3rem;
  min-width: 0;
}

.host-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  min-width: 0;
}

.host-title {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  min-width: 0;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-dot.ok {
  background: var(--success-color);
  box-shadow: 0 0 6px color-mix(in srgb, var(--success-color) 75%, transparent);
}

.status-dot.off {
  background: var(--danger-color);
}

.hostname {
  min-width: 0;
  padding: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 0.92rem;
  font-weight: 700;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: transparent;
  cursor: copy;
  transition: color 0.15s ease;
}

.hostname:hover:not(:disabled) {
  color: var(--primary-color);
}

.hostname:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 2px;
}

.hostname:disabled {
  cursor: default;
}

.role-badge {
  flex-shrink: 0;
  padding: 2px 7px;
  color: var(--text-secondary);
  font-size: 0.62rem;
  font-weight: 700;
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  border-radius: 5px;
}

.role-badge.manager {
  color: #fff;
  background: var(--primary-gradient);
  border-color: color-mix(in srgb, var(--primary-color) 70%, black);
  box-shadow: 0 2px 6px color-mix(in srgb, var(--primary-color) 25%, transparent);
}

.role-badge.worker {
  color: var(--success-color);
  background: color-mix(in srgb, var(--success-color) 14%, transparent);
  border-color: color-mix(in srgb, var(--success-color) 30%, var(--border-color));
}

.host-meta {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 0.2rem;
  min-width: 0;
  overflow-x: auto;
  color: var(--text-tertiary);
  font-family: 'Courier New', monospace;
  font-size: 0.66rem;
  scrollbar-width: none;
}

.host-meta::-webkit-scrollbar {
  display: none;
}

.meta-tag {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  min-width: 0;
  padding: 1px 4px;
  color: inherit;
  font: inherit;
  line-height: 1.25;
  white-space: nowrap;
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  border-radius: 3px;
}

button.meta-tag.ip {
  color: var(--primary-color);
  cursor: copy;
  transition: color 0.15s ease, background 0.15s ease, border-color 0.15s ease;
}

button.meta-tag.ip:hover:not(:disabled) {
  background: var(--primary-light);
  border-color: color-mix(in srgb, var(--primary-color) 45%, var(--border-color));
}

button.meta-tag.ip:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 1px;
}

button.meta-tag.ip:disabled {
  cursor: default;
  opacity: 0.65;
}

.host-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.35rem;
  padding: 0.65rem 0.3rem 0.55rem;
  background: var(--bg-secondary);
  border: 1px solid color-mix(in srgb, var(--border-color) 75%, transparent);
  border-radius: 9px;
}

.host-metrics.refresh-failed {
  display: grid;
  min-height: 77px;
  place-items: center;
  background: color-mix(in srgb, var(--danger-color) 8%, var(--bg-secondary));
  border-color: color-mix(in srgb, var(--danger-color) 38%, var(--border-color));
}

.metrics-refresh-alert {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  color: var(--danger-color);
  font-size: 0.72rem;
  font-weight: 700;
}

.metrics-alert-icon {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  color: #fff;
  font-size: 0.8rem;
  font-weight: 800;
  line-height: 1;
  background: var(--danger-color);
  border-radius: 50%;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--danger-color) 18%, transparent);
  animation: metrics-alert-pulse 0.8s ease-in-out infinite alternate;
}

@keyframes metrics-alert-pulse {
  from {
    opacity: 0.45;
    transform: scale(0.88);
  }

  to {
    opacity: 1;
    transform: scale(1.08);
  }
}

.host-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  color: var(--text-secondary);
  font-size: 0.7rem;
}

.replica-count,
.detail-button {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  white-space: nowrap;
}

.detail-button {
  padding: 0.2rem 0;
  color: var(--primary-color);
  font-size: inherit;
  font-weight: 600;
  background: transparent;
}

.detail-button:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 2px;
}

.detail-button svg {
  transition: transform 0.2s ease;
}

.host-card:hover .detail-button svg,
.host-card:focus-within .detail-button svg {
  transform: translateX(2px);
}

@container host-strip (max-height: 430px) {
  .host-card {
    gap: 0.5rem;
    padding: 0.65rem;
  }

  .host-card-header {
    gap: 0.2rem;
  }

  .hostname {
    font-size: 0.84rem;
  }

  .role-badge {
    padding: 1px 5px;
    font-size: 0.58rem;
  }

  .host-meta {
    gap: 0.15rem;
    font-size: 0.62rem;
  }

  .meta-tag {
    padding: 0 3px;
    border-radius: 2px;
  }

  .host-metrics {
    gap: 0.15rem;
    padding: 0.4rem 0.2rem 0.35rem;
  }

  .host-metrics.refresh-failed {
    min-height: 61px;
  }

  .host-metrics :deep(.metric-ring) {
    gap: 0.1rem;
  }

  .host-metrics :deep(.ring-visual) {
    width: 36px;
    height: 36px;
  }

  .host-metrics :deep(.ring-label) {
    font-size: 0.58rem;
  }

  .host-card-footer {
    font-size: 0.65rem;
  }
}

@container host-strip (max-height: 330px) {
  .host-card {
    gap: 0.35rem;
    padding: 0.5rem;
  }

  .host-metrics {
    padding-block: 0.25rem;
  }

  .host-metrics :deep(.ring-visual) {
    width: 32px;
    height: 32px;
  }

  .host-card-footer {
    min-height: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .host-card,
  .detail-button svg {
    transition: none;
  }

  .metrics-alert-icon {
    animation: none;
  }
}
</style>
