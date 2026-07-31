import request from '@/utils/request'

/**
 * 查询环境下的部署任务列表（日志为截断预览）
 * @param {Number} beforeId 游标翻页：只取 id 小于该值的更早任务，不传为首页
 */
export function listTasks(environmentId, limit = 50, beforeId = null) {
  const params = { environmentId, limit }
  if (beforeId != null) params.beforeId = beforeId
  return request({
    url: '/task/list',
    method: 'get',
    params
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
