import request from '@/utils/request'

/**
 * 查询环境下的部署任务列表（日志为截断预览）
 */
export function listTasks(environmentId, limit = 50) {
  return request({
    url: '/task/list',
    method: 'get',
    params: { environmentId, limit }
  })
}

/**
 * 查询任务详情（含完整命令日志）
 */
export function getTask(id) {
  return request({
    url: `/task/${id}`,
    method: 'get'
  })
}
