const STATUS_LABELS = {
  running: '运行中',
  new: '新建',
  pending: '等待中',
  assigned: '已分配',
  accepted: '已接受',
  ready: '就绪',
  preparing: '准备中',
  starting: '启动中',
  complete: '已完成',
  shutdown: '已停止',
  failed: '失败',
  rejected: '已拒绝',
  remove: '已移除'
}

const INSTANCE_TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
})

const ANSI_CSI_PATTERN = /\x1b\[[0-?]*[ -/]*[@-~]/g

export function sortServiceLogInstances(instances) {
  return [...(instances || [])].sort((left, right) => {
    if (Boolean(left.running) !== Boolean(right.running)) {
      return left.running ? -1 : 1
    }

    const leftTime = Date.parse(left.statusTimestamp || '')
    const rightTime = Date.parse(right.statusTimestamp || '')
    const leftValid = Number.isFinite(leftTime)
    const rightValid = Number.isFinite(rightTime)
    if (leftValid || rightValid) {
      if (!leftValid) return 1
      if (!rightValid) return -1
      if (leftTime !== rightTime) return rightTime - leftTime
    }
    return String(left.taskId || '').localeCompare(String(right.taskId || ''))
  })
}

export function canFollowLogTarget(taskId, instances) {
  if (!taskId) return true
  return Boolean((instances || []).find(item => item.taskId === taskId)?.running)
}

export function shortTaskId(taskId) {
  return String(taskId || '').slice(0, 12)
}

export function formatServiceLogInstanceLabel(instance) {
  const state = String(instance?.state || '').toLowerCase()
  const status = STATUS_LABELS[state] || instance?.state || '未知'
  const node = instance?.node || '节点未分配'
  const date = new Date(instance?.statusTimestamp)
  const timestamp = instance?.statusTimestamp && !Number.isNaN(date.getTime())
    ? INSTANCE_TIME_FORMATTER.format(date)
    : '时间未知'
  return `${instance?.name || '实例'} · ${status} · ${node} · ${timestamp} · ${shortTaskId(instance?.taskId)}`
}

export function measureLogColumns(text, initialColumn = 0, initialMax = 0) {
  let currentColumn = initialColumn
  let maxColumns = Math.max(initialMax, initialColumn)
  const plainText = String(text || '').replace(ANSI_CSI_PATTERN, '')

  for (const character of plainText) {
    const codePoint = character.codePointAt(0)
    if (character === '\n' || character === '\r') {
      currentColumn = 0
    } else if (character === '\t') {
      currentColumn += 8 - (currentColumn % 8)
    } else if (character === '\b') {
      currentColumn = Math.max(0, currentColumn - 1)
    } else if (codePoint >= 0x20 && !(codePoint >= 0x7f && codePoint <= 0x9f)) {
      currentColumn += codePoint <= 0x7f ? 1 : 2
    }
    maxColumns = Math.max(maxColumns, currentColumn)
  }

  return { currentColumn, maxColumns }
}
