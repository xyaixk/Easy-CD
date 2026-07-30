import request from '@/utils/request'
import { getToken } from '@/utils/auth'

/**
 * 游标查询日志（backward，返回 { items, hasMore }）
 */
export function queryLogs(data) {
  return request({
    url: '/observability/logs/query',
    method: 'post',
    data
  })
}

/**
 * 日志量直方图（按 detected_level 分桶，返回 [{ tsMs, error, warn, info, debug, other }]）
 */
export function fetchHistogram(data) {
  return request({
    url: '/observability/logs/histogram',
    method: 'post',
    data
  })
}

/**
 * 上下文查询：锚点行前后各 N 行（返回 { items, anchorIndex }）
 */
export function fetchContext(envId, container, tsNanos, limit = 50) {
  return request({
    url: '/observability/logs/context',
    method: 'get',
    params: { envId, container, tsNanos, limit }
  })
}

/**
 * trace 聚合：按时间正序返回该 traceId 的跨服务日志
 */
export function fetchTrace(envId, traceId, from, to) {
  return request({
    url: '/observability/logs/trace',
    method: 'get',
    params: { envId, traceId, from: from || null, to: to || null }
  })
}

/**
 * 当前环境 Loki 中的可选服务列表
 */
export function fetchLogServices(envId) {
  return request({
    url: '/observability/logs/services',
    method: 'get',
    params: { envId }
  })
}

/**
 * 当前环境 Loki 中指定标签的候选值（label: service_name / container_name / image_name）
 */
export function fetchLogLabelValues(envId, label) {
  return request({
    url: '/observability/logs/label-values',
    method: 'get',
    params: { envId, label }
  })
}

/**
 * 导出日志（POST + Blob，携带 token）
 */
export async function exportLogs(body, filename) {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  const token = getToken()
  const resp = await fetch(`${baseURL}/observability/logs/export`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body)
  })
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
  const blob = await resp.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || `logs-${Date.now()}.log`
  document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/**
 * 实时日志 WebSocket 地址（后端代理 Loki tail）
 * @param {Object} filters { envId, services, levels, keyword, traceId, logger, containerName, thread }
 * @returns {String} WebSocket URL
 */
export function getLogsTailWsUrl(filters) {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const params = new URLSearchParams({ envId: String(filters.envId) })
  if (filters.services?.length) params.set('services', filters.services.join(','))
  if (filters.images?.length) params.set('images', filters.images.join(','))
  if (filters.levels?.length) params.set('levels', filters.levels.join(','))
  if (filters.keyword) params.set('keyword', filters.keyword)
  if (filters.traceId) params.set('traceId', filters.traceId)
  if (filters.logger) params.set('logger', filters.logger)
  if (filters.containerName) params.set('containerName', filters.containerName)
  if (filters.thread) params.set('thread', filters.thread)
  const token = getToken()
  if (token) params.set('token', token)
  return `${proto}//${location.host}/api/logs/tail?${params.toString()}`
}
