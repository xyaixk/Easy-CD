<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { getServiceLogsUrl } from '@/api/service'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  replica: {
    type: Object,
    default: () => ({})
  },
  serviceId: {
    type: Number,
    required: true
  }
})

const emit = defineEmits(['update:visible'])

// 日志内容
const logs = ref([])
const isLoading = ref(false)
const isFollowing = ref(false)
const autoScroll = ref(true)

// SSE 连接
let eventSource = null
const logsContainer = ref(null)

// 监听对话框显示状态
watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    loadLogs()
  } else {
    document.body.style.overflow = ''
    closeLogs()
  }
})

// 加载日志
const loadLogs = () => {
  logs.value = []
  isLoading.value = true
  
  try {
    // 直接使用 serviceId 获取服务聚合日志
    const url = getServiceLogsUrl(props.serviceId, 500, isFollowing.value)
    
    eventSource = new EventSource(url)
    
    eventSource.addEventListener('log', (event) => {
      logs.value.push(event.data)
      
      // 限制最大行数，防止内存溢出（最多保留10000行）
      const maxLines = 10000
      if (logs.value.length > maxLines) {
        logs.value = logs.value.slice(-maxLines)
      }
      
      isLoading.value = false
      
      // 自动滚动到底部
      if (autoScroll.value) {
        setTimeout(scrollToBottom, 50)
      }
    })
    
    eventSource.onerror = (error) => {
      console.error('日志流错误:', error, '连接状态:', eventSource.readyState, '实时模式:', isFollowing.value)
      isLoading.value = false
      
      // 静态模式下,连接关闭是正常的(日志读取完毕)
      if (!isFollowing.value) {
        // 静态模式:静默关闭连接,不显示任何提示
        closeLogs()
        return
      }
      
      // 实时模式下的错误处理
      if (eventSource.readyState === EventSource.CLOSED) {
        logs.value.push('\n--- 日志流已关闭 ---')
      } else if (eventSource.readyState === EventSource.CONNECTING) {
        logs.value.push('\n--- 日志流重连中... ---')
        return // 不关闭连接,等待自动重连
      } else {
        logs.value.push('\n--- 日志流连接错误 ---')
      }
      closeLogs()
    }
    
  } catch (error) {
    console.error('加载日志失败:', error)
    logs.value = ['加载日志失败: ' + error.message]
    isLoading.value = false
  }
}

// 滚动到底部
const scrollToBottom = () => {
  if (logsContainer.value) {
    logsContainer.value.scrollTop = logsContainer.value.scrollHeight
  }
}

// 切换实时推送
const toggleFollow = () => {
  if (isFollowing.value) {
    // 从实时切换到静态:关闭SSE连接,保留当前日志
    closeLogs()
    isFollowing.value = false
  } else {
    // 从静态切换到实时:重新加载日志并开启实时推送
    isFollowing.value = true
    closeLogs()
    loadLogs()
  }
}

// 切换自动滚动
const toggleAutoScroll = () => {
  autoScroll.value = !autoScroll.value
  if (autoScroll.value) {
    scrollToBottom()
  }
}

// 清空日志
const clearLogs = () => {
  logs.value = []
}

// 下载日志
const downloadLogs = () => {
  const content = logs.value.join('\n')
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  const serviceName = props.replica?.name || 'service'
  a.download = `${serviceName}-logs-${Date.now()}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// 关闭日志流
const closeLogs = () => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

const handleClose = () => {
  emit('update:visible', false)
}

onUnmounted(() => {
  document.body.style.overflow = ''
  closeLogs()
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
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="16" y1="13" x2="8" y2="13"/>
                  <line x1="16" y1="17" x2="8" y2="17"/>
                  <polyline points="10 9 9 9 8 9"/>
                </svg>
              </div>
              <div>
                <h3>服务日志</h3>
                <p class="replica-name">{{ replica?.name || '' }}</p>
              </div>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
          <div class="dialog-toolbar">
            <button 
              class="toolbar-btn"
              :class="{ active: isFollowing }"
              @click="toggleFollow"
              title="实时推送"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                <polyline points="17 6 23 6 23 12"/>
              </svg>
              {{ isFollowing ? '实时' : '静态' }}
            </button>
            
            <button 
              class="toolbar-btn"
              :class="{ active: autoScroll }"
              @click="toggleAutoScroll"
              title="自动滚动"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <polyline points="19 12 12 19 5 12"/>
              </svg>
              自动滚动
            </button>
            
            <button 
              class="toolbar-btn"
              @click="clearLogs"
              title="清空日志"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
              </svg>
              清空
            </button>
            
            <button 
              class="toolbar-btn"
              @click="downloadLogs"
              title="下载日志"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                <polyline points="7 10 12 15 17 10"/>
                <line x1="12" y1="15" x2="12" y2="3"/>
              </svg>
              下载
            </button>
            
            <div class="toolbar-info">
              <span>共 {{ logs.length }} 行</span>
            </div>
          </div>
          
          <div class="dialog-body">
            <div v-if="isLoading" class="loading-state">
              <div class="loading-spinner"></div>
              <p>正在加载日志...</p>
            </div>
            
            <div v-else class="logs-container" ref="logsContainer">
              <div v-if="logs.length === 0" class="empty-logs">
                暂无日志内容
              </div>
              <pre v-else class="logs-content">{{ logs.join('\n') }}</pre>
            </div>
          </div>
        </div>
      </div>
    </Transition>
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
}

.dialog-container {
  background: var(--bg-secondary);
  border-radius: 0;
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  box-shadow: none;
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

.replica-name {
  margin: 0;
  font-size: 0.875rem;
  color: rgba(255, 255, 255, 0.8);
  margin-top: 0.25rem;
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

.dialog-toolbar {
  padding: 1rem 2rem;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
  background: var(--bg-primary);
}

.toolbar-btn {
  padding: 0.5rem 1rem;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
}

.toolbar-btn:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
  background: var(--primary-light);
}

.toolbar-btn.active {
  border-color: var(--primary-color);
  background: var(--primary-color);
  color: white;
}

.toolbar-info {
  margin-left: auto;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.dialog-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem;
  color: var(--text-secondary);
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-color);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 1rem;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.logs-container {
  flex: 1;
  overflow-y: auto;
  background: #1e1e1e;
  padding: 1.5rem;
}

.logs-container::-webkit-scrollbar {
  width: 8px;
}

.logs-container::-webkit-scrollbar-track {
  background: #2d2d2d;
}

.logs-container::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.logs-container::-webkit-scrollbar-thumb:hover {
  background: #666;
}

.empty-logs {
  text-align: center;
  padding: 4rem;
  color: #888;
  font-size: 0.875rem;
}

.logs-content {
  margin: 0;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 0.875rem;
  line-height: 1.6;
  color: #d4d4d4;
  white-space: pre-wrap;
  word-break: break-all;
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
