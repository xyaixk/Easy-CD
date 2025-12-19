import request from '@/utils/request'

/**
 * 查询所有环境
 */
export function listEnvironments() {
  return request({
    url: '/environment/list',
    method: 'get'
  })
}

/**
 * 根据ID查询环境
 */
export function getEnvironment(id) {
  return request({
    url: `/environment/${id}`,
    method: 'get'
  })
}

/**
 * 新增环境
 */
export function addEnvironment(data) {
  return request({
    url: '/environment',
    method: 'post',
    data
  })
}

/**
 * 更新环境
 */
export function updateEnvironment(id, data) {
  return request({
    url: `/environment/${id}`,
    method: 'put',
    data
  })
}

/**
 * 删除环境
 */
export function deleteEnvironment(id) {
  return request({
    url: `/environment/${id}`,
    method: 'delete'
  })
}
