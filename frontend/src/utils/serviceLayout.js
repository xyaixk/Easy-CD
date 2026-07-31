const compareOrderThenId = (left, right) => {
  const orderDifference = Number(left.sortOrder || 0) - Number(right.sortOrder || 0)
  return orderDifference || Number(left.id) - Number(right.id)
}

export function sortServiceGroups(groups = []) {
  return [...groups].sort(compareOrderThenId)
}

export function buildServiceBuckets(services = [], groups = []) {
  const sortedGroups = sortServiceGroups(groups)
  const validGroupIds = new Set(sortedGroups.map(group => group.id))
  const servicesByGroup = new Map(sortedGroups.map(group => [group.id, []]))
  const ungroupedServices = []

  for (const service of services) {
    const target = validGroupIds.has(service.groupId)
      ? servicesByGroup.get(service.groupId)
      : ungroupedServices
    target.push(service)
  }

  const sortServices = items => [...items].sort(compareOrderThenId)
  return [
    ...sortedGroups.map(group => ({
      ...group,
      services: sortServices(servicesByGroup.get(group.id))
    })),
    {
      id: null,
      name: '未分组',
      sortOrder: sortedGroups.length,
      services: sortServices(ungroupedServices)
    }
  ]
}

export function createServiceLayout(environmentId, groupIds, buckets) {
  return {
    environmentId,
    groupIds: [...groupIds],
    buckets: buckets.map(bucket => ({
      groupId: bucket.groupId,
      serviceIds: [...bucket.serviceIds]
    }))
  }
}

export function applyServiceLayout(services, groups, layout) {
  const groupOrderById = new Map(layout.groupIds.map((id, index) => [id, index]))
  const servicePositionById = new Map()

  layout.buckets.forEach(bucket => {
    bucket.serviceIds.forEach((id, index) => {
      servicePositionById.set(id, {
        groupId: bucket.groupId,
        sortOrder: index
      })
    })
  })

  return {
    groups: groups.map(group => ({
      ...group,
      sortOrder: groupOrderById.get(group.id) ?? group.sortOrder
    })),
    services: services.map(service => {
      const position = servicePositionById.get(service.id)
      return position
        ? { ...service, ...position }
        : service
    })
  }
}
