<script setup>
import { ref, watch } from 'vue'
import { fetchContext } from '@/api/logs'
import { useBodyScrollLock } from '@/composables/useBodyScrollLock'

// anchor 为日志行对象（需 containerName + tsNanos）
const props = defineProps({
  visible: { type: Boolean, default: false },
  envId: { type: Number, default: null },
  anchor: { type: Object, default: null }
})
useBodyScrollLock(() => props.visible)
const emit = defineEmits(['update:visible'])

const isLoading = ref(false)
const items = ref([])
const anchorIndex = ref(-1)
const contextLimit = ref(50)
const errorMsg = ref('')

const load = async () => {
  if (!props.envId || !props.anchor?.containerName || !props.anchor?.tsNanos) {
    errorMsg.value = '该行缺少 container 或时间戳，无法查询上下文'
    return
  }
  isLoading.value = true
  errorMsg.value = ''
  try {
    const data = await fetchContext(props.envId, props.anchor.containerName, props.anchor.tsNanos, contextLimit.value)
    items.value = (data && data.items) || []
    anchorIndex.value = data ? data.anchorIndex : -1
  } catch (e) {
    items.value = []
    anchorIndex.value = -1
    errorMsg.value = '上下文查询失败'
  } finally {
    isLoading.value = false
  }
}

// 向两端扩展：limit 翻倍后重拉（上限 500，与后端一致）
const loadMore = async () => {
  if (contextLimit.value >= 500) return
  contextLimit.value = Math.min(500, contextLimit.value * 2)
  await load()
}

watch(() => props.visible, (v) => {
  if (v) {
    items.value = []
    anchorIndex.value = -1
    contextLimit.value = 50
    load()
  }
})

const handleClose = () => emit('update:visible', false)

const formatTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso); const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
const levelClass = (lv) => ({
  'lvl-DEBUG': lv === 'DEBUG', 'lvl-INFO': lv === 'INFO',
  'lvl-WARN':  lv === 'WARN',  'lvl-ERROR': lv === 'ERROR'
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
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/>
                  <line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>
                </svg>
              </div>
              <div>
                <h3>日志上下文</h3>
                <p class="header-sub">
                  <span class="mono">{{ anchor?.containerName }}</span>
                  · 锚点前后各 {{ contextLimit }} 行
                </p>
              </div>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <div class="ctx-toolbar">
            <button class="mini-btn" :disabled="isLoading || contextLimit >= 500" @click="loadMore">
              扩展范围（±{{ Math.min(500, contextLimit * 2) }} 行）
            </button>
            <span v-if="errorMsg" class="err-text">{{ errorMsg }}</span>
          </div>

          <div class="ctx-body">
            <div v-if="isLoading && !items.length" class="state-block">
              <div class="spinner"></div><p>查询中...</p>
            </div>
            <div v-else-if="!items.length" class="state-block"><p>无上下文数据</p></div>
            <div v-else class="console-view">
              <div v-for="(row, i) in items" :key="`${row.tsNanos}:${i}`"
                class="console-line" :class="{ 'is-anchor': i === anchorIndex }"><!--
             --><span class="c-time">{{ formatTime(row.timestamp) }}</span> <span class="c-lv" :class="levelClass(row.level)">{{ (row.level || '').padEnd(5, ' ') }}</span> <span class="c-thread">[{{ row.thread }}]</span> <span class="c-logger">{{ row.logger }}</span> <span class="c-msg">{{ row.bizMessage || row.message }}</span>
              </div>
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
  display: flex; align-items: center; justify-content: center; z-index: 10001;
}
.dialog-container {
  background: var(--bg-secondary);
  width: min(1100px, 92vw); height: min(760px, 88vh);
  border-radius: 12px;
  display: flex; flex-direction: column; overflow: hidden;
  box-shadow: 0 20px 60px rgba(0,0,0,.3);
}
.dialog-header {
  padding: 0.75rem 1.25rem;
  background: var(--primary-gradient);
  display: flex; align-items: center; justify-content: space-between;
  flex-shrink: 0; color: white;
}
.header-content { display: flex; align-items: center; gap: .85rem; }
.header-icon {
  width: 30px; height: 30px; border-radius: 8px;
  background: rgba(255,255,255,0.2);
  display: flex; align-items: center; justify-content: center;
}
.dialog-header h3 { margin: 0; font-size: 1.05rem; font-weight: 600; }
.header-sub { margin: .12rem 0 0; font-size: .75rem; opacity: .9; }
.btn-close {
  width: 32px; height: 32px; border-radius: 8px;
  background: rgba(255,255,255,0.2); border: none; color: white;
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.btn-close:hover { background: rgba(255,255,255,0.3); }
.mono { font-family: 'Consolas', 'Monaco', monospace; }

.ctx-toolbar {
  display: flex; align-items: center; gap: .75rem;
  padding: .5rem 1.25rem; border-bottom: 1px solid var(--border-color);
  background: var(--bg-primary); flex-shrink: 0;
}
.mini-btn {
  height: 28px; padding: 0 .75rem; border-radius: 6px;
  border: 1px solid var(--border-color); background: var(--bg-secondary); color: var(--text-primary);
  font-size: .78rem; cursor: pointer;
}
.mini-btn:hover:not(:disabled) { border-color: var(--primary-color); color: var(--primary-color); }
.mini-btn:disabled { opacity: .5; cursor: not-allowed; }
.err-text { font-size: .78rem; color: #ef4444; }

.ctx-body { flex: 1; overflow: auto; background: #1e1e1e; }
.ctx-body::-webkit-scrollbar { width: 8px; }
.ctx-body::-webkit-scrollbar-track { background: #2d2d2d; }
.ctx-body::-webkit-scrollbar-thumb { background: #555; border-radius: 4px; }

.state-block {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 4rem; color: #888;
}
.spinner {
  width: 32px; height: 32px;
  border: 3px solid #444; border-top-color: var(--primary-color);
  border-radius: 50%; animation: spin .8s linear infinite; margin-bottom: 1rem;
}
@keyframes spin { to { transform: rotate(360deg); } }

.console-view {
  padding: 1rem 1.25rem; color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 0.82rem; line-height: 1.6;
}
.console-line { display: block; min-height: 22px; line-height: 22px; white-space: nowrap; }
.console-line:hover { background: rgba(255,255,255,0.04); }
.console-line.is-anchor {
  background: rgba(102,126,234,0.22);
  outline: 1px solid rgba(102,126,234,0.55);
  border-radius: 2px;
}
.c-time   { color: #858585; }
.c-lv     { font-weight: 700; white-space: pre; }
.c-lv.lvl-ERROR { color: #f48771; }
.c-lv.lvl-WARN  { color: #ffcc66; }
.c-lv.lvl-INFO  { color: #89d185; }
.c-lv.lvl-DEBUG { color: #888; }
.c-thread { color: #c586c0; }
.c-logger { color: #9cdcfe; }
.c-msg    { color: #d4d4d4; }

.dialog-fade-enter-active, .dialog-fade-leave-active { transition: opacity .25s; }
.dialog-fade-enter-from, .dialog-fade-leave-to { opacity: 0; }
</style>
