<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import HostSimpleCard from './HostSimpleCard.vue'
import LoadingOverlay from '@/components/LoadingOverlay.vue'
import { listHosts } from '@/api/monitor'
import { useMinimumVisible } from '@/composables/useMinimumVisible'

const props = defineProps({
  environmentId: {
    type: [Number, String, null],
    default: null
  }
})

const emit = defineEmits(['host-click'])

const hosts = ref([])
const collapsed = ref(false)
const loading = ref(false)
const blockingLoading = ref(false)
const refreshFailed = ref(false)
const loadingVisible = useMinimumVisible(loading, 500)
const blockingLoadingVisible = useMinimumVisible(blockingLoading, 500)
const REFRESH_INTERVAL_MS = 3000
let timer = null
let refreshing = false
let refreshQueued = false
let refreshQueuedBlocking = false

function clearMetrics(host) {
  return {
    ...host,
    cpuPercent: null,
    memPercent: null,
    diskPercent: null,
    memUsed: null,
    diskUsed: null,
    load1: null,
    load5: null,
    load15: null,
    collectedTime: null
  }
}

async function refresh({ blocking = false } = {}) {
  if (refreshing) {
    refreshQueued = true
    refreshQueuedBlocking ||= blocking
    return
  }

  const environmentId = props.environmentId
  if (!environmentId) {
    hosts.value = []
    refreshFailed.value = false
    return
  }

  refreshing = true
  loading.value = true
  blockingLoading.value = blocking
  try {
    const nextHosts = await listHosts(environmentId)
    if (environmentId !== props.environmentId) {
      refreshQueued = true
      return
    }
    hosts.value = nextHosts
    refreshFailed.value = false
  } catch (e) {
    if (environmentId === props.environmentId) {
      hosts.value = hosts.value.map(clearMetrics)
      refreshFailed.value = true
      console.warn('[HostMetricStrip] refresh failed', e)
    }
  } finally {
    refreshing = false
    loading.value = false
    blockingLoading.value = false
    if (refreshQueued) {
      const queuedBlocking = refreshQueuedBlocking
      refreshQueued = false
      refreshQueuedBlocking = false
      void refresh({ blocking: queuedBlocking })
    }
  }
}

function startTimer() {
  stopTimer()
  timer = setInterval(refresh, REFRESH_INTERVAL_MS)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const summary = computed(() => {
  const total = hosts.value.length
  const ok = hosts.value.filter(h => (h.swarmStatus || '').toLowerCase() === 'ready').length
  const warn = hosts.value.filter(h => (h.cpuPercent >= 70 || h.memPercent >= 70) && h.cpuPercent < 90 && h.memPercent < 90).length
  const danger = hosts.value.filter(h => h.cpuPercent >= 90 || h.memPercent >= 90).length
  return { total, ok, warn, danger }
})

function toggle() {
  collapsed.value = !collapsed.value
}

watch(
  () => props.environmentId,
  () => refresh({ blocking: true }),
  { immediate: false }
)

onMounted(() => {
  refresh({ blocking: true })
  startTimer()
})

onBeforeUnmount(() => stopTimer())
</script>

<template>
  <section class="host-strip" :class="{ collapsed }" :aria-busy="loading">
    <header class="strip-header">
      <div class="header-main">
        <div class="heading">
          <span class="heading-icon" aria-hidden="true">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="2" y="3" width="20" height="14" rx="2"/>
              <line x1="8" y1="21" x2="16" y2="21"/>
              <line x1="12" y1="17" x2="12" y2="21"/>
            </svg>
          </span>
          <span class="title">宿主机监控</span>
          <div class="status-summary" aria-label="宿主机状态摘要">
            <span class="chip total">{{ summary.total }} 台</span>
            <span class="chip ok">
              <span class="dot"></span>正常 {{ summary.ok }}
            </span>
            <span class="chip warn" v-if="summary.warn > 0">
              <span class="dot"></span>预警 {{ summary.warn }}
            </span>
            <span class="chip danger" v-if="summary.danger > 0">
              <span class="dot"></span>告警 {{ summary.danger }}
            </span>
          </div>
        </div>
        <div class="header-actions">
          <span
            v-if="loadingVisible && !blockingLoadingVisible"
            class="header-refreshing"
            role="status"
            aria-label="宿主机数据刷新中"
            title="宿主机数据刷新中"
          >
            <span class="header-refresh-spinner" aria-hidden="true"></span>
          </span>
          <button
            class="btn-toggle"
            type="button"
            :aria-expanded="!collapsed"
            :title="collapsed ? '展开宿主机监控' : '折叠宿主机监控'"
            @click="toggle"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
                 :style="{ transform: collapsed ? 'rotate(-90deg)' : 'rotate(0)' }" class="chevron" aria-hidden="true">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>
        </div>
      </div>
    </header>

    <div v-show="!collapsed" class="strip-body">
      <div v-if="loading && hosts.length === 0" class="panel-state loading-state">
        正在加载宿主机…
      </div>
      <div v-else-if="hosts.length === 0" class="panel-state">
        当前环境暂无宿主机数据
      </div>
      <div v-else class="cards-scroll">
        <HostSimpleCard
          v-for="h in hosts"
          :key="h.id"
          :host="h"
          :refresh-failed="refreshFailed"
          @click="(host) => emit('host-click', host)"
        />
      </div>
    </div>

    <LoadingOverlay v-if="blockingLoadingVisible" label="宿主机数据加载中…" />
  </section>
</template>

<style scoped>
.host-strip {
  position: relative;
  display: flex;
  flex-direction: column;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.strip-header {
  padding: 0.85rem 0.9rem;
  user-select: none;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-primary);
}

.host-strip.collapsed .strip-header {
  border-bottom: none;
}

.header-main,
.heading {
  display: flex;
  align-items: center;
}

.header-main {
  justify-content: space-between;
  gap: 0.75rem;
  min-height: 30px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.header-refreshing {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
}

.header-refresh-spinner {
  width: 15px;
  height: 15px;
  border: 2px solid color-mix(in srgb, var(--primary-color) 24%, transparent);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: header-refresh-spin 0.7s linear infinite;
}

@keyframes header-refresh-spin {
  to { transform: rotate(360deg); }
}

.heading {
  gap: 0.45rem;
  min-width: 0;
  color: var(--text-primary);
}

.heading-icon {
  display: grid;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  place-items: center;
  color: var(--primary-color);
  background: var(--primary-light);
  border-radius: 8px;
}

.title {
  overflow: hidden;
  font-size: 0.88rem;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-summary {
  display: flex;
  min-width: 0;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.chip {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  padding: 2px 7px;
  border-radius: 10px;
  font-size: 0.68rem;
  font-weight: 600;
}

.chip.total {
  color: var(--text-tertiary);
  font-weight: 500;
  background: var(--bg-hover);
}

.chip .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.chip.ok {
  background: color-mix(in srgb, var(--success-color) 12%, transparent);
  color: var(--success-color);
}

.chip.ok .dot {
  background: var(--success-color);
}

.chip.warn {
  background: color-mix(in srgb, var(--warning-color) 15%, transparent);
  color: var(--warning-color);
}

.chip.warn .dot {
  background: var(--warning-color);
}

.chip.danger {
  background: color-mix(in srgb, var(--danger-color) 15%, transparent);
  color: var(--danger-color);
}

.chip.danger .dot {
  background: var(--danger-color);
}

.btn-toggle {
  display: grid;
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  padding: 0;
  place-items: center;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}

.btn-toggle:hover {
  background: var(--bg-hover);
  color: var(--primary-color);
}

.btn-toggle:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 2px;
}

.chevron {
  transition: transform 0.25s;
}

.strip-body {
  display: flex;
  min-height: 0;
  padding: 0.7rem;
  overflow: hidden;
}

.cards-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.65rem;
  min-height: 0;
  padding: 0.15rem;
  overflow-y: auto;
  scrollbar-color: var(--border-hover) transparent;
  scrollbar-width: thin;
}

.panel-state {
  width: 100%;
  padding: 2rem 0.75rem;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 0.85rem;
}

.loading-state {
  color: var(--text-secondary);
}

@container host-strip (max-height: 430px) {
  .strip-body {
    padding: 0.45rem;
  }

  .cards-scroll {
    gap: 0.4rem;
    padding: 0.05rem;
  }
}

@media (min-width: 1101px) {
  .btn-toggle {
    display: none;
  }
}

@media (max-width: 1100px) {
  .strip-body {
    overflow: visible;
  }

  .cards-scroll {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
    overflow: visible;
  }
}

@media (max-width: 768px) {
  .cards-scroll {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .host-strip,
  .chevron {
    transition: none;
  }

  .header-refresh-spinner {
    animation: none;
  }
}
</style>
