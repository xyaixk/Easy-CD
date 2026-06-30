<script setup>
import { ref, watch, onUnmounted, computed } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  environment: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:visible', 'confirm'])

const formData = ref({
  swarmManagerHosts: '',
  registryUrl: '',
  networkMode: 'overlay'
})

const isDockerSwarm = computed(() => {
  return props.environment?.deployType === 'docker'
})

const resetForm = () => {
  formData.value = {
    swarmManagerHosts: '',
    registryUrl: '',
    networkMode: 'overlay'
  }
}

const loadConfig = () => {
  if (!props.environment?.config) {
    resetForm()
    return
  }
  
  try {
    const config = JSON.parse(props.environment.config)
    formData.value = {
      swarmManagerHosts: Array.isArray(config.swarmManagerHosts) 
        ? config.swarmManagerHosts.join('\n') 
        : (config.swarmManagerHosts || ''),
      registryUrl: config.registryUrl || '',
      networkMode: config.networkMode || 'overlay'
    }
  } catch (error) {
    console.error('解析配置失败:', error)
    resetForm()
  }
}

watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    loadConfig()
  } else {
    document.body.style.overflow = ''
  }
})

const handleClose = () => {
  emit('update:visible', false)
}

const validateForm = () => {
  if (!formData.value.swarmManagerHosts.trim()) {
    alert('请输入Swarm Manager地址')
    return false
  }
  if (!formData.value.registryUrl.trim()) {
    alert('请输入默认镜像仓库地址')
    return false
  }
  return true
}

const handleConfirm = () => {
  if (!validateForm()) {
    return
  }
  
  // 将多行地址转为数组
  const hosts = formData.value.swarmManagerHosts
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0)
  
  const submitData = {
    swarmManagerHosts: hosts,
    registryUrl: formData.value.registryUrl.trim(),
    networkMode: formData.value.networkMode
  }
  
  emit('confirm', submitData)
  handleClose()
}

onUnmounted(() => {
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay">
        <div class="dialog-container" @click.stop>
          <div class="dialog-header">
            <div class="header-content">
              <div class="header-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
              </div>
              <div class="header-text">
                <h3>环境配置</h3>
                <p class="env-info">{{ environment?.name }} ({{ environment?.deployType }})</p>
              </div>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
          <div class="dialog-body">
            <div v-if="isDockerSwarm" class="config-section">
              <!-- Swarm Manager 地址 -->
              <div class="form-group">
                <label>Swarm Manager 地址 <span class="required">*</span></label>
                <textarea 
                  v-model="formData.swarmManagerHosts"
                  placeholder="tcp://10.10.0.22:2375&#10;tcp://10.10.0.23:2375&#10;tcp://10.10.0.24:2375"
                  class="form-textarea"
                  rows="4"
                />
                <div class="form-hint">每行一个Manager节点地址，格式：tcp://IP:端口</div>
              </div>
              
              <!-- 镜像仓库地址 -->
              <div class="form-group">
                <label>默认镜像仓库地址 <span class="required">*</span></label>
                <input 
                  v-model="formData.registryUrl"
                  type="text" 
                  placeholder="harbor.example.com"
                  class="form-input"
                />
                <div class="form-hint">默认的Docker镜像仓库地址。创建服务时，如果镜像未指定仓库地址，将自动使用此默认值</div>
              </div>
              
              <!-- 网络模式 -->
              <div class="form-group">
                <label>网络模式 <span class="required">*</span></label>
                <select v-model="formData.networkMode" class="form-select">
                  <option value="overlay">overlay（推荐）</option>
                  <option value="bridge">bridge</option>
                  <option value="host">host</option>
                </select>
                <div class="form-hint">Swarm集群推荐使用overlay网络模式</div>
              </div>
            </div>
            
            <div v-else class="empty-config">
              <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <circle cx="12" cy="12" r="10"/>
                <path d="M12 16v-4M12 8h.01"/>
              </svg>
              <p>当前部署类型暂不支持配置</p>
            </div>
          </div>
      
          <div class="dialog-footer">
            <button class="btn btn-secondary" @click="handleClose">取消</button>
            <button class="btn btn-primary" @click="handleConfirm" :disabled="!isDockerSwarm">
              保存配置
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 1rem;
}

.dialog-container {
  background: white;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  width: 100%;
  max-width: 600px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dialog-header {
  padding: 1.5rem 2rem;
  background: var(--primary-gradient);
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex: 1;
}

.header-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(10px);
}

.header-text h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.env-info {
  margin: 0.25rem 0 0 0;
  font-size: 0.875rem;
  opacity: 0.9;
  font-weight: 400;
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
  transition: all 0.2s;
  cursor: pointer;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: rotate(90deg);
}

.dialog-body {
  padding: 2rem;
  overflow-y: auto;
  flex: 1;
}

.dialog-body::-webkit-scrollbar {
  width: 6px;
}

.dialog-body::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}

.dialog-body::-webkit-scrollbar-thumb:hover {
  background: var(--text-tertiary);
}

.config-section {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.form-group {
  margin-bottom: 0;
}

.form-group label {
  display: block;
  margin-bottom: 0.5rem;
  color: var(--text-primary);
  font-weight: 500;
  font-size: 0.9rem;
}

.required {
  color: var(--danger-color);
}

.optional {
  color: var(--text-tertiary);
  font-weight: 400;
  font-size: 0.85rem;
}

.form-input,
.form-select,
.form-textarea {
  width: 100%;
  padding: 0.75rem 1rem;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.95rem;
  transition: all 0.2s;
  font-family: inherit;
}

.form-input:focus,
.form-select:focus,
.form-textarea:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
  outline: none;
}

.form-textarea {
  resize: vertical;
  min-height: 100px;
  font-family: 'Consolas', 'Monaco', monospace;
}

.form-hint {
  margin-top: 0.5rem;
  font-size: 0.8rem;
  color: var(--text-tertiary);
  line-height: 1.4;
}

.empty-config {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1rem;
  color: var(--text-tertiary);
}

.empty-config svg {
  margin-bottom: 1rem;
  opacity: 0.5;
}

.empty-config p {
  font-size: 1rem;
  margin: 0;
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

.btn-primary {
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.25);
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.35);
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
