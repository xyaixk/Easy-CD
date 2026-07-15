/**
 * 监控数据 API 层。
 * 后端 monitor 接口已就绪（MonitorController），USE_MOCK=false 下全部走真数据。
 * 保留 mock 代码仅作为后端异常时的快速验证开关。
 */
import request from '@/utils/request'

const USE_MOCK = false

/* ---------- mock 数据生成器 ---------- */

// 生成一个"看起来像监控曲线"的 20 点数组
function genSpark(base = 30, amp = 15, points = 20) {
  const arr = []
  let cur = base
  for (let i = 0; i < points; i++) {
    cur += (Math.random() - 0.5) * amp
    cur = Math.max(0, Math.min(100, cur))
    arr.push(Number(cur.toFixed(1)))
  }
  return arr
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)]
}

const MOCK_HOSTS = [
  {
    id: 1,
    hostname: 'host-01',
    ip: '10.0.0.11',
    swarmRole: 'manager',
    swarmStatus: 'ready',
    cpuCores: 8,
    memTotal: 16 * 1024 * 1024 * 1024,
    diskTotal: 500 * 1024 * 1024 * 1024,
    uptimeSeconds: 12 * 86400,
    replicaCount: 12
  },
  {
    id: 2,
    hostname: 'host-02',
    ip: '10.0.0.12',
    swarmRole: 'worker',
    swarmStatus: 'ready',
    cpuCores: 4,
    memTotal: 8 * 1024 * 1024 * 1024,
    diskTotal: 200 * 1024 * 1024 * 1024,
    uptimeSeconds: 5 * 86400,
    replicaCount: 8
  },
  {
    id: 3,
    hostname: 'host-03',
    ip: '10.0.0.13',
    swarmRole: 'worker',
    swarmStatus: 'ready',
    cpuCores: 4,
    memTotal: 8 * 1024 * 1024 * 1024,
    diskTotal: 200 * 1024 * 1024 * 1024,
    uptimeSeconds: 3 * 86400,
    replicaCount: 6
  }
]

function mockHostMetric(host) {
  const cpu = Math.min(95, Math.max(5, 20 + Math.random() * 60))
  const mem = Math.min(95, Math.max(20, 40 + Math.random() * 50))
  const disk = 15 + Math.random() * 35
  return {
    ...host,
    cpuPercent: Number(cpu.toFixed(1)),
    memPercent: Number(mem.toFixed(1)),
    diskPercent: Number(disk.toFixed(1)),
    memUsed: Math.floor((host.memTotal * mem) / 100),
    diskUsed: Math.floor((host.diskTotal * disk) / 100),
    load1: Number((Math.random() * 2 * host.cpuCores).toFixed(2)),
    load5: Number((Math.random() * 1.5 * host.cpuCores).toFixed(2)),
    load15: Number((Math.random() * 1.2 * host.cpuCores).toFixed(2)),
    collectedTime: new Date().toISOString()
  }
}

function mockRangePoints(range) {
  const map = { '5m': 20, '1h': 60, '6h': 72, '24h': 96, '7d': 168 }
  return map[range] || 20
}

function mockRangeStepSec(range) {
  // 与 mockRangePoints 匹配：总时长 / 点数
  const map = { '5m': 15, '1h': 60, '6h': 300, '24h': 900, '7d': 3600 }
  return map[range] || 15
}

function mockTimestamps(points, stepSec) {
  const now = Math.floor(Date.now() / 1000)
  return Array.from({ length: points }, (_, i) => now - (points - 1 - i) * stepSec)
}

/* ---------- 对外 API ---------- */

/** 环境下所有宿主机 + 最新指标 */
export function listHosts(environmentId) {
  if (USE_MOCK) {
    return Promise.resolve(MOCK_HOSTS.map(mockHostMetric))
  }
  return request({
    url: '/monitor/hosts',
    method: 'get',
    params: { environmentId }
  })
}

/** 单节点静态信息 + 最新指标 */
export function getHost(id) {
  if (USE_MOCK) {
    const host = MOCK_HOSTS.find(h => h.id === Number(id)) || MOCK_HOSTS[0]
    return Promise.resolve(mockHostMetric(host))
  }
  return request({
    url: `/monitor/hosts/${id}`,
    method: 'get'
  })
}

/** 节点时序（大图） */
export function getHostMetrics(id, range = '5m') {
  if (USE_MOCK) {
    const points = mockRangePoints(range)
    const stepSec = mockRangeStepSec(range)
    return Promise.resolve({
      range,
      stepSec,
      timestamps: mockTimestamps(points, stepSec),
      cpu: genSpark(35, 18, points),
      mem: genSpark(60, 12, points),
      disk: genSpark(25, 4, points),
      load1: genSpark(20, 15, points).map(v => Number((v / 20).toFixed(2)))
    })
  }
  return request({
    url: `/monitor/hosts/${id}/metrics`,
    method: 'get',
    params: { range }
  })
}

/** 节点上的容器列表 */
export function getHostReplicas(id) {
  if (USE_MOCK) {
    const services = ['nginx-prod', 'redis-cache', 'api-gateway', 'user-service', 'order-service']
    const count = 4 + Math.floor(Math.random() * 6)
    return Promise.resolve(
      Array.from({ length: count }).map((_, i) => {
        const svc = pick(services)
        return {
          replicaId: `${svc}.${i + 1}`,
          replicaName: `${svc}.${i + 1}`,
          serviceName: svc,
          status: Math.random() > 0.1 ? 'running' : 'starting',
          cpuPercent: Number((Math.random() * 40).toFixed(1)),
          memPercent: Number((10 + Math.random() * 50).toFixed(1))
        }
      })
    )
  }
  return request({
    url: `/monitor/hosts/${id}/replicas`,
    method: 'get'
  })
}

/** 服务级指标概要（sparkline + 当前值） */
function mockSparkTimestamps(points = 12, stepSec = 25) {
  // 默认 12 点 × 25s = 5 分钟窗口，与副本/服务卡片 sparkline 长度匹配
  const now = Math.floor(Date.now() / 1000)
  return Array.from({ length: points }, (_, i) => now - (points - 1 - i) * stepSec)
}

/** 服务级指标概要（sparkline + 当前值） */
export function getServiceMetricSummary(serviceId) {
  if (USE_MOCK) {
    const cpuBase = 5 + Math.random() * 40
    const memBase = 20 + Math.random() * 40
    return Promise.resolve({
      cpuPercent: Number(cpuBase.toFixed(1)),
      memPercent: Number(memBase.toFixed(1)),
      cpuSpark: genSpark(cpuBase, 12),
      memSpark: genSpark(memBase, 8),
      timestamps: mockSparkTimestamps()
    })
  }
  return request({
    url: `/monitor/services/${serviceId}/metrics/summary`,
    method: 'get'
  })
}

/** 批量服务级指标概要：首页一轮只发一个请求。 */
export function getServiceMetricSummaries(serviceIds = []) {
  return request({
    url: '/monitor/services/metrics/summaries',
    method: 'post',
    data: serviceIds
  })
}

/** 便捷：将服务列表批量补齐 sparkline + 瞬时百分比。
 * 后端 listServices 目前不返回实时 CPU/MEM 百分比（都是 0），
 * 需要 summary 里的 cpuPercent/memPercent 覆盖到 service.cpuPercent/memoryPercent，
 * 以及 cpuSpark/memSpark/timestamps 支撑图。
 * 未拿到 summary 时静默保留原对象。返回 Promise<Array>。
 */
export function enrichServiceMetrics(services = []) {
  if (!services.length) return Promise.resolve(services)
  const ids = services.filter(s => s && s.id != null).map(s => s.id)
  if (!ids.length) return Promise.resolve(services)
  return getServiceMetricSummaries(ids).then(summaries => {
    const map = summaries || {}
    return services.map(s => {
      if (!s || s.id == null) return s
      const summary = map[s.id] || map[String(s.id)]
      if (!summary) return s
      return {
        ...s,
        cpuPercent: summary.cpuPercent != null ? summary.cpuPercent : s.cpuPercent,
        memoryPercent: summary.memPercent != null ? summary.memPercent : s.memoryPercent,
        cpuPercentMax: summary.cpuPercentMax != null ? summary.cpuPercentMax : s.cpuPercentMax,
        memoryPercentMax: summary.memPercentMax != null ? summary.memPercentMax : s.memoryPercentMax,
        cpuSpark: summary.cpuSpark || s.cpuSpark,
        memSpark: summary.memSpark || s.memSpark,
        cpuSparkMax: summary.cpuSparkMax || s.cpuSparkMax,
        memSparkMax: summary.memSparkMax || s.memSparkMax,
        timestamps: summary.timestamps || s.timestamps
      }
    })
  }).catch(() => services)
}

/** 副本级指标概要，range=5m/30m/2h（默认 5m） */
export function getReplicaMetricSummary(replicaId, range = '5m') {
  if (USE_MOCK) {
    const cpu = Math.random() * 40
    const mem = 15 + Math.random() * 50
    return Promise.resolve({
      cpuPercent: Number(cpu.toFixed(1)),
      memPercent: Number(mem.toFixed(1)),
      cpuSpark: genSpark(cpu, 12),
      memSpark: genSpark(mem, 8),
      timestamps: mockSparkTimestamps()
    })
  }
  return request({
    url: `/monitor/replicas/${replicaId}/metrics/summary`,
    method: 'get',
    params: { range }
  })
}

/** 批量副本级指标概要：key=replicaId，value=5m/30m/2h。 */
export function getReplicaMetricSummaries(rangeByReplica = {}) {
  return request({
    url: '/monitor/replicas/metrics/summaries',
    method: 'post',
    data: rangeByReplica
  })
}

/** 便捷：将服务/副本对象批量补齐指标。
 *  - USE_MOCK=true：本地生成 mock 数据（同步返回）
 *  - USE_MOCK=false：并发拉 getReplicaMetricSummary（异步返回 Promise<Array>）
 *  - rangeMap: { [replicaId]: '5m'|'30m'|'2h' }，未设则使用 defaultRange
 * 调用方需兼容：const result = await enrichWithMockMetrics(items, rangeMap)
 */
export function enrichWithMockMetrics(items = [], rangeMap = {}, defaultRange = '5m') {
  if (USE_MOCK) {
    const ts = mockSparkTimestamps()
    return items.map(item => {
      const cpuBase = 5 + Math.random() * 40
      const memBase = 20 + Math.random() * 40
      return {
        ...item,
        cpuPercent: Number(cpuBase.toFixed(1)),
        memPercent: Number(memBase.toFixed(1)),
        cpuSpark: genSpark(cpuBase, 12),
        memSpark: genSpark(memBase, 8),
        timestamps: ts
      }
    })
  }
  // 真实模式：一次批量请求拉取全部副本 summary
  const ranges = {}
  items.forEach(item => {
    const rid = item.replicaId || item.id || item.name
    if (rid) ranges[rid] = (rangeMap && rangeMap[rid]) || defaultRange
  })
  if (!Object.keys(ranges).length) return Promise.resolve(items)
  return getReplicaMetricSummaries(ranges).then(summaries => {
    const map = summaries || {}
    return items.map(item => {
      const rid = item.replicaId || item.id || item.name
      const range = ranges[rid] || defaultRange
      return map[rid] ? { ...item, ...map[rid], range } : { ...item, range }
    })
  }).catch(() => items.map(item => {
    const rid = item.replicaId || item.id || item.name
    return { ...item, range: ranges[rid] || defaultRange }
  }))
}
