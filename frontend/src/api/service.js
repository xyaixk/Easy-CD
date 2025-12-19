import request from '@/utils/request'

/**
 * 查询环境下的所有服务
 */
export function listServices(environmentId) {
  return request({
    url: '/service/list',
    method: 'get',
    params: { environmentId }
  })
}

/**
 * 根据ID查询服务详情
 */
export function getService(id) {
  return request({
    url: `/service/${id}`,
    method: 'get'
  })
}

/**
 * 新增服务
 */
export function createService(data) {
  return request({
    url: '/service',
    method: 'post',
    data
  })
}

/**
 * 更新服务
 */
export function updateService(id, data) {
  return request({
    url: `/service/${id}`,
    method: 'put',
    data
  })
}

/**
 * 删除服务
 */
export function deleteService(id) {
  return request({
    url: `/service/${id}`,
    method: 'delete'
  })
}

/**
 * 重启服务
 */
export function restartService(id) {
  return request({
    url: `/service/${id}/restart`,
    method: 'post'
  })
}

/**
 * 停止服务
 */
export function stopService(id) {
  return request({
    url: `/service/${id}/stop`,
    method: 'post'
  })
}

/**
 * 回滚服务
 */
export function rollbackService(id, targetVersion) {
  return request({
    url: `/service/${id}/rollback`,
    method: 'post',
    params: { targetVersion }
  })
}

/**
 * 调整副本数
 */
export function scaleService(id, replicas) {
  return request({
    url: `/service/${id}/scale`,
    method: 'post',
    params: { replicas }
  })
}

/**
 * 查看服务副本列表
 */
export function getServiceReplicas(id) {
  return request({
    url: `/service/${id}/replicas`,
    method: 'get'
  })
}

/**
 * 获取服务镜像的所有可用版本（用于回滚）
 */
export function getAvailableVersions(id) {
  return request({
    url: `/service/${id}/versions`,
    method: 'get'
  })
}
