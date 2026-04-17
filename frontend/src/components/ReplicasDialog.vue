<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { getServiceReplicas } from '@/api/service'
import LogViewerDialog from './LogViewerDialog.vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  service: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['update:visible'])

// 副本列表数据
const replicas = ref([])

// 日志查看对话框
const showLogViewer = ref(false)
const selectedReplica = ref(null)

// 定时刷新相关
let refreshTimer = null
const REFRESH_INTERVAL = 1000 // 1秒刷新一次

// 监听对话框显示状态
watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    loadReplicas()
    startAutoRefresh()
  } else {
    document.body.style.overflow = ''
    stopAutoRefresh()
  }
})

// 加载副本列表数据
const loadReplicas = async () => {
  if (!props.service?.id) return
  
  try {
    const data = await getServiceReplicas(props.service.id)
    // 过滤掉已停止的副本，只显示活跃的副本
    replicas.value = (data || []).filter(r => {
      const status = r.status?.toLowerCase()
      return status !== 'shutdown' && status !== 'complete' && status !== 'remove'
    })
  } catch (error) {
    console.error('加载副本列表失败:', error)
    // 如果API调用失败，使用模拟数据作为降级方案
    loadMockReplicas()
  }
}

// 模拟数据（降级方案）
const loadMockReplicas = () => {
  const instances = props.service.instances || 3
  replicas.value = Array.from({ length: instances }, (_, i) => ({
    id: `${props.service.name}.${i + 1}`,
    name: `${props.service.name}.${i + 1}`,
    status: i === 0 ? 'running' : (i === instances - 1 ? 'starting' : 'running'),
    node: `node-${(i % 3) + 1}`,
    uptime: i === instances - 1 ? '刚刚' : `${Math.floor(Math.random() * 24) + 1}小时`
  }))
}

// 启动定时刷新
const startAutoRefresh = () => {
  stopAutoRefresh() // 先清除可能存在的旧定时器
  refreshTimer = setInterval(() => {
    loadReplicas()
  }, REFRESH_INTERVAL)
}

// 停止定时刷新
const stopAutoRefresh = () => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

const handleClose = () => {
  emit('update:visible', false)
}

const getStatusClass = (status) => {
  const statusMap = {
    running: 'status-success',
    starting: 'status-warning',
    preparing: 'status-warning',
    ready: 'status-warning',
    assigned: 'status-warning',
    accepted: 'status-warning',
    stopped: 'status-neutral',
    shutdown: 'status-neutral',
    complete: 'status-neutral',
    remove: 'status-neutral',
    failed: 'status-danger',
    rejected: 'status-danger',
    error: 'status-danger'
  }
  return statusMap[status] || 'status-neutral'
}

const getStatusText = (status) => {
  const statusMap = {
    running: '运行中',
    starting: '启动中',
    preparing: '准备中',
    ready: '就绪',
    assigned: '已分配',
    accepted: '已接受',
    stopped: '已停止',
    shutdown: '关闭中',
    complete: '已完成',
    remove: '移除中',
    failed: '失败',
    rejected: '已拒绝',
    error: '异常'
  }
  return statusMap[status] || status
}

// 查看服务日志
const handleViewServiceLogs = () => {
  console.log('查看服务日志:', props.service)
  selectedReplica.value = { name: props.service.name } // 用于日志对话框标题
  showLogViewer.value = true
}

// 副本操作按钮（暂无功能）
const handleViewLogs = (replica) => {
  console.log('副本日志功能暂未开放:', replica)
}

const handleEnterContainer = (replica) => {
  console.log('进入容器功能暂未开放:', replica)
}

onUnmounted(() => {
  document.body.style.overflow = ''
  stopAutoRefresh()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay" @click="handleClose">
        <div class="dialog-container" @click.stop>
          <div class="dialog-header">
            <div class="header-content">
              <div class="header-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                  <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
                  <line x1="12" y1="22.08" x2="12" y2="12"/>
                </svg>
              </div>
              <h3>{{ service.name }} - 副本列表</h3>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
          <div class="dialog-body">
            <div class="replicas-summary">
              <div class="summary-item">
                <span class="summary-label">总副本数</span>
                <span class="summary-value">{{ replicas.length }}</span>
              </div>
              <div class="summary-item">
                <span class="summary-label">运行中</span>
                <span class="summary-value success">{{ replicas.filter(r => r.status === 'running').length }}</span>
              </div>
              <div class="summary-item">
                <span class="summary-label">启动中</span>
                <span class="summary-value warning">{{ replicas.filter(r => ['starting', 'preparing', 'ready', 'assigned', 'accepted'].includes(r.status)).length }}</span>
              </div>
              <div class="summary-item">
                <span class="summary-label">失败/异常</span>
                <span class="summary-value danger">{{ replicas.filter(r => ['failed', 'rejected', 'error'].includes(r.status)).length }}</span>
              </div>
              <div class="summary-item">
                <button class="btn btn-view-logs" @click="handleViewServiceLogs">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                    <polyline points="14 2 14 8 20 8"/>
                    <line x1="16" y1="13" x2="8" y2="13"/>
                    <line x1="16" y1="17" x2="8" y2="17"/>
                    <polyline points="10 9 9 9 8 9"/>
                  </svg>
                  查看日志
                </button>
              </div>
            </div>

            <div class="replicas-list">
              <div 
                v-for="replica in replicas" 
                :key="replica.id"
                class="replica-card"
              >
                <div class="replica-header">
                  <div class="replica-name">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="2" y="3" width="20" height="14" rx="2"/>
                      <path d="M8 21h8M12 17v4"/>
                    </svg>
                    {{ replica.name }}
                  </div>
                  <div class="replica-header-right">
                    <div class="replica-actions-inline">
                      <button 
                        class="btn-icon" 
                        @click="handleViewLogs(replica)"
                        :disabled="true"
                        title="副本日志功能暂未开放"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                          <polyline points="14 2 14 8 20 8"/>
                          <line x1="16" y1="13" x2="8" y2="13"/>
                          <line x1="16" y1="17" x2="8" y2="17"/>
                          <polyline points="10 9 9 9 8 9"/>
                        </svg>
                      </button>
                      <button 
                        class="btn-icon" 
                        @click="handleEnterContainer(replica)"
                        :disabled="true"
                        title="进入容器功能暂未开放"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                          <polyline points="4 17 10 11 4 5"/>
                          <line x1="12" y1="19" x2="20" y2="19"/>
                        </svg>
                      </button>
                    </div>
                    <span class="replica-status" :class="getStatusClass(replica.status)">
                      {{ getStatusText(replica.status) }}
                    </span>
                  </div>
                </div>
                
                <div class="replica-details">
                  <div class="detail-item">
                    <span class="detail-label">节点</span>
                    <span class="detail-value">{{ replica.nodeIp || replica.node }}</span>
                  </div>
                  <div class="detail-item">
                    <span class="detail-label">容器ID</span>
                    <span class="detail-value">{{ replica.containerId || '-' }}</span>
                  </div>
                  <div class="detail-item">
                    <span class="detail-label">运行时间</span>
                    <span class="detail-value">{{ replica.uptime || '-' }}</span>
                  </div>
                  <div class="detail-item">
                    <span class="detail-label">重启次数</span>
                    <span class="detail-value">{{ replica.restartCount || 0 }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
          
          <div class="dialog-footer">
            <button class="btn btn-secondary" @click="handleClose">关闭</button>
          </div>
        </div>
      </div>
    </Transition>
    
    <!-- 日志查看对话框 -->
    <LogViewerDialog
      :visible="showLogViewer"
      :replica="selectedReplica || {}"
      :service-id="service.id"
      @update:visible="showLogViewer = $event"
    />
  </Teleport>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 1rem;
}

.dialog-container {
  background: var(--bg-secondary);
  border-radius: 16px;
  width: 100%;
  max-width: 800px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  overflow: hidden;
}

.dialog-header {
  padding: 1.5rem 2rem;
  background: var(--primary-gradient);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.header-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: white;
}

.btn-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.dialog-body {
  padding: 2rem;
  overflow-y: auto;
  flex: 1;
}

.dialog-body::-webkit-scrollbar {
  width: 8px;
}

.dialog-body::-webkit-scrollbar-track {
  background: var(--bg-primary);
  border-radius: 4px;
}

.dialog-body::-webkit-scrollbar-thumb {
  background: var(--text-tertiary);
  border-radius: 4px;
}

.dialog-body::-webkit-scrollbar-thumb:hover {
  background: var(--text-secondary);
}

.replicas-summary {
  display: flex;
  gap: 1.5rem;
  margin-bottom: 2rem;
  padding: 1.5rem;
  background: var(--bg-primary);
  border-radius: 12px;
  border: 1px solid var(--border-color);
}

.summary-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.summary-item:last-child {
  flex: initial;
  min-width: auto;
  justify-content: center;
}

.btn-view-logs {
  padding: 0.75rem 1.25rem;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.9rem;
  border: none;
  background: var(--primary-gradient);
  color: white;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
}

.btn-view-logs:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.btn-view-logs:active {
  transform: translateY(0);
}

.summary-label {
  font-size: 0.85rem;
  color: var(--text-secondary);
}

.summary-value {
  font-size: 1.75rem;
  font-weight: 600;
  color: var(--text-primary);
}

.summary-value.success {
  color: var(--success-color);
}

.summary-value.warning {
  color: var(--warning-color);
}

.summary-value.danger {
  color: var(--danger-color);
}

.replicas-list {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.replica-card {
  padding: 1.25rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  transition: all 0.2s;
}

.replica-card:hover {
  border-color: var(--primary-color);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.1);
}

.replica-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.75rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border-color);
}

.replica-header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.replica-actions-inline {
  display: flex;
  gap: 0.5rem;
}

.btn-icon {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-icon:hover:not(:disabled) {
  border-color: var(--primary-color);
  color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 5%, transparent);
  transform: translateY(-1px);
}

.btn-icon:active:not(:disabled) {
  transform: translateY(0);
}

.btn-icon:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.replica-name {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-weight: 600;
  color: var(--text-primary);
  font-size: 1rem;
}

.replica-name svg {
  color: var(--primary-color);
}

.replica-status {
  padding: 0.375rem 0.75rem;
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 500;
}

.status-success {
  background: color-mix(in srgb, var(--success-color) 15%, transparent);
  color: var(--success-color);
}

.status-warning {
  background: color-mix(in srgb, var(--warning-color) 15%, transparent);
  color: var(--warning-color);
}

.status-danger {
  background: color-mix(in srgb, var(--danger-color) 15%, transparent);
  color: var(--danger-color);
}

.status-neutral {
  background: color-mix(in srgb, var(--text-tertiary) 15%, transparent);
  color: var(--text-tertiary);
}

.replica-details {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.75rem;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.detail-label {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.detail-value {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary);
}

.node-ip {
  color: var(--text-secondary);
  font-weight: 400;
  margin-left: 0.25rem;
}

.dialog-footer {
  padding: 1.5rem 2rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
  flex-shrink: 0;
  background: var(--bg-secondary);
}

.btn {
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.9rem;
  transition: all 0.2s;
  cursor: pointer;
  border: none;
}

.btn-secondary {
  background: white;
  border: 2px solid var(--border-color);
  color: var(--text-primary);
}

.btn-secondary:hover {
  border-color: var(--border-hover);
  background: var(--bg-hover);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.3s;
}

.dialog-fade-enter-active .dialog-container,
.dialog-fade-leave-active .dialog-container {
  transition: transform 0.3s, opacity 0.3s;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-from .dialog-container,
.dialog-fade-leave-to .dialog-container {
  transform: scale(0.9);
  opacity: 0;
}
</style>
