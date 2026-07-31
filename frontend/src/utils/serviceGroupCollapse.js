const STORAGE_KEY_PREFIX = 'easy-cd:service-groups:collapsed'
export const UNGROUPED_COLLAPSE_KEY = 'ungrouped'

export function getGroupCollapseStorageKey(environmentId) {
  return `${STORAGE_KEY_PREFIX}:${environmentId}`
}

export function getGroupCollapseKey(groupId) {
  return groupId === null || groupId === undefined
    ? UNGROUPED_COLLAPSE_KEY
    : String(groupId)
}

export function parseCollapsedGroupKeys(value) {
  if (!value) return []

  const parsed = JSON.parse(value)
  if (!Array.isArray(parsed)) {
    throw new TypeError('分组折叠缓存必须是数组')
  }

  const keys = parsed.map(key => String(key))
  if (keys.some(key => key !== UNGROUPED_COLLAPSE_KEY && !/^\d+$/.test(key))) {
    throw new TypeError('分组折叠缓存包含无效分组')
  }
  return [...new Set(keys)]
}

export function serializeCollapsedGroupKeys(keys) {
  return JSON.stringify([...new Set(Array.from(keys, key => String(key)))])
}
