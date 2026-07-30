export const DOCKER_BUILT_IN_KEYS = new Set([
  'replicas', 'cpus', 'memory', 'memory-reservation', 'cpu-reservation',
  'restart', 'restart-max-attempts', 'restart-delay',
  'publish', 'network', 'endpoint-mode',
  'healthcheck', 'healthcheck_interval', 'healthcheck_timeout',
  'healthcheck_retries', 'healthcheck_start_period',
  'update_parallelism', 'update_delay', 'update_monitor',
  'update_failure_action', 'update_order',
  'rollback_parallelism', 'rollback_delay', 'rollback_monitor',
  'rollback_failure_action', 'rollback_order',
  'container-label', 'container-labels', 'mounts',
  'log-driver', 'log-opts', 'command', 'constraints',
  'stop-grace-period', 'replicas-max-per-node'
])

const ENV_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_.-]*$/

export class EnvClipboardError extends Error {
  constructor(message, options = {}) {
    super(message)
    this.name = 'EnvClipboardError'
    this.lineNumber = options.lineNumber || null
  }
}

const toEnvValue = value => value == null ? '' : String(value)

const assertValidKey = (key, options = {}) => {
  if (!ENV_KEY_PATTERN.test(key)) {
    throw new EnvClipboardError(`环境变量名“${key}”不合法`, options)
  }
  if (DOCKER_BUILT_IN_KEYS.has(key)) {
    throw new EnvClipboardError(`“${key}”与 Docker 内置参数重名`, options)
  }
}

const parseQuotedValue = (rawValue, lineNumber) => {
  const quote = rawValue[0]
  if (quote !== '"' && quote !== "'") return rawValue
  if (rawValue.length < 2 || rawValue.at(-1) !== quote) {
    throw new EnvClipboardError('引号未闭合', { lineNumber })
  }

  if (quote === "'") return rawValue.slice(1, -1)

  try {
    return JSON.parse(rawValue)
  } catch (_) {
    throw new EnvClipboardError('双引号值包含无效转义', { lineNumber })
  }
}

/**
 * Parse common .env text into unique key/value entries.
 * Comments are recognized only when # is the first non-whitespace character.
 */
export function parseEnvText(text) {
  const entries = []
  const seenLines = new Map()
  const lines = String(text || '').split(/\r?\n/)

  lines.forEach((rawLine, index) => {
    const lineNumber = index + 1
    let line = rawLine.trim()
    if (!line || line.startsWith('#')) return

    if (/^export(?:\s|$)/.test(line)) {
      line = line.replace(/^export\s+/, '')
    }

    const separatorIndex = line.indexOf('=')
    if (separatorIndex <= 0) {
      throw new EnvClipboardError('必须使用 KEY=VALUE 格式', { lineNumber })
    }

    const key = line.slice(0, separatorIndex).trim()
    const rawValue = line.slice(separatorIndex + 1).trim()
    assertValidKey(key, { lineNumber })

    if (seenLines.has(key)) {
      throw new EnvClipboardError(
        `环境变量“${key}”重复（首次出现在第 ${seenLines.get(key)} 行）`,
        { lineNumber }
      )
    }

    entries.push({ key, value: parseQuotedValue(rawValue, lineNumber) })
    seenLines.set(key, lineNumber)
  })

  return entries
}

/**
 * Return the effective environment list using the same last-value-wins behavior
 * as ServiceDialog's dockerParams conversion.
 */
export function getEffectiveEnvEntries(envList) {
  const effectiveEntries = new Map()

  for (const item of envList || []) {
    const key = String(item?.key || '').trim()
    if (!key) continue
    assertValidKey(key)

    // Reinsert duplicates so output ordering follows the effective row.
    effectiveEntries.delete(key)
    effectiveEntries.set(key, { key, value: toEnvValue(item?.value) })
  }

  return Array.from(effectiveEntries.values())
}

const serializeEnvValue = value => {
  const normalizedValue = toEnvValue(value)
  if (!/[\s#"'\\]/.test(normalizedValue)) return normalizedValue
  return JSON.stringify(normalizedValue)
}

export function serializeEnvList(envList) {
  return getEffectiveEnvEntries(envList)
    .map(({ key, value }) => `${key}=${serializeEnvValue(value)}`)
    .join('\n')
}

export function prepareEnvMerge(currentEnvList, incomingEntries) {
  const currentEntries = getEffectiveEnvEntries(currentEnvList)
  const currentByKey = new Map(currentEntries.map(entry => [entry.key, entry.value]))
  const additions = []
  const unchanged = []
  const conflicts = []

  for (const incoming of incomingEntries || []) {
    const entry = { key: incoming.key, value: toEnvValue(incoming.value) }
    if (!currentByKey.has(entry.key)) {
      additions.push(entry)
      continue
    }

    const currentValue = currentByKey.get(entry.key)
    if (currentValue === entry.value) {
      unchanged.push(entry)
      continue
    }

    conflicts.push({
      key: entry.key,
      currentValue,
      incomingValue: entry.value,
      resolution: 'current'
    })
  }

  return { additions, unchanged, conflicts }
}

export function applyEnvMerge(currentEnvList, mergePlan, resolutions = {}) {
  const conflicts = mergePlan?.conflicts || []
  const additions = mergePlan?.additions || []
  const currentItems = currentEnvList || []
  const conflictByKey = new Map(conflicts.map(conflict => [conflict.key, conflict]))
  const lastConflictIndex = new Map()

  currentItems.forEach((item, index) => {
    const key = String(item?.key || '').trim()
    if (conflictByKey.has(key)) lastConflictIndex.set(key, index)
  })

  const nextEnvList = []
  currentItems.forEach((item, index) => {
    const key = String(item?.key || '').trim()
    const conflict = conflictByKey.get(key)
    if (!conflict) {
      nextEnvList.push({ ...item })
      return
    }

    if (lastConflictIndex.get(key) !== index) return

    const resolution = resolutions instanceof Map
      ? resolutions.get(key)
      : resolutions[key]
    nextEnvList.push({
      key,
      value: resolution === 'incoming' ? conflict.incomingValue : conflict.currentValue
    })
  })

  additions.forEach(entry => {
    nextEnvList.push({ key: entry.key, value: toEnvValue(entry.value) })
  })

  return nextEnvList
}
