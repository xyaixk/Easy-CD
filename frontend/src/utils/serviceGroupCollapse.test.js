import assert from 'node:assert/strict'
import test from 'node:test'
import {
  getGroupCollapseKey,
  getGroupCollapseStorageKey,
  parseCollapsedGroupKeys,
  serializeCollapsedGroupKeys,
  UNGROUPED_COLLAPSE_KEY
} from './serviceGroupCollapse.js'

test('分组折叠缓存按环境隔离并统一分组键格式', () => {
  assert.equal(getGroupCollapseStorageKey(12), 'easy-cd:service-groups:collapsed:12')
  assert.equal(getGroupCollapseKey(7), '7')
  assert.equal(getGroupCollapseKey(null), UNGROUPED_COLLAPSE_KEY)
})

test('分组折叠缓存解析时去重并兼容数字 ID', () => {
  assert.deepEqual(
    parseCollapsedGroupKeys('[2,"2","ungrouped",5]'),
    ['2', 'ungrouped', '5']
  )
  assert.deepEqual(parseCollapsedGroupKeys(null), [])
})

test('分组折叠缓存拒绝损坏的数据', () => {
  assert.throws(() => parseCollapsedGroupKeys('{"group":1}'), /必须是数组/)
  assert.throws(() => parseCollapsedGroupKeys('["invalid"]'), /无效分组/)
  assert.throws(() => parseCollapsedGroupKeys('{'), SyntaxError)
})

test('分组折叠缓存序列化 Set 并保持唯一值', () => {
  assert.equal(
    serializeCollapsedGroupKeys(new Set([2, '2', 'ungrouped'])),
    '["2","ungrouped"]'
  )
})
