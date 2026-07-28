<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import toast from '@/utils/toast'
import { listTasks, getTask } from '@/api/task'

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

const emit = defineEmits(['update:visible', 'task-finished', 'update:activeCount'])

// 任务列表
const tasks = ref([])
const loading = ref(false)

// 展开的任务ID及其完整日志
const expandedId = ref(null)
const expandedLog = ref('')
const logRef = ref(null)

// 上一轮各任务状态，用于检测活跃→终态的变化
let prevStatusMap = new Map()

// 轮询
let pollTimer = null
const POLL_INTERVAL = 2000

const activeStatuses = ['PENDING', 'RUNNING']

const activeCount = computed(() => {
  return tasks.value.filter(t => activeStatuses.includes(t.status)).length
})

watch(activeCount, (count) => {
  emit('update:activeCount', count)
}, { immediate: true })

const typeLabels = {
  CREATE: '创建',
  UPDATE: '更新',
  DELETE: '删除',
  RESTART: '重启',
  STOP: '停止',
  ROLLBACK: '回滚',
  SCALE: '伸缩'
}

const statusLabels = {
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败'
}

const getTypeLabel = (type) => typeLabels[type] || type
const getStatusLabel = (status) => statusLabels[status] || status

// 耗时格式化
const formatDuration = (ms) => {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m${seconds % 60}s`
}

// 拉取任务列表
const fetchTasks = async () => {
  if (!props.environmentId) {
    tasks.value = []
    return
  }
  try {
    const data = await listTasks(props.environmentId)
    detectFinished(data)
    tasks.value = data
    // 展开中的任务若在执行，增量刷新完整日志
    if (expandedId.value) {
      const expanded = data.find(t => t.id === expandedId.value)
      if (expanded && expanded.status === 'RUNNING') {
        await fetchExpandedLog(expandedId.value)
      }
    }
  } catch (error) {
    console.error('加载任务列表失败:', error)
  }
}

// 检测任务从活跃转为终态：toast + 通知父组件刷新服务列表
const detectFinished = (newTasks) => {
  newTasks.forEach(task => {
    const prev = prevStatusMap.get(task.id)
    if (prev && activeStatuses.includes(prev) && !activeStatuses.includes(task.status)) {
      const label = `${getTypeLabel(task.taskType)}「${task.serviceName}」`
      if (task.status === 'SUCCESS') {
        toast.success(`任务${label}执行成功`)
      } else {
        toast.error(`任务${label}执行失败：${task.errorMsg || '未知错误'}`)
      }
      emit('task-finished', task)
    }
  })
  prevStatusMap = new Map(newTasks.map(t => [t.id, t.status]))
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

// 轮询控制：抽屉打开或存在活跃任务时持续轮询
const ensurePolling = () => {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    if (!props.visible && activeCount.value === 0) {
      stopPolling()
      return
    }
    await fetchTasks()
  }, POLL_INTERVAL)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 外部（提交任务后）调用：立即刷新并启动轮询
const refresh = async () => {
  await fetchTasks()
  ensurePolling()
}

defineExpose({ refresh })

watch(() => props.visible, async (visible) => {
  if (visible) {
    // 锁住背景滚动，避免抽屉内滚动穿透到页面
    document.body.style.overflow = 'hidden'
    await fetchTasks()
    ensurePolling()
  } else {
    document.body.style.overflow = ''
  }
})

watch(() => props.environmentId, async () => {
  tasks.value = []
  expandedId.value = null
  expandedLog.value = ''
  prevStatusMap = new Map()
  await fetchTasks()
  if (activeCount.value > 0) {
    ensurePolling()
  }
})

const close = () => {
  emit('update:visible', false)
}

onMounted(async () => {
  // 初始拉一次，恢复徽标（如后端有历史活跃任务）
  await fetchTasks()
  if (activeCount.value > 0) {
    ensurePolling()
  }
})

onUnmounted(() => {
  document.body.style.overflow = ''
  stopPolling()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer-fade">
      <div v-if="visible" class="drawer-overlay" @click.self="close">
        <Transition name="drawer-slide" appear>
          <div class="drawer-panel">
            <div class="drawer-header">
              <div class="drawer-title">
                <div class="header-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="8" y1="6" x2="21" y2="6"/>
                    <line x1="8" y1="12" x2="21" y2="12"/>
                    <line x1="8" y1="18" x2="21" y2="18"/>
                    <line x1="3" y1="6" x2="3.01" y2="6"/>
                    <line x1="3" y1="12" x2="3.01" y2="12"/>
                    <line x1="3" y1="18" x2="3.01" y2="18"/>
                  </svg>
                </div>
                <h3>部署任务</h3>
                <span v-if="activeCount > 0" class="active-badge">{{ activeCount }} 个进行中</span>
              </div>
              <button class="drawer-close" @click="close">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="18" y1="6" x2="6" y2="18"/>
                  <line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>

            <div class="drawer-body">
              <!-- 空状态 -->
              <div v-if="tasks.length === 0" class="task-empty">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <circle cx="12" cy="12" r="10"/>
                  <polyline points="12 6 12 12 16 14"/>
                </svg>
                <p>当前环境暂无部署任务</p>
              </div>

              <!-- 任务列表 -->
              <div
                v-for="task in tasks"
                :key="task.id"
                class="task-item"
                :class="{ expanded: expandedId === task.id }"
              >
                <div class="task-row" @click="toggleExpand(task)">
                  <div class="task-main">
                    <div class="task-line1">
                      <span class="task-type" :class="task.taskType.toLowerCase()">{{ getTypeLabel(task.taskType) }}</span>
                      <span class="task-service">{{ task.serviceName }}</span>
                      <span class="task-status" :class="task.status.toLowerCase()">
                        <span v-if="task.status === 'RUNNING'" class="status-dot"></span>
                        {{ getStatusLabel(task.status) }}
                      </span>
                    </div>
                    <div class="task-line2">
                      <span>#{{ task.id }}</span>
                      <span v-if="task.submittedBy">{{ task.submittedBy }}</span>
                      <span v-if="task.submittedIp" class="task-ip">{{ task.submittedIp }}</span>
                      <span>{{ task.createdTime }}</span>
                      <span v-if="task.durationMs != null">耗时 {{ formatDuration(task.durationMs) }}</span>
                    </div>
                  </div>
                  <svg
                    class="task-arrow"
                    :class="{ rotated: expandedId === task.id }"
                    width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                  >
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </div>

                <!-- 终端风格日志 -->
                <div v-if="expandedId === task.id" class="task-log-wrapper">
                  <div v-if="task.errorMsg" class="task-error">{{ task.errorMsg }}</div>
                  <pre ref="logRef" class="task-log">{{ expandedLog || '暂无命令输出...' }}</pre>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  z-index: 10000;
}

.drawer-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 480px;
  max-width: 92vw;
  background: var(--bg-secondary);
  border-radius: 16px 0 0 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 过渡动画 */
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.25s ease;
}

.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 0.3s ease;
}

.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(100%);
}

.drawer-header {
  padding: 0.875rem 1.5rem;
  background: var(--primary-gradient);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.drawer-title {
  display: flex;
  align-items: center;
  gap: 1rem;
  color: white;
}

.header-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
}

.drawer-title h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: white;
}

.active-badge {
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  white-space: nowrap;
}

.drawer-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.drawer-close:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 1.25rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.drawer-body::-webkit-scrollbar {
  width: 8px;
}

.drawer-body::-webkit-scrollbar-track {
  background: var(--bg-primary);
  border-radius: 4px;
}

.drawer-body::-webkit-scrollbar-thumb {
  background: var(--text-tertiary);
  border-radius: 4px;
}

.drawer-body::-webkit-scrollbar-thumb:hover {
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
  border: 1px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-primary);
  overflow: hidden;
  transition: border-color 0.2s;
}

.task-item.expanded {
  border-color: var(--primary-color);
}

.task-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 0.875rem;
  cursor: pointer;
  transition: background 0.15s;
}

.task-row:hover {
  background: var(--bg-hover);
}

.task-main {
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
</style>
