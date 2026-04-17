<script setup>
import { ref, watch, onUnmounted, computed } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  currentEnvironment: {
    type: Object,
    required: true
  },
  service: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:visible', 'confirm'])

const formData = ref({
  serviceName: '',
  description: '',
  dockerImage: '',
  replicas: 1,
  dockerParams: '' // Docker运行参数，格式：KEY1=VALUE1\nKEY2=VALUE2
})

// 获取当前环境的部署类型
const currentDeployType = computed(() => props.currentEnvironment?.deployType || 'docker')

const isDockerDeploy = computed(() => currentDeployType.value === 'docker')
const isKubernetes = computed(() => currentDeployType.value === 'kubernetes')

watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    if (props.service) {
      // 编辑模式，加载服务数据
      loadServiceData()
    } else {
      // 新增模式，重置表单
      resetForm()
    }
  } else {
    document.body.style.overflow = ''
  }
})

const resetForm = () => {
  formData.value = {
    serviceName: '',
    description: '',
    dockerImage: '',
    replicas: 1,
    dockerParams: ''
  }
}

const loadServiceData = () => {
  if (!props.service) return
  
  // 解析dockerParams从 JSON到字符串格式
  let dockerParamsObj = {}
  try {
    dockerParamsObj = props.service.dockerParams ? JSON.parse(props.service.dockerParams) : {}
  } catch (e) {
    console.error('解析dockerParams失败:', e)
    dockerParamsObj = {}
  }
  
  // 从 dockerParams 提取 replicas
  const replicas = dockerParamsObj.replicas || 1
  delete dockerParamsObj.replicas
  
  formData.value = {
    serviceName: props.service.name || '',
    description: props.service.description || '',
    dockerImage: props.service.dockerImage || '',
    replicas: replicas,
    dockerParams: formatEnvVars(dockerParamsObj)
  }
}

const formatEnvVars = (obj) => {
  return Object.entries(obj)
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')
}

const handleClose = () => {
  emit('update:visible', false)
  resetForm()
}

const validateForm = () => {
  if (!formData.value.serviceName.trim()) {
    alert('请输入服务名称')
    return false
  }
  
  if (formData.value.description && formData.value.description.length > 20) {
    alert('服务描述最多20个字符')
    return false
  }
  
  if (!formData.value.dockerImage.trim()) {
    alert('请输入Docker镜像')
    return false
  }
  
  if (formData.value.replicas < 1 || formData.value.replicas > 100) {
    alert('副本数必须在1-100之间')
    return false
  }
  
  return true
}

const handleConfirm = () => {
  if (!validateForm()) {
    return
  }
  
  // 构建提交数据
  const submitData = {
    environmentId: props.currentEnvironment.id,
    name: formData.value.serviceName,
    description: formData.value.description,
    dockerImage: formData.value.dockerImage,
    replicas: formData.value.replicas,
    dockerParams: JSON.stringify(parseEnvVars(formData.value.dockerParams))
  }
  
  emit('confirm', submitData)
  handleClose()
}

// 解析环境变量字符串为对象
const parseEnvVars = (envVarsStr) => {
  if (!envVarsStr.trim()) return {}
  
  const envVars = {}
  const lines = envVarsStr.split('\n')
  lines.forEach(line => {
    const trimmed = line.trim()
    if (trimmed && trimmed.includes('=')) {
      const [key, ...valueParts] = trimmed.split('=')
      envVars[key.trim()] = valueParts.join('=').trim()
    }
  })
  return envVars
}

// 复制示例参数
const copyExampleParams = () => {
  const exampleParams = `# 环境变量
JAVA_OPTS=-Xmx2g -Xms1g -XX:+UseG1GC
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
TZ=Asia/Shanghai
DATABASE_URL=jdbc:mysql://mysql:3306/mydb
DATABASE_USERNAME=root
DATABASE_PASSWORD=password
REDIS_HOST=redis
REDIS_PORT=6379

# Docker 基础配置
restart=always
publish=8080:8080
publish=9090:9090
cpus=2.0
memory=4G
memory-reservation=2G
network=overlay

# HealthCheck 健康检查配置
healthcheck=curl -f http://localhost:8080/actuator/health || exit 1
healthcheck_interval=10s
healthcheck_timeout=5s
healthcheck_retries=3
healthcheck_start_period=30s

# 滚动更新配置
update_parallelism=1
update_delay=10s
update_monitor=60s
update_failure_action=pause
update_order=start-first`
  
  formData.value.dockerParams = exampleParams
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
                  <rect x="2" y="3" width="20" height="14" rx="2"/>
                  <path d="M8 21h8M12 17v4"/>
                </svg>
              </div>
              <h3>{{ service ? '编辑服务' : '新增服务' }}</h3>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
          <div class="dialog-body">
            <!-- 当前环境显示 -->
            <div class="env-info">
              <span class="env-label">部署环境：</span>
              <span class="env-name">{{ currentEnvironment.name }}</span>
              <span class="env-type">({{ currentEnvironment.deployType }})</span>
            </div>
            
            <!-- 基础信息 -->
            <div class="form-row">
              <div class="form-group">
                <label>服务名称 <span class="required">*</span></label>
                <input 
                  v-model="formData.serviceName" 
                  type="text" 
                  placeholder="user-service"
                  class="form-input"
                  :disabled="!!service"
                />
              </div>
            </div>
            
            <!-- 服务描述 -->
            <div class="form-group">
              <label>服务描述 <span class="optional">(可选，最多20字)</span></label>
              <input 
                v-model="formData.description" 
                type="text" 
                placeholder="用户管理服务"
                class="form-input"
                maxlength="20"
              />
              <div class="form-hint">{{ formData.description.length }}/20 字符</div>
            </div>
            
            <!-- Docker 镜像 -->
            <div class="form-group">
              <label>Docker 镜像 <span class="required">*</span></label>
              <input 
                v-model="formData.dockerImage" 
                type="text" 
                placeholder="harbor.example.com/project/user-service:1.0.0"
                class="form-input"
              />
              <div class="form-hint">完整的Docker镜像地址，包含仓库、项目、服务名和标签</div>
            </div>
            
            <!-- 副本数量 -->
            <div class="form-group">
              <label>副本数量 <span class="required">*</span></label>
              <input 
                v-model.number="formData.replicas" 
                type="number" 
                min="1"
                max="100"
                placeholder="1"
                class="form-input"
                style="max-width: 200px;"
              />
            </div>
            
            <!-- Docker运行参数 -->
            <div class="form-group">
              <div class="label-with-button">
                <label>Docker 运行参数 <span class="optional">(可选)</span></label>
                <button type="button" class="btn-copy-example" @click="copyExampleParams" title="复制示例参数">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                  </svg>
                  复制示例
                </button>
              </div>
              <textarea 
                v-model="formData.dockerParams"
                placeholder="cpuLimit=1&#10;memoryLimit=2G&#10;restart=always&#10;network=overlay"
                class="form-textarea"
                rows="4"
              />
              <div class="form-hint">每行一个参数，格式：KEY=VALUE，用于Docker容器配置</div>
            </div>
          </div>
      
          <div class="dialog-footer">
            <button class="btn btn-secondary" @click="handleClose">取消</button>
            <button class="btn btn-primary" @click="handleConfirm">
              {{ service ? '确认修改' : '确认新增' }}
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

.dialog-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
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

.env-info {
  padding: 1rem;
  background: var(--primary-light);
  border-radius: 8px;
  margin-bottom: 1.5rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.env-label {
  color: var(--text-secondary);
  font-size: 0.9rem;
}

.env-name {
  color: var(--primary-color);
  font-weight: 600;
  font-size: 1rem;
}

.env-type {
  color: var(--text-tertiary);
  font-size: 0.85rem;
}

.form-group {
  margin-bottom: 1.5rem;
}

.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
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

.form-input:disabled {
  background: var(--bg-primary);
  color: var(--text-tertiary);
  cursor: not-allowed;
  opacity: 0.6;
}

.form-textarea {
  resize: vertical;
  min-height: 100px;
}

.radio-group {
  display: flex;
  gap: 1rem;
}

.radio-item {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  border: 2px solid var(--border-color);
  background: var(--bg-secondary);
  transition: all 0.2s;
  position: relative;
}

.radio-item input[type="radio"] {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.radio-item:hover {
  border-color: var(--primary-color);
  background: var(--bg-hover);
}

.radio-item.active {
  border-color: var(--primary-color);
  background: var(--primary-light);
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.radio-item.active span {
  color: var(--primary-color);
  font-weight: 600;
}

.radio-item span {
  font-size: 1rem;
  color: var(--text-primary);
  font-weight: 500;
  transition: all 0.2s;
}

.form-hint {
  margin-top: 0.5rem;
  font-size: 0.8rem;
  color: var(--text-tertiary);
  line-height: 1.4;
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

.btn-copy-example {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 0.8rem;
  border: 1px solid var(--primary-color);
  background: white;
  color: var(--primary-color);
  border-radius: 6px;
  font-size: 0.85rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-copy-example:hover {
  background: var(--primary-light);
  border-color: var(--primary-color);
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(102, 126, 234, 0.2);
}

.btn-copy-example:active {
  transform: translateY(0);
}

.btn-copy-example svg {
  flex-shrink: 0;
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

.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.35);
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
