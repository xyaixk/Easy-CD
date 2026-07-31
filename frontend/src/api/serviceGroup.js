import request from '@/utils/request'

export function listServiceGroups(environmentId) {
  return request({
    url: '/service-group/list',
    method: 'get',
    params: { environmentId }
  })
}

export function createServiceGroup(data) {
  return request({
    url: '/service-group',
    method: 'post',
    data
  })
}

export function updateServiceGroup(id, data) {
  return request({
    url: `/service-group/${id}`,
    method: 'put',
    data
  })
}

export function deleteServiceGroup(id) {
  return request({
    url: `/service-group/${id}`,
    method: 'delete'
  })
}

export function saveServiceLayout(data) {
  return request({
    url: '/service-group/layout',
    method: 'put',
    data
  })
}
