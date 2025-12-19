<script setup>
import { ref, computed } from 'vue'

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

const emit = defineEmits(['confirm', 'cancel', 'update:visible'])

const targetReplicas = ref(props.service?.instances || 1)
const isHolding = ref(false)
const holdProgress = ref(0)
let holdTimer = null
let progressInterval = null

const startHold = () => {
  if (!isValid.value) return
  
  isHolding.value = true
  holdProgress.value = 0
  
  const startTime = Date.now()
  const duration = 1000
  
  progressInterval = setInterval(() => {
    const elapsed = Date.now() - startTime
    holdProgress.value = Math.min((elapsed / duration) * 100, 100)
    
    if (holdProgress.value >= 100) {
      clearInterval(progressInterval)
      clearTimeout(holdTimer)
      handleConfirm()
    }
  }, 16)
  
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
  emit('confirm', targetReplicas.value)
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

const isValid = computed(() => {
  return targetReplicas.value > 0 && targetReplicas.value <= 100
})

const increment = () => {
  if (targetReplicas.value < 100) {
    targetReplicas.value++
  }
}

const decrement = () => {
  if (targetReplicas.value > 1) {
    targetReplicas.value--
  }
}

const validateInput = (e) => {
  let value = e.target.value.replace(/[^0-9]/g, '')
  if (value === '') {
    targetReplicas.value = 1
  } else {
    let num = parseInt(value)
    if (num < 1) num = 1
    if (num > 100) num = 100
    targetReplicas.value = num
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div 
        v-if="visible" 
        class="confirm-dialog-overlay"
        @click="handleClickOutside"
      >
        <div class="confirm-dialog">
          <div class="dialog-header">
            <h3>调整副本数量</h3>
          </div>
          
          <div class="dialog-body">
            <div class="replica-control">
              <div class="scale-display">
                <span class="scale-label">当前值</span>
                <div class="scale-number current">{{ service.instances }}</div>
              </div>
              
              <div class="scale-arrow">
                <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M5 12h14M12 5l7 7-7 7"/>
                </svg>
              </div>
              
              <div class="scale-display">
                <span class="scale-label">调整至</span>
                <input 
                  type="text" 
                  v-model.number="targetReplicas"
                  class="scale-number target"
                  @input="validateInput"
                />
              </div>
            </div>
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
              :disabled="!isValid"
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
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100000;
  backdrop-filter: blur(4px);
}

.confirm-dialog {
  background: var(--bg-secondary);
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.3);
  min-width: 450px;
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
  padding: 1.5rem 1.5rem 1rem;
  border-bottom: 1px solid var(--border-color);
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
}

.dialog-body {
  padding: 2rem;
}

.replica-control {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2rem;
}

.scale-display {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
}

.scale-label {
  font-size: 0.875rem;
  color: var(--text-secondary);
  font-weight: 500;
  margin-bottom: 1rem;
}

.scale-number {
  width: 80px;
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.75rem;
  font-weight: 600;
  border-radius: 10px;
  border: 2px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  transition: all 0.2s;
  text-align: center;
  font-family: inherit;
}

.scale-number.current {
  background: linear-gradient(135deg, var(--bg-primary) 0%, var(--bg-secondary) 100%);
}

.scale-number.target {
  background: var(--bg-primary);
  padding: 0;
}

.scale-number.target:focus {
  outline: none;
}

.scale-arrow {
  color: var(--primary-color);
  opacity: 0.6;
  display: flex;
  align-items: center;
  margin-bottom: 2rem;
}




.dialog-footer {
  padding: 1rem 1.5rem 1.5rem;
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
}

.btn-cancel,
.btn-confirm {
  padding: 0.625rem 1.5rem;
  border-radius: 8px;
  font-size: 0.9rem;
  font-weight: 500;
  border: none;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
  overflow: hidden;
}

.btn-cancel {
  background: var(--bg-primary);
  color: var(--text-primary);
  border: 1px solid var(--border-color);
}

.btn-cancel:hover {
  background: var(--bg-hover);
}

.btn-confirm {
  background: var(--primary-gradient);
  color: white;
  min-width: 120px;
  user-select: none;
  -webkit-user-select: none;
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
</style>
