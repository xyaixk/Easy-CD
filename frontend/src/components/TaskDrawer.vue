<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import toast from '@/utils/toast'
import { listTasks, getTask } from '@/api/task'
import {
  createTaskCompletionTracker,
  getTaskStatusLabel,
  getTaskTypeLabel,
  isActiveTask
} from '@/utils/deployTask'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  environmentId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits([
  'update:visible',
  'task-finished',
  'update:activeCount',
  'update:activeTasks'
])

// 任务列表：首页（轮询刷新）+ 翻页累积的更早任务（游标 beforeId）
const tasks = ref([])
const olderTasks = ref([])
const loadingOlder = ref(false)
const hasMore = ref(false)
const PAGE_SIZE = 50

// 渲染用合并列表：轮询可能把旧首页的任务顶下来，按 id 去重避免重复渲染
const allTasks = computed(() => {
  const firstPageIds = new Set(tasks.value.map(t => t.id))
  return [...tasks.value, ...olderTasks.value.filter(t => !firstPageIds.has(t.id))]
})

// 展开的任务ID及其完整日志
const expandedId = ref(null)
const expandedLog = ref('')
const logRef = ref(null)

const completionTracker = createTaskCompletionTracker()

// 轮询
let pollTimer = null
let disposed = false
const ACTIVE_POLL_INTERVAL = 2000
const IDLE_POLL_INTERVAL = 10000

const activeTasks = computed(() => tasks.value.filter(isActiveTask))

const activeCount = computed(() => {
  return activeTasks.value.length
})

watch(activeTasks, (currentTasks) => {
  emit('update:activeCount', currentTasks.length)
  emit('update:activeTasks', currentTasks)
}, { immediate: true })

// 耗时格式化
const formatDuration = (ms) => {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m${seconds % 60}s`
}

// 拉取任务列表（首页，轮询复用；翻页数据在 olderTasks 中不受影响）
const fetchTasks = async () => {
  const environmentId = props.environmentId
  if (!environmentId) {
    tasks.value = []
    olderTasks.value = []
    hasMore.value = false
    return
  }
  try {
    const data = await listTasks(environmentId, PAGE_SIZE)
    if (props.environmentId !== environmentId) return

    detectFinished(data)
    tasks.value = data
    // 尚未翻过页时，首页拉满即认为可能还有更早的
    if (olderTasks.value.length === 0) {
      hasMore.value = data.length >= PAGE_SIZE
    }
    // 展开中的任务若在执行，增量刷新完整日志
    if (expandedId.value) {
      const expanded = data.find(t => t.id === expandedId.value)
      if (expanded && expanded.status === 'RUNNING') {
        await fetchExpandedLog(expandedId.value)
      }
    }
  } catch (error) {
    if (props.environmentId === environmentId) {
      console.error('加载任务列表失败:', error)
    }
  }
}

// 加载更早的任务（游标：当前列表最后一条的 id）
const loadOlder = async () => {
  if (loadingOlder.value || !props.environmentId) return
  const environmentId = props.environmentId
  const list = allTasks.value
  if (!list.length) return
  loadingOlder.value = true
  try {
    const data = await listTasks(environmentId, PAGE_SIZE, list[list.length - 1].id)
    if (props.environmentId !== environmentId) return

    olderTasks.value = [...olderTasks.value, ...data]
    hasMore.value = data.length >= PAGE_SIZE
  } catch (error) {
    if (props.environmentId === environmentId) {
      console.error('加载更早任务失败:', error)
    }
  } finally {
    loadingOlder.value = false
  }
}

// 检测任务从活跃转为终态：toast + 通知父组件刷新对应服务卡片
const detectFinished = (newTasks) => {
  completionTracker.update(newTasks).forEach(task => {
    const label = `${getTaskTypeLabel(task.taskType)}「${task.serviceName}」`
    if (task.status === 'SUCCESS') {
      toast.success(`任务${label}执行成功`)
    } else {
      toast.error(`任务${label}执行失败：${task.errorMsg || '未知错误'}`)
    }
    emit('task-finished', task)
  })
}

// 拉取展开任务的完整日志并滚动到底部
const fetchExpandedLog = async (id) => {
  try {
    const detail = await getTask(id)
    if (expandedId.value !== id) return
    const changed = detail.commandLog !== expandedLog.value
    expandedLog.value = detail.commandLog || ''
    if (changed) {
      await nextTick()
      // v-for 内的模板 ref 会被收集为数组
      const el = Array.isArray(logRef.value) ? logRef.value[0] : logRef.value
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    }
  } catch (error) {
    console.error('加载任务日志失败:', error)
  }
}

// 展开/收起任务日志
const toggleExpand = async (task) => {
  if (expandedId.value === task.id) {
    expandedId.value = null
    expandedLog.value = ''
    return
  }
  expandedId.value = task.id
  expandedLog.value = ''
  await fetchExpandedLog(task.id)
}

const stopPolling = () => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

// 活跃任务或面板打开时快速刷新；空闲时低频检查其他用户新提交的任务
const schedulePolling = () => {
  stopPolling()
  if (disposed || !props.environmentId) return

  const interval = props.visible || activeCount.value > 0
    ? ACTIVE_POLL_INTERVAL
    : IDLE_POLL_INTERVAL

  pollTimer = setTimeout(async () => {
    pollTimer = null
    await fetchTasks()
    schedulePolling()
  }, interval)
}

// 外部（提交任务后）调用：立即刷新并启动轮询
const refresh = async () => {
  await fetchTasks()
  schedulePolling()
}

const trackTask = (task) => {
  completionTracker.track(task)
  schedulePolling()
}

defineExpose({ refresh, trackTask })

watch(() => props.visible, async (visible) => {
  if (visible) {
    await fetchTasks()
  }
  schedulePolling()
})

watch(() => props.environmentId, async () => {
  stopPolling()
  tasks.value = []
  olderTasks.value = []
  hasMore.value = false
  expandedId.value = null
  expandedLog.value = ''
  completionTracker.reset()
  await fetchTasks()
  schedulePolling()
})

const close = () => {
  emit('update:visible', false)
}

onMounted(async () => {
  disposed = false
  // 初始拉一次，恢复徽标（如后端有历史活跃任务）
  await fetchTasks()
  schedulePolling()
})

onUnmounted(() => {
  disposed = true
  stopPolling()
})
</script>

<template>
  <section v-if="visible" class="task-panel" aria-label="部署任务面板">
    <header class="task-panel-header">
      <div class="task-panel-title">
        <span class="header-icon" aria-hidden="true">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="8" y1="6" x2="21" y2="6"/>
            <line x1="8" y1="12" x2="21" y2="12"/>
            <line x1="8" y1="18" x2="21" y2="18"/>
            <line x1="3" y1="6" x2="3.01" y2="6"/>
            <line x1="3" y1="12" x2="3.01" y2="12"/>
            <line x1="3" y1="18" x2="3.01" y2="18"/>
          </svg>
        </span>
        <div>
          <h2>部署任务</h2>
          <p>{{ allTasks.length }} 条任务记录</p>
        </div>
      </div>
      <div class="task-panel-actions">
        <span v-if="activeCount > 0" class="active-badge">
          <span class="active-pulse" aria-hidden="true"></span>
          {{ activeCount }} 个进行中
        </span>
        <button class="panel-close" type="button" title="收起部署任务" aria-label="收起部署任务" @click="close">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
    </header>

    <div class="task-panel-body">
      <div v-if="allTasks.length === 0" class="task-empty">
        <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/>
          <polyline points="12 6 12 12 16 14"/>
        </svg>
        <p>当前环境暂无部署任务</p>
      </div>

      <article
        v-for="task in allTasks"
        :key="task.id"
        class="task-item"
        :class="[task.status.toLowerCase(), { expanded: expandedId === task.id }]"
      >
        <button class="task-row" type="button" @click="toggleExpand(task)">
          <span class="task-main">
            <span class="task-line1">
              <span class="task-type" :class="task.taskType.toLowerCase()">{{ getTaskTypeLabel(task.taskType) }}</span>
              <span class="task-service">{{ task.serviceName }}</span>
              <span class="task-status" :class="task.status.toLowerCase()">
                <span v-if="task.status === 'RUNNING'" class="status-dot"></span>
                {{ getTaskStatusLabel(task.status) }}
              </span>
            </span>
            <span class="task-line2">
              <span>#{{ task.id }}</span>
              <span v-if="task.submittedBy">{{ task.submittedBy }}</span>
              <span v-if="task.submittedIp" class="task-ip">{{ task.submittedIp }}</span>
              <span>{{ task.createdTime }}</span>
              <span v-if="task.durationMs != null">耗时 {{ formatDuration(task.durationMs) }}</span>
            </span>
          </span>
          <svg
            class="task-arrow"
            :class="{ rotated: expandedId === task.id }"
            width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            aria-hidden="true"
          >
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </button>

        <div v-if="expandedId === task.id" class="task-log-wrapper">
          <div v-if="task.errorMsg" class="task-error">{{ task.errorMsg }}</div>
          <pre ref="logRef" class="task-log">{{ expandedLog || '暂无命令输出...' }}</pre>
        </div>
      </article>

      <div v-if="hasMore && allTasks.length" class="load-older">
        <button class="older-btn" :disabled="loadingOlder" @click="loadOlder">
          <span v-if="loadingOlder" class="mini-spinner"></span>
          {{ loadingOlder ? '加载中...' : '加载更早的任务' }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.task-panel {
  display: flex;
  height: 100%;
  min-height: 260px;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
}

.task-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  flex-shrink: 0;
  min-height: 60px;
  padding: 0.75rem 0.85rem;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-color);
}

.task-panel-title,
.task-panel-actions {
  display: flex;
  align-items: center;
}

.task-panel-title {
  gap: 0.6rem;
  min-width: 0;
}

.task-panel-actions {
  gap: 0.35rem;
  flex-shrink: 0;
}

.header-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  flex-shrink: 0;
  color: var(--primary-color);
  background: var(--primary-light);
  border-radius: 8px;
}

.task-panel-title h2 {
  margin: 0;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 0.9rem;
  font-weight: 700;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-panel-title p {
  margin: 0.15rem 0 0;
  color: var(--text-tertiary);
  font-size: 0.66rem;
  line-height: 1.2;
}

.active-badge {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 2px 6px;
  color: var(--warning-color);
  font-size: 0.66rem;
  font-weight: 700;
  white-space: nowrap;
  background: color-mix(in srgb, var(--warning-color) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--warning-color) 25%, var(--border-color));
  border-radius: 999px;
}

.active-pulse {
  width: 6px;
  height: 6px;
  background: currentColor;
  border-radius: 50%;
  animation: task-pulse 1.2s infinite;
}

.panel-close {
  display: grid;
  width: 30px;
  height: 30px;
  padding: 0;
  place-items: center;
  color: var(--text-secondary);
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}

.panel-close:hover {
  color: var(--primary-color);
  background: var(--bg-hover);
}

.panel-close:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: 2px;
}

.task-panel-body {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 0.6rem;
  padding: 0.7rem;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: var(--border-hover) transparent;
  scrollbar-width: thin;
}

.task-panel-body::-webkit-scrollbar {
  width: 8px;
}

.task-panel-body::-webkit-scrollbar-track {
  background: transparent;
  border-radius: 4px;
}

.task-panel-body::-webkit-scrollbar-thumb {
  background: var(--border-hover);
  border-radius: 4px;
}

.task-panel-body::-webkit-scrollbar-thumb:hover {
  background: var(--text-secondary);
}

.task-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  color: var(--text-tertiary);
  font-size: 0.9rem;
}

.task-item {
  flex-shrink: 0;
  overflow: hidden;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-left: 3px solid var(--text-tertiary);
  border-radius: 10px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.task-item.expanded {
  border-color: var(--primary-color);
  box-shadow: 0 4px 12px color-mix(in srgb, var(--primary-color) 10%, transparent);
}

.task-item.running { border-left-color: var(--warning-color); }
.task-item.success { border-left-color: var(--success-color); }
.task-item.failed { border-left-color: var(--danger-color); }

.task-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 0.875rem;
  color: inherit;
  text-align: left;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s;
}

.task-row:hover {
  background: var(--bg-hover);
}

.task-main {
  display: block;
  flex: 1;
  min-width: 0;
}

.task-line1 {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.375rem;
}

.task-type {
  padding: 0.125rem 0.5rem;
  border-radius: 5px;
  font-size: 0.7rem;
  font-weight: 600;
  background: var(--bg-hover);
  color: var(--text-secondary);
  flex-shrink: 0;
}

.task-type.create { background: color-mix(in srgb, var(--success-color) 15%, transparent); color: var(--success-color); }
.task-type.update { background: color-mix(in srgb, var(--primary-color) 12%, transparent); color: var(--primary-color); }
.task-type.delete { background: color-mix(in srgb, var(--danger-color) 15%, transparent); color: var(--danger-color); }
.task-type.restart { background: color-mix(in srgb, var(--warning-color) 15%, transparent); color: var(--warning-color); }
.task-type.stop { background: color-mix(in srgb, var(--text-tertiary) 15%, transparent); color: var(--text-tertiary); }
.task-type.rollback { background: color-mix(in srgb, var(--danger-color) 10%, transparent); color: var(--danger-color); }
.task-type.scale { background: color-mix(in srgb, var(--info-color, #3b82f6) 15%, transparent); color: var(--info-color, #3b82f6); }

.task-service {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-status {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.125rem 0.55rem;
  border-radius: 12px;
  font-size: 0.72rem;
  font-weight: 600;
  flex-shrink: 0;
}

.task-status.pending { background: color-mix(in srgb, var(--text-tertiary) 15%, transparent); color: var(--text-tertiary); }
.task-status.running { background: color-mix(in srgb, var(--warning-color) 15%, transparent); color: var(--warning-color); }
.task-status.success { background: color-mix(in srgb, var(--success-color) 15%, transparent); color: var(--success-color); }
.task-status.failed { background: color-mix(in srgb, var(--danger-color) 15%, transparent); color: var(--danger-color); }

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: task-pulse 1.2s infinite;
}

@keyframes task-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.task-line2 {
  display: flex;
  gap: 0.75rem;
  font-size: 0.72rem;
  color: var(--text-tertiary);
  flex-wrap: wrap;
}

.task-ip {
  font-family: 'Courier New', monospace;
  color: var(--text-secondary);
}

.task-arrow {
  color: var(--text-tertiary);
  flex-shrink: 0;
  transition: transform 0.2s;
}

.task-arrow.rotated {
  transform: rotate(180deg);
}

.task-row:focus-visible {
  outline: 2px solid var(--primary-color);
  outline-offset: -2px;
}

.task-log-wrapper {
  border-top: 1px solid var(--border-color);
}

.task-error {
  padding: 0.625rem 0.875rem;
  background: color-mix(in srgb, var(--danger-color) 8%, transparent);
  color: var(--danger-color);
  font-size: 0.78rem;
  line-height: 1.5;
  word-break: break-all;
}

.task-log {
  margin: 0;
  padding: 0.875rem;
  max-height: 360px;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: #0f172a;
  color: #cbd5e1;
  font-family: 'Courier New', Consolas, monospace;
  font-size: 0.75rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.load-older {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  padding: 0.25rem 0 0.5rem;
}

.older-btn {
  height: 28px;
  padding: 0 1rem;
  border-radius: 999px;
  border: 1px dashed var(--border-color);
  background: var(--bg-primary);
  color: var(--text-tertiary);
  font-size: 0.75rem;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  transition: all 0.2s;
}

.older-btn:hover:not(:disabled) {
  border-color: var(--primary-color);
  border-style: solid;
  color: var(--primary-color);
}

.older-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.mini-spinner {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid var(--border-color);
  border-top-color: var(--primary-color);
  animation: task-spin 0.8s linear infinite;
}

@keyframes task-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .active-pulse,
  .status-dot,
  .mini-spinner {
    animation: none;
  }

  .task-item,
  .task-row,
  .task-arrow,
  .panel-close {
    transition: none;
  }
}
</style>
