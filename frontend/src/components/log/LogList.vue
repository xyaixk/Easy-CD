<script setup>
import { ref, computed, watch, nextTick } from 'vue'

// logs 为时间正序数组（旧上新下），行带唯一 key
const props = defineProps({
  logs: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingOlder: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  live: { type: Boolean, default: false }
})
const emit = defineEmits(['loadOlder', 'showContext', 'showTrace'])

const bodyRef = ref(null)
const followTail = ref(true)

// ============ 虚拟列表（定高行） ============
const VLIST_THRESHOLD = 500
const ROW_H = 22
const LOADER_H = 36
const scrollTop = ref(0)
const viewportH = ref(600)
const useVirtual = computed(() => props.logs.length >= VLIST_THRESHOLD)
// 顶部"加载更早"条占用的流内高度，需从滚动偏移中扣除
const loaderH = computed(() => (!props.live && props.hasMore) ? LOADER_H : 0)
const vRange = computed(() => {
  const n = props.logs.length
  if (!useVirtual.value) return { start: 0, end: n }
  const buf = 20
  const effTop = Math.max(0, scrollTop.value - loaderH.value)
  const start = Math.max(0, Math.floor(effTop / ROW_H) - buf)
  const end = Math.min(n, Math.ceil((effTop + viewportH.value) / ROW_H) + buf)
  return { start, end }
})
const visibleLogs = computed(() => useVirtual.value ? props.logs.slice(vRange.value.start, vRange.value.end) : props.logs)
const topPad = computed(() => useVirtual.value ? vRange.value.start * ROW_H : 0)
const bottomPad = computed(() => useVirtual.value ? Math.max(0, (props.logs.length - vRange.value.end)) * ROW_H : 0)

const onScroll = (e) => {
  const el = e.target
  scrollTop.value = el.scrollTop
  followTail.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 40
  // 滚动到顶部附近时自动加载更早
  if (el.scrollTop < 60 && props.hasMore && !props.loadingOlder && !props.live && props.logs.length) {
    emit('loadOlder')
  }
}

// prepend 更早的日志后由父组件调用：保持视口不跳动
const compensatePrepend = async (addedCount) => {
  await nextTick()
  if (bodyRef.value) {
    bodyRef.value.scrollTop += addedCount * ROW_H
    scrollTop.value = bodyRef.value.scrollTop
  }
}
const scrollToBottom = async () => {
  await nextTick()
  if (bodyRef.value) {
    bodyRef.value.scrollTop = bodyRef.value.scrollHeight
    scrollTop.value = bodyRef.value.scrollTop
  }
}
defineExpose({ compensatePrepend, scrollToBottom })

watch(() => props.logs, async () => {
  await nextTick()
  if (bodyRef.value) {
    // 实时模式贴底跟随
    if (props.live && followTail.value) {
      bodyRef.value.scrollTop = bodyRef.value.scrollHeight
    }
    scrollTop.value = bodyRef.value.scrollTop
    viewportH.value = bodyRef.value.clientHeight || 600
  }
})

// ============ 行详情（底部面板，不影响定高虚拟滚动） ============
const selectedRow = ref(null)
const selectRow = (row) => {
  selectedRow.value = selectedRow.value?.key === row.key ? null : row
}

const formatTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso); const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
const shortTrace = (t) => (t ? t.slice(0, 8) + '…' : '-')
const levelClass = (lv) => ({
  'lvl-DEBUG': lv === 'DEBUG', 'lvl-INFO': lv === 'INFO',
  'lvl-WARN':  lv === 'WARN',  'lvl-ERROR': lv === 'ERROR'
})
</script>

<template>
  <div class="log-list">
    <div class="result-body" ref="bodyRef" @scroll="onScroll">
      <!-- 顶部：加载更早 -->
      <div v-if="!live && hasMore" class="load-older" :style="{ height: '36px' }">
        <button class="older-btn" :disabled="loadingOlder" @click="emit('loadOlder')">
          <span v-if="loadingOlder" class="mini-spinner"></span>
          {{ loadingOlder ? '加载中...' : '加载更早的日志' }}
        </button>
      </div>

      <div v-if="loading && !logs.length" class="state-block">
        <div class="spinner"></div><p>查询中...</p>
      </div>
      <div v-else-if="!logs.length" class="state-block">
        <p>{{ live ? '等待新日志...' : '无匹配的日志' }}</p>
      </div>

      <!-- 控制台视图 -->
      <div v-else class="console-view">
        <div v-if="useVirtual && topPad > 0" :style="{ height: topPad + 'px' }"></div>
        <div v-for="row in visibleLogs" :key="row.key" class="console-line"
          :class="{ selected: selectedRow?.key === row.key }" @click="selectRow(row)"><!--
       --><span class="c-time">{{ formatTime(row.timestamp) }}</span> <span class="c-lv" :class="levelClass(row.level)">{{ (row.level || '').padEnd(5, ' ') }}</span> <span class="c-svc">{{ row.service }}</span> <span class="c-thread">[{{ row.thread }}]</span> <span class="c-logger">{{ row.logger }}</span> <span class="c-trace"><a v-if="row.traceId" href="#" @click.stop.prevent="emit('showTrace', row)">({{ shortTrace(row.traceId) }})</a><span v-else>(-)</span></span> <span class="c-msg">{{ row.bizMessage || row.message }}</span>
        </div>
        <div v-if="useVirtual && bottomPad > 0" :style="{ height: bottomPad + 'px' }"></div>
      </div>
    </div>

    <!-- 行详情面板 -->
    <div v-if="selectedRow" class="detail-panel">
      <div class="dp-head">
        <span class="dp-title">日志详情</span>
        <div class="dp-actions">
          <button class="mini-btn" @click="emit('showContext', selectedRow)">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
            查看上下文
          </button>
          <button v-if="selectedRow.traceId" class="mini-btn" @click="emit('showTrace', selectedRow)">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M12 1v6m0 10v6M1 12h6m10 0h6"/></svg>
            查 trace
          </button>
          <button class="mini-btn" @click="selectedRow = null">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            关闭
          </button>
        </div>
      </div>
      <div class="detail-grid">
        <div><span class="k">time</span><span class="v mono">{{ formatTime(selectedRow.timestamp) }}</span></div>
        <div><span class="k">service</span><span class="v mono">{{ selectedRow.service }}</span></div>
        <div><span class="k">level</span><span class="v mono">{{ selectedRow.level }}</span></div>
        <div><span class="k">logger</span><span class="v mono">{{ selectedRow.logger }}</span></div>
        <div><span class="k">container</span><span class="v mono">{{ selectedRow.containerName }}</span></div>
        <div><span class="k">image_name</span><span class="v mono">{{ selectedRow.imageName }}</span></div>
        <div><span class="k">source_host</span><span class="v mono">{{ selectedRow.sourceHost }}</span></div>
        <div><span class="k">thread</span><span class="v mono">{{ selectedRow.thread }}</span></div>
        <div><span class="k">traceId</span><span class="v mono">{{ selectedRow.traceId || '-' }}</span></div>
      </div>
      <pre class="detail-message">{{ selectedRow.message }}</pre>
    </div>

    <!-- 底部状态栏 -->
    <div class="list-footer">
      <span v-if="live" class="ft-live">
        <span class="live-dot"></span>实时推送中 · 已接收 {{ logs.length }} 条
      </span>
      <span v-else class="ft-info">共 {{ logs.length }} 条{{ hasMore ? '（可向上加载更早）' : '' }}</span>
      <div class="grow"></div>
    </div>
  </div>
</template>

<style scoped>
.log-list { flex: 1; display: flex; flex-direction: column; min-height: 0; }

.result-body { flex: 1; overflow: auto; background: #1e1e1e; }
.result-body::-webkit-scrollbar { width: 8px; }
.result-body::-webkit-scrollbar-track { background: #2d2d2d; }
.result-body::-webkit-scrollbar-thumb { background: #555; border-radius: 4px; }
.result-body::-webkit-scrollbar-thumb:hover { background: #666; }

.load-older { display: flex; align-items: center; justify-content: center; }
.older-btn {
  height: 26px; padding: 0 1rem; border-radius: 999px;
  border: 1px dashed #555; background: #2d2d2d; color: #aaa;
  font-size: .75rem; cursor: pointer; display: inline-flex; align-items: center; gap: .4rem;
}
.older-btn:hover:not(:disabled) { border-color: var(--primary-color); color: var(--primary-color); border-style: solid; }
.older-btn:disabled { opacity: .6; cursor: default; }
.mini-spinner {
  width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid #555; border-top-color: var(--primary-color);
  animation: spin .8s linear infinite;
}

.state-block {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 4rem; color: #888;
}
.spinner {
  width: 36px; height: 36px;
  border: 3px solid #444; border-top-color: var(--primary-color);
  border-radius: 50%; animation: spin .8s linear infinite; margin-bottom: 1rem;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Console */
.console-view {
  padding: .5rem 1.5rem 1rem; color: #d4d4d4;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 0.875rem; line-height: 1.6;
}
.console-line {
  display: block;
  height: 22px; line-height: 22px;
  white-space: nowrap; cursor: pointer;
}
.console-line:hover { background: rgba(255,255,255,0.05); }
.console-line.selected { background: rgba(102,126,234,0.22); border-radius: 2px; }
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

/* 行详情面板（列表与底栏之间） */
.detail-panel {
  flex-shrink: 0; max-height: 42%;
  overflow: auto;
  padding: .65rem 1.5rem .85rem;
  background: var(--bg-primary);
  border-top: 1px solid var(--border-color);
}
.dp-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: .5rem; }
.dp-title { font-size: .82rem; font-weight: 700; color: var(--text-primary); }
.dp-actions { display: flex; gap: .5rem; }
.mini-btn {
  height: 26px; padding: 0 .7rem; border-radius: 6px;
  border: 1px solid var(--border-color); background: var(--bg-secondary); color: var(--text-primary);
  font-size: .76rem; cursor: pointer; display: inline-flex; align-items: center; gap: .35rem;
}
.mini-btn:hover { border-color: var(--primary-color); color: var(--primary-color); }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: .35rem .75rem; margin-bottom: .6rem; }
.detail-grid > div { display: flex; gap: .5rem; }
.detail-grid .k { color: var(--text-tertiary); font-size: .72rem; min-width: 90px; }
.detail-grid .v { color: var(--text-primary); font-size: .78rem; word-break: break-all; }
.mono { font-family: 'Consolas', 'Monaco', monospace; }
.detail-message {
  margin: 0; padding: .6rem .75rem; background: #1e1e1e; color: #d4d4d4;
  border-radius: 6px; font-family: 'Consolas', 'Monaco', monospace;
  font-size: .76rem; line-height: 1.55; white-space: pre-wrap; word-break: break-all;
}

/* 底部状态栏 */
.list-footer {
  display: flex; align-items: center; gap: .55rem;
  padding: .45rem 1.5rem; border-top: 1px solid var(--border-color);
  background: var(--bg-primary); flex-shrink: 0; font-size: .8rem;
}
.ft-info { color: var(--text-secondary); }
.ft-live { color: #10b981; font-weight: 600; display: inline-flex; align-items: center; gap: .4rem; }
.live-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #10b981;
  animation: live-pulse 1.2s ease-in-out infinite;
}
@keyframes live-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(16,185,129,.5); }
  50% { box-shadow: 0 0 0 4px rgba(16,185,129,0); }
}
.grow { flex: 1; }
</style>
