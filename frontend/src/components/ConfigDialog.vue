<script setup>
import { ref, watch, computed } from 'vue'
import { useBodyScrollLock } from '@/composables/useBodyScrollLock'

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

useBodyScrollLock(() => props.visible)

const emit = defineEmits(['update:visible', 'confirm'])

const formData = ref({
  sshHosts: [{ host: '', port: 22, username: 'root', password: '', privateKey: '' }],
  registryUrl: '',
  networkMode: 'overlay',
  lokiUri: '',
  lokiNamespace: 'ysb',
  lokiAppEnv: ''
})

const isDockerSwarm = computed(() => {
  return props.environment?.deployType === 'docker'
})

const resetForm = () => {
  formData.value = {
    sshHosts: [{ host: '', port: 22, username: 'root', password: '', privateKey: '' }],
    registryUrl: '',
    networkMode: 'overlay',
    lokiUri: '',
    lokiNamespace: 'ysb',
    lokiAppEnv: ''
  }
}

const addSshHost = () => {
  formData.value.sshHosts.push({ host: '', port: 22, username: 'root', password: '', privateKey: '' })
}

const removeSshHost = (index) => {
  if (formData.value.sshHosts.length > 1) {
    formData.value.sshHosts.splice(index, 1)
  }
}

const loadConfig = () => {
  if (!props.environment?.config) {
    resetForm()
    return
  }
  
  try {
    const config = JSON.parse(props.environment.config)
    let sshHosts = []
    
    if (Array.isArray(config.swarmManagerHosts) && config.swarmManagerHosts.length > 0) {
      const first = config.swarmManagerHosts[0]
      if (typeof first === 'object') {
        // 新格式：SSH 对象数组
        sshHosts = config.swarmManagerHosts.map(h => ({
          host: h.host || '',
          port: h.port || 22,
          username: h.username || 'root',
          password: h.password || '',
          privateKey: h.privateKey || h.privateKeyPath || ''
        }))
      } else {
        // 旧格式：tcp://host:port 字符串数组，转换为 SSH
        sshHosts = config.swarmManagerHosts.map(s => {
          const host = s.replace('tcp://', '').replace('ssh://', '').replace(/:\d+$/, '')
          return {
            host,
            port: 22,
            username: 'root',
            password: config.sshPassword || '',
            privateKey: config.sshPrivateKey || ''
          }
        })
      }
    }
    
    if (sshHosts.length === 0) {
      sshHosts = [{ host: '', port: 22, username: 'root', password: '', privateKey: '' }]
    }
    
    formData.value = {
      sshHosts,
      registryUrl: config.registryUrl || '',
      networkMode: config.networkMode || 'overlay',
      lokiUri: config.lokiUri || '',
      lokiNamespace: config.lokiNamespace || 'ysb',
      lokiAppEnv: config.lokiAppEnv || props.environment?.name || ''
    }
  } catch (error) {
    console.error('解析配置失败:', error)
    resetForm()
  }
}

watch(() => props.visible, (val) => {
  if (val) {
    loadConfig()
  }
})

const handleClose = () => {
  emit('update:visible', false)
}

const validateForm = () => {
  const validHosts = formData.value.sshHosts.filter(h => h.host.trim())
  if (validHosts.length === 0) {
    alert('请至少配置一个 SSH 主机地址')
    return false
  }
  for (const h of validHosts) {
    if (!h.password && !h.privateKey) {
      alert(`主机 ${h.host} 需要配置密码或私钥`)
      return false
    }
  }
  if (!formData.value.registryUrl.trim()) {
    alert('请输入默认镜像仓库地址')
    return false
  }
  const lokiUri = formData.value.lokiUri.trim()
  if (lokiUri) {
    try {
      const url = new URL(lokiUri)
      if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) {
        throw new Error('invalid Loki URL')
      }
    } catch (error) {
      alert('Loki 地址必须是有效的 HTTP/HTTPS 地址')
      return false
    }
  }
  return true
}

const handleConfirm = () => {
  if (!validateForm()) {
    return
  }
  
  // 过滤有效 SSH 主机
  const validHosts = formData.value.sshHosts
    .filter(h => h.host.trim())
    .map(h => ({
      host: h.host.trim(),
      port: h.port || 22,
      username: h.username.trim() || 'root',
      password: h.password || '',
      privateKey: h.privateKey || ''
    }))
  
  const submitData = {
    swarmManagerHosts: validHosts,
    registryUrl: formData.value.registryUrl.trim(),
    networkMode: formData.value.networkMode
  }
  const lokiUri = formData.value.lokiUri.trim().replace(/\/+$/, '')
  if (lokiUri) {
    submitData.lokiUri = lokiUri
    submitData.lokiNamespace = formData.value.lokiNamespace.trim()
    submitData.lokiAppEnv = formData.value.lokiAppEnv.trim() || props.environment?.name || ''
  }
  
  emit('confirm', submitData)
  handleClose()
}

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
              <!-- SSH 主机配置 -->
              <div class="form-group">
                <div class="label-with-button">
                  <label>Swarm Manager SSH 连接 <span class="required">*</span></label>
                  <button type="button" class="btn-add-host" @click="addSshHost">+ 添加节点</button>
                </div>
                <div class="ssh-hosts-list">
                  <div v-for="(host, index) in formData.sshHosts" :key="index" class="ssh-host-item">
                    <div class="ssh-host-header">
                      <span class="ssh-host-index">节点 {{ index + 1 }}</span>
                      <button v-if="formData.sshHosts.length > 1" type="button" class="btn-remove-host" @click="removeSshHost(index)">移除</button>
                    </div>
                    <div class="ssh-host-fields">
                      <div class="field-row">
                        <div class="field-item flex-2">
                          <label class="field-label">主机地址</label>
                          <input v-model="host.host" type="text" placeholder="10.10.0.22" class="form-input" />
                        </div>
                        <div class="field-item flex-1">
                          <label class="field-label">端口</label>
                          <input v-model.number="host.port" type="number" placeholder="22" class="form-input" />
                        </div>
                        <div class="field-item flex-1">
                          <label class="field-label">用户名</label>
                          <input v-model="host.username" type="text" placeholder="root" class="form-input" />
                        </div>
                      </div>
                      <div class="field-row">
                        <div class="field-item flex-1">
                          <label class="field-label">密码</label>
                          <input v-model="host.password" type="password" placeholder="SSH 密码" class="form-input" />
                        </div>
                      </div>
                      <div class="field-row">
                        <div class="field-item flex-1">
                          <label class="field-label">私钥内容 <span class="optional">(与密码二选一)</span></label>
                          <textarea v-model="host.privateKey" placeholder="-----BEGIN RSA PRIVATE KEY-----&#10;...&#10;-----END RSA PRIVATE KEY-----" class="form-textarea private-key-textarea" rows="3" />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                <div class="form-hint">配置 Swarm Manager 节点 SSH 连接信息，支持多节点故障转移。密码与私钥填一个即可</div>
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

              <div class="form-group">
                <label>Loki 日志查询地址 <span class="optional">(可选)</span></label>
                <input
                  v-model="formData.lokiUri"
                  type="url"
                  placeholder="http://0.0.0.0:3100"
                  class="form-input"
                />
                <div class="form-hint">配置后启用当前环境的 Loki 日志查询；留空时隐藏右上角日志按钮</div>
              </div>

              <div v-if="formData.lokiUri.trim()" class="form-group">
                <label>Loki namespace <span class="optional">(可选)</span></label>
                <input
                  v-model="formData.lokiNamespace"
                  type="text"
                  placeholder="ysb"
                  class="form-input"
                />
                <div class="form-hint">对应 Alloy 写入日志时的 namespace 标签；留空则不按该标签筛选</div>
              </div>

              <div v-if="formData.lokiUri.trim()" class="form-group">
                <label>Loki app_env <span class="required">*</span></label>
                <input
                  v-model="formData.lokiAppEnv"
                  type="text"
                  :placeholder="environment?.name || 'release'"
                  class="form-input"
                />
                <div class="form-hint">对应 Alloy 写入日志时的 app_env 标签；默认使用当前环境名称</div>
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
  padding: 0.875rem 1.5rem;
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
  width: 32px;
  height: 32px;
  border-radius: 8px;
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

.label-with-button {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.label-with-button label {
  margin-bottom: 0;
}

.btn-add-host {
  padding: 0.35rem 0.75rem;
  border: 1px solid var(--primary-color);
  background: white;
  color: var(--primary-color);
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-add-host:hover {
  background: var(--primary-light);
  transform: translateY(-1px);
}

.ssh-hosts-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.ssh-host-item {
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 0.75rem;
  background: var(--bg-primary);
  transition: border-color 0.2s;
}

.ssh-host-item:hover {
  border-color: var(--primary-color);
}

.ssh-host-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.6rem;
}

.ssh-host-index {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--primary-color);
}

.btn-remove-host {
  padding: 0.2rem 0.5rem;
  border: none;
  background: transparent;
  color: var(--danger-color);
  font-size: 0.75rem;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
}

.btn-remove-host:hover {
  background: rgba(239, 68, 68, 0.1);
}

.ssh-host-fields {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.field-row {
  display: flex;
  gap: 0.5rem;
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.field-item.flex-1 {
  flex: 1;
}

.field-item.flex-2 {
  flex: 2;
}

.field-label {
  font-size: 0.72rem;
  color: var(--text-tertiary);
  font-weight: 500;
}

.field-item .form-input {
  padding: 0.5rem 0.65rem;
  font-size: 0.85rem;
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
