import test from 'node:test'
import assert from 'node:assert/strict'

import {
  canFollowLogTarget,
  formatServiceLogInstanceLabel,
  measureLogColumns,
  shortTaskId,
  sortServiceLogInstances
} from './serviceLogs.js'

test('日志实例将运行项置顶，其余按最后状态时间倒序且不修改输入', () => {
  const input = [
    { taskId: 'old', running: false, statusTimestamp: '2026-09-06T10:00:00Z' },
    { taskId: 'running', running: true, statusTimestamp: '2026-09-05T10:00:00Z' },
    { taskId: 'new', running: false, statusTimestamp: '2026-09-07T10:00:00Z' }
  ]

  const result = sortServiceLogInstances(input)

  assert.deepEqual(result.map(item => item.taskId), ['running', 'new', 'old'])
  assert.deepEqual(input.map(item => item.taskId), ['old', 'running', 'new'])
})

test('聚合目标始终可实时跟随，单实例仅运行时可跟随', () => {
  const instances = [
    { taskId: 'running', running: true },
    { taskId: 'failed', running: false }
  ]

  assert.equal(canFollowLogTarget('', instances), true)
  assert.equal(canFollowLogTarget('running', instances), true)
  assert.equal(canFollowLogTarget('failed', instances), false)
  assert.equal(canFollowLogTarget('missing', instances), false)
})

test('实例标签包含状态、节点和稳定的短 Task ID', () => {
  const label = formatServiceLogInstanceLabel({
    taskId: 'abcdefghijklmnopqrstuvwxyz',
    name: 'api.1',
    state: 'failed',
    node: 'node-a',
    statusTimestamp: null
  })

  assert.equal(shortTaskId('abcdefghijklmnopqrstuvwxyz'), 'abcdefghijkl')
  assert.equal(label, 'api.1 · 失败 · node-a · 时间未知 · abcdefghijkl')
})

test('日志列宽统计忽略 ANSI 控制码并保留跨数据块的当前列', () => {
  const first = measureLogColumns('\x1b[31merror\x1b[0m: 中文')
  const second = measureLogColumns(' detail\r\nnext', first.currentColumn, first.maxColumns)

  assert.deepEqual(first, { currentColumn: 11, maxColumns: 11 })
  assert.deepEqual(second, { currentColumn: 4, maxColumns: 18 })
})
