<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import AppHeader from './components/AppHeader.vue'
import ServiceCard from './components/ServiceCard.vue'
import EnvDialog from './components/EnvDialog.vue'
import ServiceDialog from './components/ServiceDialog.vue'
import ReplicasDialog from './components/ReplicasDialog.vue'
import ConfigDialog from './components/ConfigDialog.vue'
import toast from '@/utils/toast'
import { listEnvironments, addEnvironment as addEnvApi, deleteEnvironment as deleteEnvApi, updateEnvironment as updateEnvApi } from '@/api/environment'
import { 
  createService as createServiceApi, 
  updateService as updateServiceApi, 
  deleteService as deleteServiceApi, 
  listServices,
  restartService,
  stopService,
  rollbackService,
  scaleService
} from '@/api/service'

// 环境列表
const environments = ref([])

// 当前选中的环境
const selectedEnv = ref(1)

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

// 显示副本对话框
const showReplicasDialog = ref(false)
const selectedService = ref(null)

// 显示配置对话框
const showConfigDialog = ref(false)
const configEnvironment = ref(null)

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

// 服务列表数据
const services = ref([])

// 定时刷新相关
let refreshTimer = null
const REFRESH_INTERVAL = 5000 // 5秒

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
      selectedEnv.value = data[0].id
    }
  } catch (error) {
    console.error('加载环境列表失败:', error)
  }
}

// 加载服务列表
const loadServices = async () => {
  if (!selectedEnv.value) {
    services.value = []
    return
  }
  
  try {
    const data = await listServices(selectedEnv.value)
    // 转换为前端需要的格式
    const newServices = data.map(service => ({
      id: service.id,
      name: service.name,
      description: service.description || '',
      version: service.version,
      status: service.status || 'unknown',
      lastDeploy: service.deployTime || service.createdTime,
      branch: 'main', // 后端暂无此字段
      healthyInstances: service.healthyInstances || 0,
      instances: service.instances || 0,
      desiredInstances: service.desiredInstances || 0,
      // Docker配置信息（编辑时需要）
      dockerImage: service.dockerImage || '',
      dockerParams: service.dockerParams || '',
      // 监控指标
      cpuPercent: service.cpuPercent || 0,
      memoryUsage: service.memoryUsage || 0,
      memoryLimit: service.memoryLimit || 0,
      memoryPercent: service.memoryPercent || 0,
      networkRxRate: service.networkRxRate || 0,
      networkTxRate: service.networkTxRate || 0,
      diskReadRate: service.diskReadRate || 0,
      diskWriteRate: service.diskWriteRate || 0
    }))
    
    // 只更新有变化的服务
    updateChangedServices(newServices)
  } catch (error) {
    console.error('加载服务列表失败:', error)
    // 静默失败，避免定时刷新时频繁提示错误
  }
}

// 智能更新：只更新有变化的服务
const updateChangedServices = (newServices) => {
  if (services.value.length === 0) {
    // 首次加载，直接赋值
    services.value = newServices
    return
  }
  
  const oldServicesMap = new Map(services.value.map(s => [s.id, s]))
  const newServicesMap = new Map(newServices.map(s => [s.id, s]))
  
  // 检查是否有服务被删除或新增
  const hasStructureChange = 
    services.value.length !== newServices.length ||
    services.value.some(s => !newServicesMap.has(s.id)) ||
    newServices.some(s => !oldServicesMap.has(s.id))
  
  if (hasStructureChange) {
    // 服务列表结构发生变化，整体更新
    services.value = newServices
    return
  }
  
  // 只更新有变化的服务
  newServices.forEach((newService, index) => {
    const oldService = oldServicesMap.get(newService.id)
    if (oldService && hasServiceChanged(oldService, newService)) {
      // 找到对应位置并更新
      const serviceIndex = services.value.findIndex(s => s.id === newService.id)
      if (serviceIndex !== -1) {
        services.value[serviceIndex] = newService
      }
    }
  })
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
    // 监控指标变化检测
    oldService.cpuPercent !== newService.cpuPercent ||
    oldService.memoryUsage !== newService.memoryUsage ||
    oldService.networkRxRate !== newService.networkRxRate ||
    oldService.networkTxRate !== newService.networkTxRate ||
    oldService.diskReadRate !== newService.diskReadRate ||
    oldService.diskWriteRate !== newService.diskWriteRate
}

// 启动定时刷新
const startAutoRefresh = () => {
  stopAutoRefresh() // 先清除可能存在的旧定时器
  refreshTimer = setInterval(() => {
    // 如果有对话框打开，暂停刷新避免冲突
    if (!showEnvDialog.value && !showServiceDialog.value && 
        !showReplicasDialog.value && !showConfigDialog.value) {
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

// 监听环境切换，重新加载服务列表
watch(selectedEnv, () => {
  loadServices()
})

// 页面加载时获取环境列表和服务列表
onMounted(() => {
  loadEnvironments()
  loadServices()
  startAutoRefresh() // 启动定时刷新
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

// 服务操作方法
// 更新操作：重新部署服务（拉取最新镜像）
const updateService = async (service) => {
  try {
    // 调用后端的 update 接口，触发重新部署
    // 后端会重新拉取镜像并执行滚动更新
    await updateServiceApi(service.id, {
      name: service.name,
      description: service.description,
      dockerImage: service.dockerImage,
      dockerParams: service.dockerParams
    })
    toast.success(`服务「${service.name}」更新部署成功`)
    await loadServices()
  } catch (error) {
    console.error('更新服务失败:', error)
    toast.error(error.message || '更新失败，请重试')
  }
}

const rollbackServiceHandler = async ({ service, version }) => {
  try {
    await rollbackService(service.id, version)
    toast.success(`服务「${service.name}」回滚成功`)
    await loadServices()
  } catch (error) {
    console.error('回滚服务失败:', error)
    toast.error(error.message || '回滚失败，请重试')
  }
}

const restartServiceHandler = async (service) => {
  try {
    await restartService(service.id)
    toast.success(`服务「${service.name}」重启成功`)
    await loadServices()
  } catch (error) {
    console.error('重启服务失败:', error)
    toast.error(error.message || '重启失败，请重试')
  }
}

const stopServiceHandler = async (service) => {
  try {
    await stopService(service.id)
    toast.success(`服务「${service.name}」已停止`)
    await loadServices()
  } catch (error) {
    console.error('停止服务失败:', error)
    toast.error(error.message || '停止失败，请重试')
  }
}

const scaleServiceHandler = async ({ service, replicas }) => {
  try {
    await scaleService(service.id, replicas)
    toast.success(`服务「${service.name}」副本数已调整为 ${replicas}`)
    await loadServices()
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
  currentService.value = service
  showServiceDialog.value = true
}

const deleteServiceHandler = async (service) => {
  try {
    await deleteServiceApi(service.id)
    toast.success(`服务「${service.name}」已删除`)
    await loadServices()
  } catch (error) {
    console.error('删除服务失败:', error)
    toast.error(error.message || '删除失败，请重试')
  }
}

// 打开新增服务对话框
const handleAddService = () => {
  currentService.value = null
  showServiceDialog.value = true
}

// 确认新增/编辑服务
const handleConfirmService = async (serviceData) => {
  try {
    if (currentService.value) {
      // 编辑服务
      const result = await updateServiceApi(currentService.value.id, serviceData)
      toast.success(`服务「${result.name}」修改成功`)
    } else {
      // 新增服务
      const result = await createServiceApi(serviceData)
      toast.success(`服务「${result.name}」创建成功`)
    }
    // 重新加载服务列表
    await loadServices()
  } catch (error) {
    console.error('保存服务失败:', error)
    toast.error(error.message || '保存失败，请重试')
  }
}
</script>

<template>
  <div class="app-container">
    <AppHeader 
      :environments="environments"
      :selected-env="selectedEnv"
      @update:selectedEnv="selectedEnv = $event"
      @add-environment="handleAddEnvironment"
      @delete-environment="handleDeleteEnvironment"
      @open-config="handleOpenConfig"
    />

    <!-- 主内容区 -->
    <main class="main-content">
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
          <button class="btn btn-primary" @click="handleAddService">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            新增服务
          </button>
        </div>
      </div>

      <!-- 服务列表 -->
      <div class="services-grid">
        <ServiceCard
          v-for="service in filteredServices" 
          :key="service.id"
          :service="service"
          @update="updateService"
          @rollback="rollbackServiceHandler"
          @restart="restartServiceHandler"
          @stop="stopServiceHandler"
          @scale="scaleServiceHandler"
          @view="viewLogs"
          @edit="editConfig"
          @delete="deleteServiceHandler"
        />
      </div>

      <!-- 空状态 -->
      <div v-if="filteredServices.length === 0" class="empty-state">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/>
          <path d="M8 15h8M9 9h.01M15 9h.01"/>
        </svg>
        <h3>未找到服务</h3>
        <p>没有匹配 "{{ searchKeyword }}" 的服务</p>
      </div>
    </main>

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
      :service="currentService"
      @update:visible="showServiceDialog = $event"
      @confirm="handleConfirmService"
    />
    
    <!-- 副本对话框 -->
    <ReplicasDialog
      :visible="showReplicasDialog"
      :service="selectedService || {}"
      @update:visible="showReplicasDialog = $event"
    />
    
    <!-- 配置对话框 -->
    <ConfigDialog
      :visible="showConfigDialog"
      :environment="configEnvironment"
      @update:visible="showConfigDialog = $event"
      @confirm="handleConfirmConfig"
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
  max-width: 1400px;
  margin: 0 auto;
  padding: 2rem;
}

/* 工具栏 */
.toolbar {
  display: flex;
  gap: 1rem;
  margin-bottom: 2rem;
  flex-wrap: wrap;
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
  padding: 0.625rem 1rem 0.625rem 3rem;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.95rem;
  transition: all 0.2s;
  height: 40px;
}

.search-input:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.toolbar-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
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

.btn-primary {
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.25);
}

.btn-primary:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.35);
}

.btn-secondary {
  background: var(--bg-secondary);
  border: 2px solid var(--border-color);
  color: var(--text-primary);
}

.btn-secondary:hover {
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
@media (max-width: 768px) {
  .header-content {
    padding: 0 1rem;
  }
  
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
</style>
