<script setup>
import { ref, watch, onUnmounted } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:visible', 'confirm'])

const formData = ref({
  name: '',
  color: '#667eea',
  deployType: 'docker',
  config: {}
})

const resetForm = () => {
  formData.value = {
    name: '',
    color: '#667eea',
    deployType: 'docker',
    config: {}
  }
}

const handleClose = () => {
  emit('update:visible', false)
}

watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    resetForm()
  } else {
    document.body.style.overflow = ''
  }
})

const handleConfirm = () => {
  // 暂时只保存基本信息，配置通过右上角配置按钮设置
  const submitData = {
    ...formData.value,
    config: JSON.stringify({})  // 空配置，后续通过配置按钮设置
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
                  <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                </svg>
              </div>
              <h3>新增环境</h3>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
      <div class="dialog-body">
        <div class="form-group">
          <label>环境名称 <span class="required">*</span></label>
          <div class="name-color-row">
            <input 
              v-model="formData.name" 
              type="text" 
              placeholder="请输入环境名称"
              class="form-input name-input"
            />
            <input 
              v-model="formData.color" 
              type="color" 
              class="form-color-picker"
              title="选择颜色"
            />
          </div>
        </div>
        
        <div class="form-group">
          <label>部署方式 <span class="required">*</span></label>
          <select v-model="formData.deployType" class="form-select">
            <option value="docker">Docker</option>
            <option value="k8s" disabled>Kubernetes（暂不支持）</option>
          </select>
          <div class="form-hint">具体部署配置请在创建后通过右上角「配置」按钮进行设置</div>
        </div>
      </div>
      
      <div class="dialog-footer">
        <button class="btn-secondary" @click="handleClose">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
          取消
        </button>
        <button class="btn-primary" @click="handleConfirm">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
          确定
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
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  backdrop-filter: blur(8px);
  padding: 2rem;
}

.dialog-container {
  background: var(--bg-secondary);
  border-radius: 16px;
  width: 100%;
  max-width: 600px;
  max-height: 90vh;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3), 0 0 0 1px rgba(255, 255, 255, 0.1);
  display: flex;
  flex-direction: column;
}

.dialog-header {
  padding: 1.75rem 2rem;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, var(--bg-secondary) 0%, var(--bg-primary) 100%);
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
  background: var(--primary-gradient);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.35rem;
  font-weight: 600;
  color: var(--text-primary);
}

.btn-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.btn-close:hover {
  background: var(--bg-hover);
  color: var(--danger-color);
  transform: rotate(90deg);
}

.dialog-body {
  flex: 1;
  overflow-y: auto;
  padding: 2rem;
}

.dialog-body::-webkit-scrollbar {
  width: 8px;
}

.dialog-body::-webkit-scrollbar-track {
  background: var(--bg-primary);
  border-radius: 4px;
}

.dialog-body::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 4px;
}

.dialog-body::-webkit-scrollbar-thumb:hover {
  background: var(--primary-color);
}

.form-group {
  margin-bottom: 1.75rem;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-group label {
  display: block;
  margin-bottom: 0.75rem;
  color: var(--text-primary);
  font-weight: 500;
  font-size: 0.95rem;
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
  padding: 0.85rem 1rem;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: 0.95rem;
  transition: all 0.2s;
  font-family: inherit;
}

.form-textarea {
  resize: vertical;
  min-height: 100px;
  line-height: 1.5;
}

.form-input:focus,
.form-select:focus,
.form-textarea:focus {
  outline: none;
  border-color: var(--primary-color);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--primary-color) 10%, transparent);
  background: var(--bg-secondary);
}

.form-hint {
  margin-top: 0.6rem;
  font-size: 0.85rem;
  color: var(--text-secondary);
  display: flex;
  align-items: flex-start;
  gap: 0.4rem;
}

.form-hint::before {
  content: 'ℹ';
  color: var(--primary-color);
  font-weight: bold;
}

.name-color-row {
  display: flex;
  gap: 0.75rem;
  align-items: center;
}

.name-input {
  flex: 1;
}

.form-color-picker {
  width: 56px;
  height: 48px;
  padding: 4px;
  border: 2px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-primary);
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.form-color-picker:hover {
  border-color: var(--primary-color);
  transform: scale(1.05);
}

.form-color-picker::-webkit-color-swatch-wrapper {
  padding: 0;
}

.form-color-picker::-webkit-color-swatch {
  border: none;
  border-radius: 6px;
}

.dialog-footer {
  padding: 1.5rem 2rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
  background: linear-gradient(135deg, var(--bg-primary) 0%, var(--bg-secondary) 100%);
}

.btn-secondary,
.btn-primary {
  padding: 0.75rem 1.75rem;
  border-radius: 10px;
  border: none;
  font-size: 0.95rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.btn-secondary {
  background: var(--bg-secondary);
  color: var(--text-primary);
  border: 2px solid var(--border-color);
}

.btn-secondary:hover {
  background: var(--bg-hover);
  border-color: var(--text-secondary);
  transform: translateY(-1px);
}

.btn-primary {
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.btn-primary:hover {
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.4);
  transform: translateY(-2px);
}

.btn-primary:active,
.btn-secondary:active {
  transform: translateY(0);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.3s ease;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-active .dialog-container,
.dialog-fade-leave-active .dialog-container {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.dialog-fade-enter-from .dialog-container {
  transform: scale(0.95) translateY(-20px);
  opacity: 0;
}

.dialog-fade-leave-to .dialog-container {
  transform: scale(0.95) translateY(20px);
  opacity: 0;
}
</style>
