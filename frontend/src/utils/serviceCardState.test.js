import assert from 'node:assert/strict'
import test from 'node:test'
import {
  mapServiceDetail,
  removeServiceCard,
  upsertServiceCard
} from './serviceCardState.js'

test('mapServiceDetail 保留后端返回的零值并补齐卡片默认字段', () => {
  const mapped = mapServiceDetail({
    id: 7,
    name: 'api',
    status: 'stopped',
    serviceMode: 'global',
    replicas: 0,
    healthyInstances: 0,
    instances: 0,
    desiredInstances: 0,
    createdTime: '2026-09-08 10:00:00'
  })

  assert.equal(mapped.status, 'stopped')
  assert.equal(mapped.replicas, 0)
  assert.equal(mapped.instances, 0)
  assert.equal(mapped.lastDeploy, '2026-09-08 10:00:00')
  assert.equal(mapped.description, '')
})

test('upsertServiceCard 不可变替换部署字段并保留已有监控指标', () => {
  const cpuSpark = Object.freeze([1, 2, 3])
  const original = Object.freeze({
    id: 9,
    name: 'api',
    status: 'deploying',
    version: 'old',
    groupId: 5,
    sortOrder: 3,
    cpuPercent: 28,
    memoryPercent: 41,
    cpuSpark
  })
  const services = Object.freeze([original])

  const result = upsertServiceCard(services, {
    id: 9,
    name: 'api',
    status: 'running',
    version: 'new',
    healthyInstances: 2,
    instances: 2,
    desiredInstances: 2,
    replicas: 2,
    dockerImage: 'registry/api:new'
  })

  assert.notEqual(result, services)
  assert.notEqual(result[0], original)
  assert.equal(original.status, 'deploying')
  assert.equal(result[0].status, 'running')
  assert.equal(result[0].version, 'new')
  assert.equal(result[0].groupId, 5)
  assert.equal(result[0].sortOrder, 3)
  assert.equal(result[0].cpuPercent, 28)
  assert.equal(result[0].memoryPercent, 41)
  assert.equal(result[0].cpuSpark, cpuSpark)
})

test('upsertServiceCard 添加新服务，removeServiceCard 只移除目标服务', () => {
  const first = Object.freeze({ id: 1, name: 'first' })
  const withSecond = upsertServiceCard([first], {
    id: 2,
    name: 'second',
    status: 'running'
  })

  assert.deepEqual(withSecond.map(service => service.id), [1, 2])
  assert.deepEqual(removeServiceCard(withSecond, '1').map(service => service.id), [2])
  assert.equal(removeServiceCard(withSecond, null), withSecond)
})
