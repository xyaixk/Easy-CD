<script setup>
import { ref, watch } from 'vue'
import { fetchTrace } from '@/api/logs'

const props = defineProps({
  visible: { type: Boolean, default: false },
  envId: { type: Number, default: null },
  traceId: { type: String, default: '' },
  // 锚点行时间（ISO），用于计算默认查询窗口 ±30 分钟
  anchorTimestamp: { type: String, default: '' }
})
const emit = defineEmits(['update:visible'])

const isLoading = ref(false)
const items = ref([])
const errorMsg = ref('')

const fmtParam = (d) => {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const load = async () => {
  if (!props.envId || !props.traceId) return
  isLoading.value = true
  errorMsg.value = ''
  try {
    const anchor = props.anchorTimestamp ? new Date(props.anchorTimestamp) : new Date()
    const from = fmtParam(new Date(anchor.getTime() - 30 * 60 * 1000))
    const to = fmtParam(new Date(anchor.getTime() + 30 * 60 * 1000))
    const data = await fetchTrace(props.envId, props.traceId, from, to)
    items.value = Array.isArray(data) ? data : []
  } catch (e) {
    items.value = []
    errorMsg.value = 'trace 查询失败'
  } finally {
    isLoading.value = false
  }
}

watch(() => [props.visible, props.traceId], ([v]) => {
  if (v) { items.value = []; load() }
})

const handleClose = () => emit('update:visible', false)

// 服务名稳定染色（hash 取色盘）
const SVC_COLORS = ['#569cd6', '#4ec9b0', '#c586c0', '#dcdcaa', '#9cdcfe', '#ce9178', '#b5cea8', '#d16969']
const svcColor = (name) => {
  if (!name) return SVC_COLORS[0]
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return SVC_COLORS[h % SVC_COLORS.length]
}

const formatTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso); const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`
}
const levelClass = (lv) => ({
  'lvl-DEBUG': lv === 'DEBUG', 'lvl-INFO': lv === 'INFO',
  'lvl-WARN':  lv === 'WARN',  'lvl-ERROR': lv === 'ERROR'
})
</script>

<template>
  <Teleport to="body">
    <Transition name="panel-slide">
      <div v-if="visible" class="trace-panel">
        <div class="panel-header">
          <div class="ph-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="3"/><path d="M12 1v6m0 10v6M1 12h6m10 0h6"/>
            </svg>
            <span>Trace 时间线</span>
          </div>
          <button class="btn-close" @click="handleClose">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="panel-sub">
          <span class="trace-id mono">{{ traceId }}</span>
          <span class="hit-count">{{ items.length }} 条命中（锚点 ±30 分钟）</span>
        </div>

        <div class="panel-body">
          <div v-if="isLoading" class="state-block">
            <div class="spinner"></div><p>查询中...</p>
          </div>
          <div v-else-if="errorMsg" class="state-block"><p>{{ errorMsg }}</p></div>
          <div v-else-if="!items.length" class="state-block"><p>无该 trace 的日志</p></div>
          <div v-else class="timeline">
            <div v-for="(row, i) in items" :key="`${row.tsNanos}:${i}`" class="tl-item">
              <div class="tl-meta">
                <span class="tl-time mono">{{ formatTime(row.timestamp) }}</span>
                <span class="tl-svc" :style="{ color: svcColor(row.service), borderColor: svcColor(row.service) }">{{ row.service }}</span>
                <span class="tl-lv" :class="levelClass(row.level)">{{ row.level }}</span>
              </div>
              <div class="tl-msg mono">{{ row.bizMessage || row.message }}</div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.trace-panel {
  position: fixed; top: 0; right: 0; bottom: 0; z-index: 10001;
  width: min(560px, 92vw);
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  box-shadow: -8px 0 32px rgba(0,0,0,.18);
  display: flex; flex-direction: column;
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: .7rem 1.1rem;
  background: var(--primary-gradient); color: white; flex-shrink: 0;
}
.ph-title { display: flex; align-items: center; gap: .5rem; font-weight: 600; font-size: .95rem; }
.btn-close {
  width: 28px; height: 28px; border-radius: 6px;
  background: rgba(255,255,255,0.2); border: none; color: white;
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.btn-close:hover { background: rgba(255,255,255,0.3); }

.panel-sub {
  display: flex; align-items: center; gap: .75rem; flex-wrap: wrap;
  padding: .5rem 1.1rem; border-bottom: 1px solid var(--border-color);
  background: var(--bg-primary); flex-shrink: 0;
}
.trace-id { font-size: .75rem; color: var(--primary-color); word-break: break-all; }
.hit-count { font-size: .72rem; color: var(--text-tertiary); white-space: nowrap; }
.mono { font-family: 'Consolas', 'Monaco', monospace; }

.panel-body { flex: 1; overflow: auto; }
.state-block {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 3rem 1rem; color: var(--text-secondary);
}
.spinner {
  width: 30px; height: 30px;
  border: 3px solid var(--border-color); border-top-color: var(--primary-color);
  border-radius: 50%; animation: spin .8s linear infinite; margin-bottom: .85rem;
}
@keyframes spin { to { transform: rotate(360deg); } }

.timeline { padding: .75rem 1.1rem 1.25rem; display: flex; flex-direction: column; }
.tl-item {
  position: relative; padding: .45rem 0 .45rem 1rem;
  border-left: 2px solid var(--border-color);
}
.tl-item::before {
  content: ''; position: absolute; left: -5px; top: .85rem;
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--primary-color);
}
.tl-meta { display: flex; align-items: center; gap: .5rem; flex-wrap: wrap; }
.tl-time { font-size: .72rem; color: var(--text-tertiary); }
.tl-svc {
  font-size: .72rem; font-weight: 700;
  padding: .1rem .45rem; border: 1px solid; border-radius: 999px;
}
.tl-lv { font-size: .7rem; font-weight: 700; }
.tl-lv.lvl-ERROR { color: #ef4444; }
.tl-lv.lvl-WARN  { color: #f59e0b; }
.tl-lv.lvl-INFO  { color: #10b981; }
.tl-lv.lvl-DEBUG { color: #6b7280; }
.tl-msg {
  margin-top: .2rem; font-size: .76rem; line-height: 1.5;
  color: var(--text-primary); word-break: break-all; white-space: pre-wrap;
}

.panel-slide-enter-active, .panel-slide-leave-active { transition: transform .25s ease; }
.panel-slide-enter-from, .panel-slide-leave-to { transform: translateX(100%); }
</style>
