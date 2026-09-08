import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildBlockingTaskMap,
  getServiceTaskKey,
  getTaskStatusLabel,
  getTaskTypeLabel,
  isActiveTask
} from './deployTask.js'

test('isActiveTask 只将排队和执行中的任务视为活跃任务', () => {
  assert.equal(isActiveTask({ status: 'PENDING' }), true)
  assert.equal(isActiveTask({ status: 'RUNNING' }), true)
  assert.equal(isActiveTask({ status: 'SUCCESS' }), false)
  assert.equal(isActiveTask({ status: 'FAILED' }), false)
  assert.equal(isActiveTask(null), false)
})

test('buildBlockingTaskMap 忽略无服务任务和终态任务', () => {
  const tasks = [
    Object.freeze({ id: 1, serviceId: null, status: 'RUNNING' }),
    Object.freeze({ id: 2, serviceId: 8, status: 'SUCCESS' }),
    Object.freeze({ id: 3, serviceId: 8, status: 'PENDING' })
  ]

  const result = buildBlockingTaskMap(Object.freeze(tasks))

  assert.equal(result.size, 1)
  assert.equal(result.get(getServiceTaskKey(8)), tasks[2])
})

test('buildBlockingTaskMap 优先执行中任务，否则选择最早排队任务', () => {
  const oldestPending = { id: 10, serviceId: 9, status: 'PENDING' }
  const newestPending = { id: 12, serviceId: 9, status: 'PENDING' }
  const running = { id: 11, serviceId: 9, status: 'RUNNING' }

  assert.equal(
    buildBlockingTaskMap([newestPending, oldestPending]).get('9'),
    oldestPending
  )
  assert.equal(
    buildBlockingTaskMap([oldestPending, running, newestPending]).get('9'),
    running
  )
})

test('任务标签提供中文文案并保留未知值', () => {
  assert.equal(getTaskTypeLabel('ROLLBACK'), '回滚')
  assert.equal(getTaskStatusLabel('RUNNING'), '执行中')
  assert.equal(getTaskTypeLabel('CUSTOM'), 'CUSTOM')
  assert.equal(getTaskStatusLabel('CUSTOM'), 'CUSTOM')
})
