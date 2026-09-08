const ACTIVE_TASK_STATUS_SET = new Set(['PENDING', 'RUNNING'])

const TASK_TYPE_LABELS = Object.freeze({
  CREATE: '创建',
  UPDATE: '更新',
  DELETE: '删除',
  RESTART: '重启',
  STOP: '停止',
  ROLLBACK: '回滚',
  SCALE: '伸缩'
})

const TASK_STATUS_LABELS = Object.freeze({
  PENDING: '排队中',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败'
})

export const getServiceTaskKey = serviceId =>
  serviceId == null ? null : String(serviceId)

export const isActiveTask = task =>
  Boolean(task && ACTIVE_TASK_STATUS_SET.has(task.status))

const getTaskKey = task => task?.id == null ? null : String(task.id)

export const createTaskCompletionTracker = () => {
  const activeTaskIds = new Set()

  return {
    track(task) {
      const taskKey = getTaskKey(task)
      if (taskKey != null && isActiveTask(task)) activeTaskIds.add(taskKey)
    },

    update(tasks = []) {
      const finishedTasks = []

      for (const task of tasks) {
        const taskKey = getTaskKey(task)
        if (taskKey == null) continue

        if (isActiveTask(task)) {
          activeTaskIds.add(taskKey)
        } else if (activeTaskIds.delete(taskKey)) {
          finishedTasks.push(task)
        }
      }

      return finishedTasks
    },

    reset() {
      activeTaskIds.clear()
    }
  }
}

export const getTaskTypeLabel = type => TASK_TYPE_LABELS[type] || type || '任务'

export const getTaskStatusLabel = status => TASK_STATUS_LABELS[status] || status || ''

const isPreferredTask = (candidate, current) => {
  if (candidate.status !== current.status) return candidate.status === 'RUNNING'
  return Number(candidate.id) < Number(current.id)
}

export const buildBlockingTaskMap = tasks => {
  const taskMap = new Map()

  for (const task of tasks || []) {
    if (!isActiveTask(task)) continue

    const serviceKey = getServiceTaskKey(task.serviceId)
    if (serviceKey == null) continue

    const current = taskMap.get(serviceKey)
    if (!current || isPreferredTask(task, current)) {
      taskMap.set(serviceKey, task)
    }
  }

  return taskMap
}
