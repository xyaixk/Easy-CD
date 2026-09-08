<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted, watch } from 'vue'
import AppHeader from './components/AppHeader.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import ServiceGroupBoard from './components/ServiceGroupBoard.vue'
import ServiceGroupDialog from './components/ServiceGroupDialog.vue'
import EnvDialog from './components/EnvDialog.vue'
import ServiceDialog from './components/ServiceDialog.vue'
import ReplicasDialog from './components/ReplicasDialog.vue'
import ConfigDialog from './components/ConfigDialog.vue'
import LoginDialog from './components/LoginDialog.vue'
import LogsPage from './components/log/LogsPage.vue'
import TaskDrawer from './components/TaskDrawer.vue'
import HostMetricStrip from './components/monitor/HostMetricStrip.vue'
import HostDetailDialog from './components/monitor/HostDetailDialog.vue'
import toast from '@/utils/toast'
import { listEnvironments, addEnvironment as addEnvApi, deleteEnvironment as deleteEnvApi, updateEnvironment as updateEnvApi } from '@/api/environment'
import { login as loginApi, logout as logoutApi, getCurrentUser as getCurrentUserApi } from '@/api/auth'
import { 
  createService as createServiceApi, 
  updateService as updateServiceApi, 
  deleteService as deleteServiceApi, 
  getService,
  listServices,
  restartService,
  stopService,
  rollbackService,
  scaleService
} from '@/api/service'
import {
  createServiceGroup,
  deleteServiceGroup,
  listServiceGroups,
  saveServiceLayout,
  updateServiceGroup
} from '@/api/serviceGroup'
import { clearAuth, getCurrentUser as getStoredUser, getToken, setAuth } from '@/utils/auth'
import {
  buildBlockingTaskMap,
  getServiceTaskKey,
  getTaskStatusLabel,
  getTaskTypeLabel
} from '@/utils/deployTask'
import { enrichServiceMetrics } from '@/api/monitor'
import { applyServiceLayout } from '@/utils/serviceLayout'
import { mapServiceDetail, removeServiceCard, upsertServiceCard } from '@/utils/serviceCardState'
import { useMinimumVisible } from '@/composables/useMinimumVisible'

// 环境列表
const environments = ref([])
const currentUser = ref(getStoredUser())
const showLoginDialog = ref(false)
const loginLoading = ref(false)

// 当前选中的环境
const selectedEnv = ref(null)

// 当前环境信息
const currentEnvironmentInfo = computed(() => {
  return environments.value.find(e => e.id === selectedEnv.value)
})

// 搜索关键词
const searchKeyword = ref('')

// 选中的状态筛选
const selectedStatus = ref('all')

// 显示环境对话框
const showEnvDialog = ref(false)

// 显示服务对话框
const showServiceDialog = ref(false)
const currentService = ref(null)
const serviceDialogMode = ref('create')

// 服务分组与布局
const serviceGroups = ref([])
const showGroupDialog = ref(false)
const groupDialogMode = ref('create')
const currentGroup = ref(null)
const groupDialogLoading = ref(false)
const showDeleteGroupConfirm = ref(false)
const pendingDeleteGroup = ref(null)
const layoutSaving = ref(false)
const layoutDragging = ref(false)

const deleteGroupConfirmMessage = computed(() => {
  const group = pendingDeleteGroup.value
  if (!group) return ''

  if (group.serviceCount > 0) {
    return `删除分组「${group.name}」后，其中 ${group.serviceCount} 个服务将移至「未分组」末尾，服务本身不会被删除。分组删除后无法恢复。`
  }

  return `确定删除分组「${group.name}」吗？分组删除后无法恢复。`
})

// 显示副本对话框
const showReplicasDialog = ref(false)
const selectedService = ref(null)

// 显示配置对话框
const showConfigDialog = ref(false)
const configEnvironment = ref(null)

const handleOpenLogin = () => {
  showLoginDialog.value = true
}

// 主内容区视图切换：deploy 部署视图 / logs 日志视图（v-show 保留查询现场）
const activeView = ref('deploy')
const logsViewOpened = ref(false)

const handleOpenLogs = () => {
  activeView.value = 'logs'
  logsViewOpened.value = true
}

// 部署任务面板
const showTaskDrawer = ref(false)
const activeTaskCount = ref(0)
const activeTasks = ref([])
const taskDrawerRef = ref(null)
const blockingTaskByServiceId = computed(() => buildBlockingTaskMap(activeTasks.value))
const getBlockingTask = serviceId => {
  const serviceKey = getServiceTaskKey(serviceId)
  return serviceKey == null ? null : blockingTaskByServiceId.value.get(serviceKey) || null
}
const selectedServiceReadOnly = computed(() => Boolean(getBlockingTask(selectedService.value?.id)))

const ensureServiceMutable = (service) => {
  const blockingTask = getBlockingTask(service?.id)
  if (!blockingTask) return true

  const taskLabel = `${getTaskTypeLabel(blockingTask.taskType)}任务${getTaskStatusLabel(blockingTask.status)}`
  toast.warning(`服务「${service.name}」的${taskLabel}，暂不可执行其他操作`)
  return false
}

const handleOpenTasks = () => {
  showTaskDrawer.value = !showTaskDrawer.value
}

// 任务提交成功后：提示 + 打开任务面板并立即刷新
const onTaskSubmitted = (taskId, message, taskContext = null) => {
  let optimisticTask = null
  if (taskContext) {
    optimisticTask = {
      id: taskId,
      serviceId: taskContext.serviceId ?? null,
      serviceName: taskContext.serviceName,
      taskType: taskContext.taskType,
      status: 'PENDING'
    }
    activeTasks.value = [
      optimisticTask,
      ...activeTasks.value.filter(task => String(task.id) !== String(taskId))
    ]
    activeTaskCount.value = activeTasks.value.length
  }

  toast.success(`${message}，任务 #${taskId} 已提交`)
  showTaskDrawer.value = true
  if (optimisticTask) taskDrawerRef.value?.trackTask(optimisticTask)
  void taskDrawerRef.value?.refresh()
}

// 宿主机详情弹窗
const showHostDetail = ref(false)
const selectedHost = ref(null)

const handleHostClick = (host) => {
  selectedHost.value = host
  showHostDetail.value = true
}

// 打开配置
const handleOpenConfig = () => {
  if (!currentEnvironmentInfo.value) {
    toast.warning('请先选择一个环境')
    return
  }
  configEnvironment.value = currentEnvironmentInfo.value
  showConfigDialog.value = true
}

// 确认配置
const handleConfirmConfig = async (configData) => {
  try {
    const submitData = {
      name: configEnvironment.value.name,
      color: configEnvironment.value.color,
      deployType: configEnvironment.value.deployType,
      config: JSON.stringify(configData)
    }
    
    const result = await updateEnvApi(configEnvironment.value.id, submitData)
    const index = environments.value.findIndex(e => e.id === configEnvironment.value.id)
    if (index !== -1) {
      environments.value[index] = result
    }
    toast.success(`环境「${result.name}」配置保存成功`)
  } catch (error) {
    console.error('保存配置失败:', error)
    toast.error(error.message || '保存失败，请重试')
  }
}

const handleLogin = async (formData) => {
  try {
    loginLoading.value = true
    const result = await loginApi(formData)
    setAuth(result)
    currentUser.value = result
    showLoginDialog.value = false
    await loadEnvironments()
    await loadServices()
    toast.success(`登录成功，欢迎 ${result.username}`)
  } catch (error) {
    console.error('登录失败:', error)
    toast.error(error.message || '登录失败，请重试')
  } finally {
    loginLoading.value = false
  }
}

const handleLogout = async () => {
  try {
    await logoutApi()
  } catch (error) {
    console.error('退出登录失败:', error)
  } finally {
    clearAuth()
    currentUser.value = null
    services.value = []
    serviceGroups.value = []
    await loadEnvironments()
    await loadServices()
    toast.success('已退出登录')
  }
}

// 服务列表数据
const services = ref([])
let serviceDataEpoch = 0
const serviceRefreshTokens = new Map()
const serviceLoadCount = ref(0)
const serviceBlockingLoadCount = ref(0)
const servicesLoading = computed(() => serviceLoadCount.value > 0)
const servicesBlockingLoading = computed(() => serviceBlockingLoadCount.value > 0)
const servicesRefreshing = computed(() => servicesLoading.value && !servicesBlockingLoading.value)
const servicesBlockingLoadingVisible = useMinimumVisible(servicesBlockingLoading, 500)
const servicesRefreshingVisible = useMinimumVisible(servicesRefreshing, 500)

// 定时刷新相关
let refreshTimer = null
const REFRESH_INTERVAL = 3000 // 与宿主机监控保持一致，每 3 秒刷新

// 过滤后的服务列表
const filteredServices = computed(() => {
  let result = services.value
  
  // 状态筛选
  if (selectedStatus.value !== 'all') {
    result = result.filter(service => service.status === selectedStatus.value)
  }
  
  // 搜索关键词筛选
  if (searchKeyword.value) {
    const keyword = searchKeyword.value.toLowerCase()
    result = result.filter(service => 
      service.name.toLowerCase().includes(keyword) ||
      service.description.toLowerCase().includes(keyword)
    )
  }
  
  return result
})

const hasActiveServiceFilter = computed(() =>
  selectedStatus.value !== 'all' || Boolean(searchKeyword.value.trim())
)

const shouldShowServiceBoard = computed(() => {
  if (hasActiveServiceFilter.value) {
    return filteredServices.value.length > 0
  }
  return services.value.length > 0 || serviceGroups.value.length > 0
})

// 获取各状态的服务数量
const statusCounts = computed(() => {
  return {
    all: services.value.length,
    running: services.value.filter(s => s.status === 'running').length,
    deploying: services.value.filter(s => s.status === 'deploying').length,
    stopped: services.value.filter(s => s.status === 'stopped').length
  }
})

// 获取状态颜色
const getStatusColor = (status) => {
  const colors = {
    running: '#10b981',      // 绿色 - 运行中
    stopped: '#94a3b8',      // 灰色 - 已停止
    deploying: '#f59e0b',    // 橙色 - 部署中
    failed: '#ef4444',       // 红色 - 失败
    degraded: '#f97316',     // 深橙 - 降级运行
    scaling: '#3b82f6',      // 蓝色 - 扩缩容中
    unknown: '#6b7280'       // 深灰 - 未知
  }
  return colors[status] || '#94a3b8'
}

// 获取状态文本
const getStatusText = (status) => {
  const texts = {
    running: '运行中',
    stopped: '已停止',
    deploying: '部署中',
    failed: '失败',
    degraded: '降级',
    scaling: '扩缩容中',
    unknown: '未知'
  }
  return texts[status] || status
}

// 添加环境
const handleAddEnvironment = () => {
  showEnvDialog.value = true
}

// 确认环境对话框
const handleConfirmEnvironment = async (data) => {
  try {
    // 添加环境
    const result = await addEnvApi(data)
    environments.value.push(result)
    toast.success(`环境「${result.name}」添加成功`)
  } catch (error) {
    console.error('保存环境失败:', error)
    toast.error(error.message || '保存失败，请重试')
  }
}

// 加载环境列表
const loadEnvironments = async () => {
  try {
    const data = await listEnvironments()
    environments.value = data
    if (data.length > 0) {
      // 尝试从 localStorage 读取上次选中的环境
      const cachedEnvId = localStorage.getItem('selectedEnvId')
      if (cachedEnvId) {
        const envExists = data.find(e => e.id === parseInt(cachedEnvId))
        if (envExists) {
          selectedEnv.value = parseInt(cachedEnvId)
        } else {
          // 缓存的环境不存在,使用第一个环境
          selectedEnv.value = data[0].id
          localStorage.setItem('selectedEnvId', data[0].id)
        }
      } else {
        // 没有缓存,使用第一个环境并缓存
        selectedEnv.value = data[0].id
        localStorage.setItem('selectedEnvId', data[0].id)
      }
    } else {
      selectedEnv.value = null
      localStorage.removeItem('selectedEnvId')
    }
  } catch (error) {
    console.error('加载环境列表失败:', error)
  }
}

// 加载服务列表
const loadServices = async ({ force = false, blocking = false } = {}) => {
  if (!selectedEnv.value) {
    services.value = []
    serviceGroups.value = []
    return
  }
  if (!force && (layoutDragging.value || layoutSaving.value)) return

  const environmentId = selectedEnv.value
  const dataEpoch = serviceDataEpoch
  serviceLoadCount.value += 1
  if (blocking) serviceBlockingLoadCount.value += 1
  try {
    const [data, groups] = await Promise.all([
      listServices(environmentId),
      listServiceGroups(environmentId)
    ])
    if (selectedEnv.value !== environmentId || dataEpoch !== serviceDataEpoch) return

    // 转换为前端需要的格式
    const newServices = data.map(service => mapServiceDetail(service))

    // 并发补齐 sparkline（失败静默），不阻塞接下来的 diff 更新
    const enriched = await enrichServiceMetrics(newServices)
    if (selectedEnv.value !== environmentId || dataEpoch !== serviceDataEpoch) return
    if (!force && (layoutDragging.value || layoutSaving.value)) return

    // 只更新有变化的服务
    serviceGroups.value = groups
    updateChangedServices(enriched)
  } catch (error) {
    console.error('加载服务列表失败:', error)
    // 静默失败，避免定时刷新时频繁提示错误
  } finally {
    serviceLoadCount.value = Math.max(0, serviceLoadCount.value - 1)
    if (blocking) {
      serviceBlockingLoadCount.value = Math.max(0, serviceBlockingLoadCount.value - 1)
    }
  }
}

// 智能更新：只更新有变化的服务
const updateChangedServices = (newServices) => {
  const oldServicesMap = new Map(services.value.map(s => [s.id, s]))
  services.value = newServices.map(newService => {
    const oldService = oldServicesMap.get(newService.id)
    return oldService && !hasServiceChanged(oldService, newService)
      ? oldService
      : newService
  })
}

const isTaskForCurrentEnvironment = task =>
  task?.environmentId == null || String(task.environmentId) === String(selectedEnv.value)

const nextServiceRefreshToken = serviceId => {
  const serviceKey = String(serviceId)
  const token = (serviceRefreshTokens.get(serviceKey) || 0) + 1
  serviceRefreshTokens.set(serviceKey, token)
  return { serviceKey, token }
}

const updateOpenServiceReference = service => {
  if (selectedService.value && String(selectedService.value.id) === String(service.id)) {
    selectedService.value = service
  }
  if (currentService.value && String(currentService.value.id) === String(service.id)) {
    currentService.value = service
  }
}

const refreshServiceCard = async task => {
  const environmentId = selectedEnv.value
  const { serviceKey, token } = nextServiceRefreshToken(task.serviceId)
  serviceDataEpoch += 1

  try {
    const detail = await getService(task.serviceId)
    if (selectedEnv.value !== environmentId || !isTaskForCurrentEnvironment(task)) return
    if (detail.environmentId != null && String(detail.environmentId) !== String(environmentId)) return
    if (serviceRefreshTokens.get(serviceKey) !== token) return

    services.value = upsertServiceCard(services.value, detail)
    const refreshed = services.value.find(service => String(service.id) === serviceKey)
    if (refreshed) updateOpenServiceReference(refreshed)
  } catch (error) {
    if (selectedEnv.value === environmentId && serviceRefreshTokens.get(serviceKey) === token) {
      console.error('定向刷新服务卡片失败:', error)
      await loadServices({ force: true })
    }
  }
}

// 任务进入终态后，优先定向刷新对应卡片；创建和删除处理列表结构变化。
const handleTaskFinished = async task => {
  if (!isTaskForCurrentEnvironment(task)) return

  if (task.status === 'SUCCESS' && task.taskType === 'DELETE' && task.serviceId != null) {
    serviceDataEpoch += 1
    nextServiceRefreshToken(task.serviceId)
    services.value = removeServiceCard(services.value, task.serviceId)
    if (selectedService.value && String(selectedService.value.id) === String(task.serviceId)) {
      selectedService.value = null
      showReplicasDialog.value = false
    }
    if (currentService.value && String(currentService.value.id) === String(task.serviceId)) {
      currentService.value = null
      showServiceDialog.value = false
    }
    return
  }

  if (task.serviceId == null || task.taskType === 'CREATE') {
    serviceDataEpoch += 1
    await loadServices({ force: true })
    return
  }

  await refreshServiceCard(task)
}

// 检查服务是否发生变化
const hasServiceChanged = (oldService, newService) => {
  return oldService.status !== newService.status ||
    oldService.version !== newService.version ||
    oldService.healthyInstances !== newService.healthyInstances ||
    oldService.instances !== newService.instances ||
    oldService.desiredInstances !== newService.desiredInstances ||
    oldService.lastDeploy !== newService.lastDeploy ||
    oldService.description !== newService.description ||
    oldService.dockerImage !== newService.dockerImage ||
    oldService.dockerParams !== newService.dockerParams ||
    oldService.serviceMode !== newService.serviceMode ||
    oldService.replicas !== newService.replicas ||
    oldService.groupId !== newService.groupId ||
    oldService.sortOrder !== newService.sortOrder ||
    // 监控指标变化检测
    oldService.cpuPercent !== newService.cpuPercent ||
    oldService.memoryUsage !== newService.memoryUsage ||
    oldService.networkRxRate !== newService.networkRxRate ||
    oldService.networkTxRate !== newService.networkTxRate ||
    oldService.diskReadRate !== newService.diskReadRate ||
    oldService.diskWriteRate !== newService.diskWriteRate ||
    // sparkline 引用变化（enrichServiceMetrics 每轮返回新数组），确保图能刷
    oldService.cpuSpark !== newService.cpuSpark ||
    oldService.memSpark !== newService.memSpark
}

// 启动定时刷新
const startAutoRefresh = () => {
  stopAutoRefresh() // 先清除可能存在的旧定时器
  refreshTimer = setInterval(() => {
    // 如果有对话框打开，暂停刷新避免冲突
    if (!showEnvDialog.value && !showServiceDialog.value && 
        !showReplicasDialog.value && !showConfigDialog.value &&
        !showGroupDialog.value && !showDeleteGroupConfirm.value &&
        !layoutDragging.value && !layoutSaving.value) {
      loadServices()
    }
  }, REFRESH_INTERVAL)
}

// 停止定时刷新
const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

// 监听环境切换，重新加载服务列表并缓存选中的环境
watch(selectedEnv, (newEnvId) => {
  // 立即移除旧环境数据；环境切换不能被拖拽/布局保存的刷新保护跳过
  showDeleteGroupConfirm.value = false
  pendingDeleteGroup.value = null
  activeTasks.value = []
  serviceDataEpoch += 1
  serviceRefreshTokens.clear()
  services.value = []
  serviceGroups.value = []
  loadServices({ force: true, blocking: true })
  // 缓存选中的环境ID
  if (newEnvId) {
    localStorage.setItem('selectedEnvId', newEnvId)
  }
})

// 当前环境→使用环境自身 color 作为全局主题色
watch(currentEnvironmentInfo, (env) => {
  const html = document.documentElement
  if (env && env.color) {
    html.style.setProperty('--primary-color', env.color)
    html.classList.add('theme-locked')
  } else {
    html.style.removeProperty('--primary-color')
    html.classList.remove('theme-locked')
  }
}, { immediate: true })

// 页面加载时获取环境列表和服务列表
onMounted(async () => {
  const token = getToken()
  if (token) {
    try {
      const user = await getCurrentUserApi()
      setAuth(user)
      currentUser.value = user
    } catch (error) {
      console.error('恢复登录态失败:', error)
      clearAuth()
      currentUser.value = null
    }
  }

  await loadEnvironments()
  await loadServices({ blocking: true })
  startAutoRefresh()
})

// 页面卸载时清理定时器
onUnmounted(() => {
  stopAutoRefresh()
})

// 删除环境
const handleDeleteEnvironment = async (envId) => {
  const env = environments.value.find(e => e.id === envId)
  if (!env) return
  
  if (confirm(`确定要删除环境「${env.name}」吗？`)) {
    try {
      await deleteEnvApi(envId)
      environments.value = environments.value.filter(e => e.id !== envId)
      // 如果删除的是当前选中的环境，切换到第一个
      if (selectedEnv.value === envId && environments.value.length > 0) {
        selectedEnv.value = environments.value[0].id
      }
      
      // 显示成功提示
      toast.success(`环境「${env.name}」已删除`)
    } catch (error) {
      console.error('删除环境失败:', error)
      toast.error(error.message || '删除失败，请重试')
    }
  }
}

const openCreateGroupDialog = () => {
  if (layoutDragging.value || layoutSaving.value) return
  if (!selectedEnv.value) {
    toast.warning('请先选择一个环境')
    return
  }
  currentGroup.value = null
  groupDialogMode.value = 'create'
  showGroupDialog.value = true
}

const openRenameGroupDialog = (group) => {
  if (layoutDragging.value || layoutSaving.value) return
  currentGroup.value = group
  groupDialogMode.value = 'edit'
  showGroupDialog.value = true
}

const closeGroupDialog = () => {
  if (!groupDialogLoading.value) showGroupDialog.value = false
}

const handleConfirmGroup = async (name) => {
  if (!selectedEnv.value) return

  groupDialogLoading.value = true
  try {
    if (groupDialogMode.value === 'edit') {
      const updatedGroup = await updateServiceGroup(currentGroup.value.id, { name })
      serviceGroups.value = serviceGroups.value.map(group =>
        group.id === updatedGroup.id ? updatedGroup : group
      )
      toast.success(`分组已重命名为「${updatedGroup.name}」`)
    } else {
      const createdGroup = await createServiceGroup({
        environmentId: selectedEnv.value,
        name
      })
      serviceGroups.value = [...serviceGroups.value, createdGroup]
      toast.success(`分组「${createdGroup.name}」创建成功`)
    }
    showGroupDialog.value = false
  } catch (error) {
    console.error('保存服务分组失败:', error)
    toast.error(error.message || '保存分组失败，请重试')
  } finally {
    groupDialogLoading.value = false
  }
}

const handleDeleteGroup = (group) => {
  if (layoutDragging.value || layoutSaving.value) return
  const serviceCount = services.value.filter(service => service.groupId === group.id).length
  pendingDeleteGroup.value = { ...group, serviceCount }
  showDeleteGroupConfirm.value = true
}

const handleCancelDeleteGroup = () => {
  pendingDeleteGroup.value = null
}

const handleConfirmDeleteGroup = async () => {
  const group = pendingDeleteGroup.value
  if (!group) return
  pendingDeleteGroup.value = null

  try {
    await deleteServiceGroup(group.id)
    await loadServices()
    toast.success(`分组「${group.name}」已删除`)
  } catch (error) {
    console.error('删除服务分组失败:', error)
    toast.error(error.message || '删除分组失败，请重试')
  }
}

const handleLayoutChange = async (layout) => {
  if (layoutSaving.value || selectedEnv.value !== layout.environmentId) return

  const previousServices = services.value
  const previousGroups = serviceGroups.value
  const optimisticLayout = applyServiceLayout(previousServices, previousGroups, layout)
  services.value = optimisticLayout.services
  serviceGroups.value = optimisticLayout.groups
  layoutSaving.value = true

  try {
    await saveServiceLayout(layout)
  } catch (error) {
    console.error('保存服务布局失败:', error)
    if (selectedEnv.value === layout.environmentId) {
      services.value = previousServices
      serviceGroups.value = previousGroups
      await loadServices({ force: true })
    }
    toast.error(error.message || '保存布局失败，已恢复原顺序')
  } finally {
    layoutSaving.value = false
  }
}

// 服务操作方法（均为异步：提交后返回任务ID，实际执行在后台队列）
// 更新操作：仅变更镜像，其他服务配置由后端沿用数据库现值
const updateService = async ({ service, dockerImage }) => {
  if (!ensureServiceMutable(service)) return

  try {
    const taskId = await updateServiceApi(service.id, {
      name: service.name,
      dockerImage
    })
    onTaskSubmitted(taskId, `服务「${service.name}」更新镜像至 ${dockerImage}`, {
      serviceId: service.id,
      serviceName: service.name,
      taskType: 'UPDATE'
    })
  } catch (error) {
    console.error('更新服务失败:', error)
    toast.error(error.message || '更新失败，请重试')
  }
}

const rollbackServiceHandler = async ({ service, version }) => {
  if (!ensureServiceMutable(service)) return

  try {
    const taskId = await rollbackService(service.id, version)
    onTaskSubmitted(taskId, `服务「${service.name}」回滚到 ${version}`, {
      serviceId: service.id,
      serviceName: service.name,
      taskType: 'ROLLBACK'
    })
  } catch (error) {
    console.error('回滚服务失败:', error)
    toast.error(error.message || '回滚失败，请重试')
  }
}

const restartServiceHandler = async (service) => {
  if (!ensureServiceMutable(service)) return

  try {
    const taskId = await restartService(service.id)
    onTaskSubmitted(taskId, `服务「${service.name}」重启`, {
      serviceId: service.id,
      serviceName: service.name,
      taskType: 'RESTART'
    })
  } catch (error) {
    console.error('重启服务失败:', error)
    toast.error(error.message || '重启失败，请重试')
  }
}

const stopServiceHandler = async (service) => {
  if (!ensureServiceMutable(service)) return

  try {
    const taskId = await stopService(service.id)
    onTaskSubmitted(taskId, `服务「${service.name}」停止`, {
      serviceId: service.id,
      serviceName: service.name,
      taskType: 'STOP'
    })
  } catch (error) {
    console.error('停止服务失败:', error)
    toast.error(error.message || '停止失败，请重试')
  }
}

const scaleServiceHandler = async ({ service, replicas }) => {
  if (!ensureServiceMutable(service)) return

  try {
    const taskId = await scaleService(service.id, replicas)
    onTaskSubmitted(taskId, `服务「${service.name}」副本数调整为 ${replicas}`, {
      serviceId: service.id,
      serviceName: service.name,
      taskType: 'SCALE'
    })
  } catch (error) {
    console.error('调整副本失败:', error)
    toast.error(error.message || '调整副本失败，请重试')
  }
}

const viewLogs = (service) => {
  selectedService.value = service
  showReplicasDialog.value = true
}

const editConfig = (service) => {
  if (!ensureServiceMutable(service)) return

  currentService.value = service
  serviceDialogMode.value = 'edit'
  showServiceDialog.value = true
}

const copyService = (service) => {
  if (!ensureServiceMutable(service)) return

  currentService.value = service
  serviceDialogMode.value = 'copy'
  showServiceDialog.value = true
}

const deleteServiceHandler = async (service) => {
  if (!ensureServiceMutable(service)) return

  try {
    const taskId = await deleteServiceApi(service.id)
    onTaskSubmitted(taskId, `服务「${service.name}」删除`, {
      serviceId: service.id,
      serviceName: service.name,
      taskType: 'DELETE'
    })
  } catch (error) {
    console.error('删除服务失败:', error)
    toast.error(error.message || '删除失败，请重试')
  }
}

// 打开新增服务对话框
const handleAddService = () => {
  currentService.value = null
  serviceDialogMode.value = 'create'
  showServiceDialog.value = true
}

// 确认新增/编辑服务（异步提交）
const handleConfirmService = async (serviceData) => {
  const dialogMode = serviceDialogMode.value
  const serviceId = currentService.value?.id

  if (dialogMode !== 'create' && !ensureServiceMutable(currentService.value)) return

  try {
    if (dialogMode === 'edit') {
      // 编辑服务
      const taskId = await updateServiceApi(serviceId, serviceData)
      onTaskSubmitted(taskId, `服务「${serviceData.name}」修改`, {
        serviceId,
        serviceName: serviceData.name,
        taskType: 'UPDATE'
      })
    } else {
      // 新增服务
      const taskId = await createServiceApi(serviceData)
      if (dialogMode === 'copy') {
        const targetEnvironment = environments.value.find(env => env.id === serviceData.environmentId)
        selectedEnv.value = serviceData.environmentId
        await nextTick()
        onTaskSubmitted(
          taskId,
          `服务「${serviceData.name}」复制到「${targetEnvironment?.name || '目标环境'}」`,
          {
            serviceId: null,
            serviceName: serviceData.name,
            taskType: 'CREATE'
          }
        )
      } else {
        onTaskSubmitted(taskId, `服务「${serviceData.name}」创建`, {
          serviceId: null,
          serviceName: serviceData.name,
          taskType: 'CREATE'
        })
      }
    }
  } catch (error) {
    console.error('保存服务失败:', error)
    toast.error(error.message || '保存失败，请重试')
  }
}
</script>

<template>
  <div class="app-container">
    <AppHeader 
      v-show="activeView === 'deploy'"
      :environments="environments"
      :selected-env="selectedEnv"
      :current-user="currentUser"
      :active-task-count="activeTaskCount"
      :tasks-open="showTaskDrawer"
      @update:selectedEnv="selectedEnv = $event"
      @add-environment="handleAddEnvironment"
      @delete-environment="handleDeleteEnvironment"
      @open-config="handleOpenConfig"
      @open-logs="handleOpenLogs"
      @open-tasks="handleOpenTasks"
      @open-login="handleOpenLogin"
      @logout="handleLogout"
    />

    <!-- 主内容区（部署视图） -->
    <main v-show="activeView === 'deploy'" class="main-content">
      <aside
        class="monitor-sidebar"
        :class="{ 'tasks-visible': showTaskDrawer }"
        aria-label="运行状态"
      >
        <HostMetricStrip
          :environment-id="selectedEnv"
          @host-click="handleHostClick"
        />
        <div v-show="showTaskDrawer" class="task-sidebar">
          <TaskDrawer
            ref="taskDrawerRef"
            :visible="showTaskDrawer"
            :environment-id="selectedEnv"
            @update:visible="showTaskDrawer = $event"
            @update:activeCount="activeTaskCount = $event"
            @update:activeTasks="activeTasks = $event"
            @task-finished="handleTaskFinished"
          />
        </div>
      </aside>

      <section class="service-workspace" aria-label="服务管理">
        <!-- 搜索和操作栏 -->
        <div class="toolbar">
          <div class="search-box">
            <svg class="search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/>
              <path d="m21 21-4.35-4.35"/>
            </svg>
            <input
              v-model="searchKeyword"
              type="text"
              placeholder="搜索服务名称或描述..."
              class="search-input"
            />
            <span
              class="workspace-refresh-indicator"
              :class="{ visible: servicesRefreshingVisible }"
              role="status"
              :aria-hidden="!servicesRefreshingVisible"
              aria-label="服务数据刷新中"
            >
              <span class="workspace-refresh-spinner" aria-hidden="true"></span>
              正在刷新
            </span>
          </div>

          <div class="toolbar-actions">
            <button
              class="status-filter-btn"
              :class="{ active: selectedStatus === 'all' }"
              @click="selectedStatus = 'all'"
            >
              全部
              <span class="count">{{ statusCounts.all }}</span>
            </button>
            <button
              class="status-filter-btn"
              :class="{ active: selectedStatus === 'running' }"
              @click="selectedStatus = 'running'"
            >
              运行中
              <span class="count success">{{ statusCounts.running }}</span>
            </button>
            <button
              class="status-filter-btn"
              :class="{ active: selectedStatus === 'deploying' }"
              @click="selectedStatus = 'deploying'"
            >
              部署中
              <span class="count warning">{{ statusCounts.deploying }}</span>
            </button>
            <button
              class="status-filter-btn"
              :class="{ active: selectedStatus === 'stopped' }"
              @click="selectedStatus = 'stopped'"
            >
              已停止
              <span class="count neutral">{{ statusCounts.stopped }}</span>
            </button>
            <button
              class="btn btn-secondary"
              :disabled="layoutDragging || layoutSaving"
              @click="openCreateGroupDialog"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 7h7l2 2h9v10H3z"/>
                <path d="M12 12v4M10 14h4"/>
              </svg>
              新建分组
            </button>
            <button class="btn btn-primary" @click="handleAddService">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              新增服务
            </button>
          </div>
        </div>

        <ServiceGroupBoard
          v-if="selectedEnv && (shouldShowServiceBoard || servicesBlockingLoadingVisible)"
          :environment-id="selectedEnv"
          :services="filteredServices"
          :groups="serviceGroups"
          :loading="servicesBlockingLoadingVisible"
          :drag-disabled="hasActiveServiceFilter"
          :layout-saving="layoutSaving"
          :blocking-task-by-service-id="blockingTaskByServiceId"
          @layout-change="handleLayoutChange"
          @dragging-change="layoutDragging = $event"
          @rename-group="openRenameGroupDialog"
          @delete-group="handleDeleteGroup"
          @update="updateService"
          @rollback="rollbackServiceHandler"
          @restart="restartServiceHandler"
          @stop="stopServiceHandler"
          @scale="scaleServiceHandler"
          @view="viewLogs"
          @edit="editConfig"
          @copy="copyService"
          @delete="deleteServiceHandler"
        />

        <!-- 空状态 -->
        <div v-if="!servicesBlockingLoadingVisible && !shouldShowServiceBoard" class="empty-state">
          <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="12" cy="12" r="10"/>
            <path d="M8 15h8M9 9h.01M15 9h.01"/>
          </svg>
          <h3>未找到服务</h3>
          <p v-if="hasActiveServiceFilter">当前搜索或状态条件下没有匹配的服务</p>
          <p v-else>当前环境还没有服务，可以先新增服务或创建分组</p>
        </div>
      </section>

    </main>

    <!-- 日志视图（首次打开才挂载，之后 v-show 保留查询现场） -->
    <div v-if="logsViewOpened" v-show="activeView === 'logs'" class="logs-view">
      <LogsPage
        :current-environment="currentEnvironmentInfo"
        :environments="environments"
        :active="activeView === 'logs'"
        @back="activeView = 'deploy'"
        @update:selected-env="selectedEnv = $event"
      />
    </div>

    <!-- 环境对话框 -->
    <EnvDialog
      :visible="showEnvDialog"
      @update:visible="showEnvDialog = $event"
      @confirm="handleConfirmEnvironment"
    />
    
    <!-- 服务对话框 -->
    <ServiceDialog
      :visible="showServiceDialog"
      :current-environment="environments.find(e => e.id === selectedEnv) || {}"
      :environments="environments"
      :mode="serviceDialogMode"
      :service="currentService"
      @update:visible="showServiceDialog = $event"
      @confirm="handleConfirmService"
    />

    <ServiceGroupDialog
      :visible="showGroupDialog"
      :mode="groupDialogMode"
      :group="currentGroup"
      :loading="groupDialogLoading"
      @close="closeGroupDialog"
      @confirm="handleConfirmGroup"
    />

    <ConfirmDialog
      v-model:visible="showDeleteGroupConfirm"
      title="确认删除分组"
      :message="deleteGroupConfirmMessage"
      @confirm="handleConfirmDeleteGroup"
      @cancel="handleCancelDeleteGroup"
    />
    
    <!-- 副本对话框 -->
    <ReplicasDialog
      :visible="showReplicasDialog"
      :service="selectedService || {}"
      :read-only="selectedServiceReadOnly"
      @update:visible="showReplicasDialog = $event"
    />
    
    <!-- 配置对话框 -->
    <ConfigDialog
      :visible="showConfigDialog"
      :environment="configEnvironment"
      @update:visible="showConfigDialog = $event"
      @confirm="handleConfirmConfig"
    />

    <LoginDialog
      :visible="showLoginDialog"
      :loading="loginLoading"
      @update:visible="showLoginDialog = $event"
      @confirm="handleLogin"
    />

    <!-- 宿主机详情弹窗 -->
    <HostDetailDialog
      :visible="showHostDetail"
      :host="selectedHost"
      @update:visible="showHostDetail = $event"
    />

  </div>
</template>

<style scoped>
.app-container {
  min-height: 100vh;
  background-color: var(--bg-primary);
}

/* 主内容区 */
.main-content {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  align-items: start;
  gap: 1.5rem;
  max-width: var(--content-max-width);
  margin: 0 auto;
  padding: 2rem;
}

.monitor-sidebar {
  position: sticky;
  top: calc(68px + 2rem);
  display: grid;
  gap: 0.75rem;
  min-width: 0;
  max-height: calc(100vh - 68px - 4rem);
}

.monitor-sidebar.tasks-visible {
  height: calc(100vh - 68px - 4rem);
  grid-template-rows: minmax(210px, 0.85fr) minmax(260px, 1.15fr);
}

.monitor-sidebar > :deep(.host-strip) {
  min-height: 0;
  max-height: 100%;
}

.task-sidebar {
  min-width: 0;
  min-height: 0;
  height: 100%;
}

.task-sidebar > :deep(.task-panel) {
  height: 100%;
}

@media (min-width: 1101px) {
  .monitor-sidebar.tasks-visible > :deep(.host-strip) {
    container-name: host-strip;
    container-type: size;
  }
}

.service-workspace {
  min-width: 0;
}

/* 日志视图：隐藏 header，占满整个视口 */
.logs-view {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 工具栏 */
.toolbar {
  position: sticky;
  top: 68px;
  z-index: 50;
  display: flex;
  gap: 1rem;
  margin-bottom: 1.25rem;
  padding: 0.75rem 0;
  flex-wrap: wrap;
  background: var(--bg-primary);
  border-bottom: 1px solid color-mix(in srgb, var(--border-color) 70%, transparent);
  box-shadow: 0 10px 18px -18px color-mix(in srgb, var(--text-primary) 45%, transparent);
}

.search-box {
  flex: 1;
  min-width: 300px;
  position: relative;
}

.search-icon {
  position: absolute;
  left: 1rem;
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-tertiary);
}

.search-input {
  width: 100%;
  padding: 0.625rem 7.5rem 0.625rem 3rem;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.95rem;
  transition: all 0.2s;
  height: 40px;
}

.workspace-refresh-indicator {
  position: absolute;
  top: 50%;
  right: 0.85rem;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  color: var(--primary-color);
  font-size: 0.75rem;
  font-weight: 600;
  pointer-events: none;
  opacity: 0;
  visibility: hidden;
  transform: translateY(-50%);
  transition: opacity 0.15s ease, visibility 0.15s ease;
}

.workspace-refresh-indicator.visible {
  opacity: 1;
  visibility: visible;
}

.workspace-refresh-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid color-mix(in srgb, var(--primary-color) 24%, transparent);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: workspace-refresh-spin 0.7s linear infinite;
}

@keyframes workspace-refresh-spin {
  to { transform: rotate(360deg); }
}

.search-input:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.toolbar-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
}

.status-filter-btn {
  padding: 0.625rem 1rem;
  border-radius: 10px;
  background: var(--bg-secondary);
  border: 2px solid var(--border-color);
  color: var(--text-primary);
  font-weight: 500;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  transition: all 0.2s;
  cursor: pointer;
  height: 40px;
}

.status-filter-btn:hover {
  border-color: var(--primary-color);
  background: var(--bg-hover);
}

.status-filter-btn.active {
  border-color: var(--primary-color);
  background: var(--primary-light);
  color: var(--primary-color);
}

.status-filter-btn .count {
  padding: 0.125rem 0.5rem;
  border-radius: 12px;
  background: var(--border-color);
  color: var(--text-primary);
  font-size: 0.75rem;
  font-weight: 600;
  min-width: 24px;
  text-align: center;
}

.status-filter-btn.active .count {
  background: var(--primary-color);
  color: white;
}

.count.success {
  background: var(--success-color);
  color: white;
}

.count.warning {
  background: var(--warning-color);
  color: white;
}

.count.neutral {
  background: var(--text-tertiary);
  color: white;
}

.count.danger {
  background: var(--danger-color);
  color: white;
}

.btn {
  padding: 0.625rem 1rem;
  border-radius: 10px;
  font-weight: 500;
  font-size: 0.9rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  transition: all 0.2s;
  height: 40px;
  cursor: pointer;
  border: none;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.25);
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.35);
}

.btn-secondary {
  background: var(--bg-secondary);
  border: 2px solid var(--border-color);
  color: var(--text-primary);
}

.btn-secondary:hover:not(:disabled) {
  border-color: var(--border-hover);
  background: var(--bg-hover);
}

/* 服务列表网格 */
.services-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.25rem;
}

.service-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1.25rem;
  transition: all 0.3s;
}

.service-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-xl);
  border-color: var(--primary-color);
}

.service-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 0.75rem;
}

.service-title-section {
  flex: 1;
}

.service-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.status-group {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.service-status {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.25rem 0.75rem;
  border-radius: 6px;
  background: color-mix(in srgb, var(--status-color) 10%, transparent);
  color: var(--status-color);
  font-size: 0.75rem;
  font-weight: 600;
}

.replicas-info {
  display: inline-flex;
  align-items: center;
  padding: 0.25rem 0.625rem;
  border-radius: 6px;
  background: var(--success-color);
  color: white;
  font-size: 0.75rem;
  font-weight: 600;
  font-family: 'Courier New', monospace;
}

.replicas-info.stopped {
  background: var(--danger-color);
}

.replicas-info.unhealthy {
  background: var(--warning-color);
}

.replicas-info.scaling {
  background: var(--info-color);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.service-version {
  padding: 0.25rem 0.625rem;
  background: var(--bg-hover);
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-secondary);
  font-family: 'Courier New', monospace;
}

.service-description {
  color: var(--text-secondary);
  font-size: 0.85rem;
  line-height: 1.5;
  margin-bottom: 0.875rem;
}

.service-info {
  display: flex;
  gap: 0.875rem;
  margin-bottom: 0.875rem;
  padding: 0.625rem;
  background: var(--bg-primary);
  border-radius: 8px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.info-item svg {
  color: var(--text-tertiary);
}

.service-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 0.875rem;
  border-top: 1px solid var(--border-color);
}

.last-deploy {
  font-size: 0.8rem;
  color: var(--text-tertiary);
}

.action-wrapper {
  position: relative;
}

.btn-action {
  padding: 0.5rem 1rem;
  background: var(--primary-gradient);
  color: white;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.85rem;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  transition: all 0.2s;
  box-shadow: 0 2px 6px rgba(102, 126, 234, 0.2);
}

.btn-action:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.action-menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 0.5rem);
  min-width: 160px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  box-shadow: var(--shadow-lg);
  padding: 0.5rem;
  z-index: 10;
  animation: slideDown 0.2s ease;
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.action-item {
  width: 100%;
  padding: 0.625rem 0.75rem;
  background: transparent;
  color: var(--text-primary);
  border-radius: 6px;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  text-align: left;
  transition: all 0.15s;
}

.action-item:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--primary-color);
}

.action-item:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.action-item.danger {
  color: var(--danger-color);
}

.action-item.danger:hover:not(:disabled) {
  background: color-mix(in srgb, var(--danger-color) 10%, transparent);
  color: var(--danger-color);
}

.action-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0.5rem 0;
}

/* 空状态 */
.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  color: var(--text-tertiary);
}

.empty-state svg {
  margin-bottom: 1rem;
}

.empty-state h3 {
  font-size: 1.25rem;
  color: var(--text-secondary);
  margin-bottom: 0.5rem;
}

/* 模态框 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.modal-content {
  background: var(--bg-secondary);
  border-radius: 16px;
  width: 90%;
  max-width: 480px;
  box-shadow: var(--shadow-xl);
  animation: slideUp 0.3s;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.modal-header {
  padding: 1.5rem;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.modal-header h3 {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
}

.modal-close {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  background: transparent;
  transition: all 0.2s;
}

.modal-close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.modal-body {
  padding: 1.5rem;
}

.input-label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
  color: var(--text-primary);
  font-size: 0.9rem;
}

.modal-input {
  width: 100%;
  padding: 0.75rem 1rem;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: 0.95rem;
  transition: all 0.2s;
}

.modal-input:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.modal-footer {
  padding: 1.5rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
}

/* 响应式设计 */
@media (max-width: 1100px) {
  .main-content {
    grid-template-columns: minmax(0, 1fr);
  }

  .monitor-sidebar {
    position: static;
    height: auto;
    max-height: none;
  }

  .monitor-sidebar.tasks-visible {
    height: auto;
    grid-template-rows: auto minmax(320px, 520px);
  }
}

@media (max-width: 768px) {
  .main-content {
    padding: 1rem;
  }

  .nav-menu {
    display: none;
  }
  
  .services-grid {
    grid-template-columns: 1fr;
  }
  
  .toolbar {
    flex-direction: column;
  }
  
  .search-box {
    min-width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workspace-refresh-indicator {
    transition: none;
  }

  .workspace-refresh-spinner {
    animation: none;
  }
}
</style>
