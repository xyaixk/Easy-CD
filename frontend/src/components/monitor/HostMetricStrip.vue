<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import HostSimpleCard from './HostSimpleCard.vue'
import { listHosts } from '@/api/monitor'

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
let timer = null

async function refresh() {
  if (!props.environmentId) {
    hosts.value = []
    return
  }
  loading.value = true
  try {
    hosts.value = await listHosts(props.environmentId)
  } catch (e) {
    console.warn('[HostMetricStrip] refresh failed', e)
  } finally {
    loading.value = false
  }
}

function startTimer() {
  stopTimer()
  timer = setInterval(refresh, 10000)
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
  () => refresh(),
  { immediate: false }
)

onMounted(() => {
  refresh()
  startTimer()
})

onBeforeUnmount(() => stopTimer())
</script>

<template>
  <section class="host-strip" :class="{ collapsed }">
    <header class="strip-header" @click="toggle">
      <div class="left">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2"/>
          <line x1="8" y1="21" x2="16" y2="21"/>
          <line x1="12" y1="17" x2="12" y2="21"/>
        </svg>
        <span class="title">宿主机监控</span>
        <span class="count">{{ summary.total }} 台</span>
      </div>
      <div class="right">
        <span class="chip ok">
          <span class="dot"></span>正常 {{ summary.ok }}
        </span>
        <span class="chip warn" v-if="summary.warn > 0">
          <span class="dot"></span>预警 {{ summary.warn }}
        </span>
        <span class="chip danger" v-if="summary.danger > 0">
          <span class="dot"></span>告警 {{ summary.danger }}
        </span>
        <button class="btn-toggle" @click.stop="toggle" :title="collapsed ? '展开' : '折叠'">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
               :style="{ transform: collapsed ? 'rotate(-90deg)' : 'rotate(0)' }" class="chevron">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>
      </div>
    </header>

    <div v-show="!collapsed" class="strip-body">
      <div v-if="hosts.length === 0 && !loading" class="empty">
        暂无宿主机数据
      </div>
      <div v-else class="cards-scroll">
        <HostSimpleCard
          v-for="h in hosts"
          :key="h.id"
          :host="h"
          @click="(host) => emit('host-click', host)"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.host-strip {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  margin-bottom: 1.5rem;
  overflow: hidden;
  transition: all 0.25s;
}

.strip-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.65rem 1rem;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-primary);
}

.host-strip.collapsed .strip-header {
  border-bottom: none;
}

.left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-primary);
}

.title {
  font-weight: 600;
  font-size: 0.9rem;
}

.count {
  font-size: 0.75rem;
  color: var(--text-tertiary);
  padding: 1px 6px;
  background: var(--bg-hover);
  border-radius: 10px;
}

.right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.chip {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  padding: 3px 8px;
  border-radius: 10px;
  font-size: 0.72rem;
  font-weight: 500;
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
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.btn-toggle:hover {
  background: var(--bg-hover);
  color: var(--primary-color);
}

.chevron {
  transition: transform 0.25s;
}

.strip-body {
  padding: 0.9rem;
}

.cards-scroll {
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
}

.empty {
  padding: 2rem;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 0.85rem;
}
</style>
