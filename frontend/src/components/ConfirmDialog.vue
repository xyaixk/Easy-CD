<script setup>
import { ref, computed, Teleport } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  title: {
    type: String,
    default: '确认操作'
  },
  message: {
    type: String,
    default: '确定要执行此操作吗？'
  }
})

const emit = defineEmits(['confirm', 'cancel', 'update:visible'])

const isHolding = ref(false)
const holdProgress = ref(0)
let holdTimer = null
let progressInterval = null

const startHold = () => {
  isHolding.value = true
  holdProgress.value = 0
  
  const startTime = Date.now()
  const duration = 1000 // 2秒
  
  progressInterval = setInterval(() => {
    const elapsed = Date.now() - startTime
    holdProgress.value = Math.min((elapsed / duration) * 100, 100)
    
    if (holdProgress.value >= 100) {
      clearInterval(progressInterval)
      clearTimeout(holdTimer)
      handleConfirm()
    }
  }, 16) // 约60fps
  
  holdTimer = setTimeout(() => {
    handleConfirm()
  }, duration)
}

const endHold = () => {
  if (holdTimer) {
    clearTimeout(holdTimer)
    holdTimer = null
  }
  if (progressInterval) {
    clearInterval(progressInterval)
    progressInterval = null
  }
  isHolding.value = false
  holdProgress.value = 0
}

const handleConfirm = () => {
  endHold()
  emit('confirm')
  emit('update:visible', false)
}

const handleCancel = () => {
  endHold()
  emit('cancel')
  emit('update:visible', false)
}

const handleClickOutside = (e) => {
  if (e.target.classList.contains('confirm-dialog-overlay')) {
    handleCancel()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="visible" 
        class="confirm-dialog-overlay"
        @click="handleClickOutside"
      >
        <div class="confirm-dialog">
          <div class="dialog-header">
            <div class="header-icon warning-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                <line x1="12" y1="9" x2="12" y2="13"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
              </svg>
            </div>
            <h3>{{ title }}</h3>
          </div>
          
          <div class="dialog-body">
            <p>{{ message }}</p>
          </div>
          
          <div class="dialog-footer">
            <button 
              class="btn-cancel" 
              @click="handleCancel"
            >
              取消
            </button>
            <button 
              class="btn-confirm"
              @mousedown="startHold"
              @mouseup="endHold"
              @mouseleave="endHold"
              @touchstart="startHold"
              @touchend="endHold"
              @touchcancel="endHold"
            >
              <span class="btn-text">{{ isHolding ? '继续按住...' : '长按确认' }}</span>
              <div 
                class="hold-progress" 
                :style="{ width: holdProgress + '%' }"
              ></div>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.confirm-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100000;
  backdrop-filter: blur(8px);
}

.confirm-dialog {
  background: var(--bg-secondary);
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3), 0 0 0 1px rgba(255, 255, 255, 0.1);
  min-width: 420px;
  max-width: 90vw;
  animation: slideIn 0.3s ease;
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: scale(0.9) translateY(-20px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.dialog-header {
  padding: 1.75rem 2rem 1.25rem;
  border-bottom: 1px solid var(--border-color);
  text-align: center;
}

.header-icon {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  margin: 0 auto 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-icon.warning-icon {
  background: linear-gradient(135deg, #f59e0b 0%, #f97316 100%);
  color: white;
  box-shadow: 0 8px 24px rgba(245, 158, 11, 0.3);
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.35rem;
  font-weight: 600;
  color: var(--text-primary);
}

.dialog-body {
  padding: 2rem;
}

.dialog-body p {
  margin: 0;
  font-size: 1rem;
  color: var(--text-secondary);
  line-height: 1.6;
  text-align: center;
}

.dialog-footer {
  padding: 1rem 2rem 2rem;
  display: flex;
  gap: 1rem;
  justify-content: center;
}

.btn-cancel,
.btn-confirm {
  padding: 0.75rem 2rem;
  border-radius: 10px;
  font-size: 0.95rem;
  font-weight: 500;
  border: none;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
  overflow: hidden;
  min-width: 120px;
}

.btn-cancel {
  background: var(--bg-primary);
  color: var(--text-primary);
  border: 2px solid var(--border-color);
}

.btn-cancel:hover {
  background: var(--bg-hover);
  border-color: var(--text-secondary);
  transform: translateY(-1px);
}

.btn-confirm {
  background: var(--primary-gradient);
  color: white;
  min-width: 140px;
  user-select: none;
  -webkit-user-select: none;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.btn-confirm:hover {
  box-shadow: 0 6px 20px rgba(102, 126, 234, 0.4);
  transform: translateY(-2px);
}

.btn-confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-confirm:active:not(:disabled) {
  transform: scale(0.98);
}

.btn-text {
  position: relative;
  z-index: 2;
}

.hold-progress {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: linear-gradient(90deg, 
    rgba(255, 255, 255, 0.3) 0%, 
    rgba(255, 255, 255, 0.2) 100%);
  transition: width 0.016s linear;
  z-index: 1;
}

.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.3s;
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-active .confirm-dialog,
.dialog-leave-active .confirm-dialog {
  transition: transform 0.3s, opacity 0.3s;
}

.dialog-enter-from .confirm-dialog,
.dialog-leave-to .confirm-dialog {
  opacity: 0;
  transform: scale(0.9) translateY(-20px);
}
</style>
