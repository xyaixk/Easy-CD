import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyServiceLayout,
  buildServiceBuckets,
  createServiceLayout,
  sortServiceGroups
} from './serviceLayout.js'

test('sortServiceGroups 按 sortOrder 和 id 排序且不修改原数组', () => {
  const groups = [
    { id: 3, name: '第三组', sortOrder: 1 },
    { id: 2, name: '第二组', sortOrder: 0 },
    { id: 1, name: '第一组', sortOrder: 0 }
  ]

  const sorted = sortServiceGroups(groups)

  assert.deepEqual(sorted.map(group => group.id), [1, 2, 3])
  assert.deepEqual(groups.map(group => group.id), [3, 2, 1])
})

test('buildServiceBuckets 将未知分组服务归入固定在末尾的未分组区域', () => {
  const groups = [
    { id: 8, name: '后端', sortOrder: 1 },
    { id: 7, name: '前端', sortOrder: 0 }
  ]
  const services = [
    { id: 4, groupId: 7, sortOrder: 1 },
    { id: 3, groupId: 99, sortOrder: 0 },
    { id: 2, groupId: null, sortOrder: 1 },
    { id: 1, groupId: 7, sortOrder: 0 }
  ]

  const buckets = buildServiceBuckets(services, groups)

  assert.deepEqual(buckets.map(bucket => bucket.id), [7, 8, null])
  assert.deepEqual(buckets[0].services.map(service => service.id), [1, 4])
  assert.deepEqual(buckets[1].services, [])
  assert.deepEqual(buckets[2].services.map(service => service.id), [3, 2])
})

test('applyServiceLayout 以不可变方式更新分组与服务位置', () => {
  const groups = [
    { id: 10, name: 'A', sortOrder: 0 },
    { id: 20, name: 'B', sortOrder: 1 }
  ]
  const services = [
    { id: 1, name: 'one', groupId: 10, sortOrder: 0 },
    { id: 2, name: 'two', groupId: null, sortOrder: 0 }
  ]
  const layout = createServiceLayout(5, [20, 10], [
    { groupId: 20, serviceIds: [2] },
    { groupId: 10, serviceIds: [] },
    { groupId: null, serviceIds: [1] }
  ])

  const result = applyServiceLayout(services, groups, layout)

  assert.deepEqual(result.groups.map(group => [group.id, group.sortOrder]), [[10, 1], [20, 0]])
  assert.deepEqual(
    result.services.map(service => [service.id, service.groupId, service.sortOrder]),
    [[1, null, 0], [2, 20, 0]]
  )
  assert.equal(groups[0].sortOrder, 0)
  assert.equal(services[0].groupId, 10)
  assert.notEqual(result.groups[0], groups[0])
  assert.notEqual(result.services[0], services[0])
})

test('createServiceLayout 复制输入数组，避免保存期间被后续拖拽修改', () => {
  const groupIds = [1]
  const serviceIds = [2]
  const layout = createServiceLayout(9, groupIds, [
    { groupId: 1, serviceIds },
    { groupId: null, serviceIds: [] }
  ])

  groupIds.push(3)
  serviceIds.push(4)

  assert.deepEqual(layout, {
    environmentId: 9,
    groupIds: [1],
    buckets: [
      { groupId: 1, serviceIds: [2] },
      { groupId: null, serviceIds: [] }
    ]
  })
})
