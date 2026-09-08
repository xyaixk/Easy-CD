const fallback = (value, previousValue, defaultValue) =>
  value != null ? value : (previousValue != null ? previousValue : defaultValue)

export const mapServiceDetail = (service, previous = null) => {
  const current = previous || {}
  const serviceMode = service.serviceMode || current.serviceMode || 'replicated'

  return {
    ...current,
    id: service.id,
    name: service.name,
    description: service.description || '',
    version: service.version,
    status: service.status || 'unknown',
    lastDeploy: service.deployTime || service.createdTime || current.lastDeploy,
    branch: current.branch || 'main',
    healthyInstances: fallback(service.healthyInstances, current.healthyInstances, 0),
    instances: fallback(service.instances, current.instances, 0),
    desiredInstances: fallback(service.desiredInstances, current.desiredInstances, 0),
    serviceMode,
    replicas: service.replicas ?? current.replicas ?? service.desiredInstances ?? 1,
    dockerImage: service.dockerImage || '',
    dockerParams: service.dockerParams || '',
    groupId: service.groupId !== undefined ? service.groupId : (current.groupId ?? null),
    sortOrder: service.sortOrder ?? current.sortOrder ?? 0,
    cpuPercent: fallback(service.cpuPercent, current.cpuPercent, 0),
    memoryUsage: fallback(service.memoryUsage, current.memoryUsage, 0),
    memoryLimit: fallback(service.memoryLimit, current.memoryLimit, 0),
    memoryPercent: fallback(service.memoryPercent, current.memoryPercent, 0),
    networkRxRate: fallback(service.networkRxRate, current.networkRxRate, 0),
    networkTxRate: fallback(service.networkTxRate, current.networkTxRate, 0),
    diskReadRate: fallback(service.diskReadRate, current.diskReadRate, 0),
    diskWriteRate: fallback(service.diskWriteRate, current.diskWriteRate, 0)
  }
}

export const upsertServiceCard = (services, detail) => {
  if (!detail || detail.id == null) return services
  const serviceKey = String(detail.id)
  const index = services.findIndex(service => String(service.id) === serviceKey)

  if (index < 0) {
    return [...services, mapServiceDetail(detail)]
  }

  return services.map((service, serviceIndex) =>
    serviceIndex === index ? mapServiceDetail(detail, service) : service
  )
}

export const removeServiceCard = (services, serviceId) => {
  if (serviceId == null) return services
  const serviceKey = String(serviceId)
  return services.filter(service => String(service.id) !== serviceKey)
}
