<script setup>
import { ref, watch, onUnmounted } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  loading: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:visible', 'confirm'])

const formData = ref({
  username: '',
  password: ''
})

const resetForm = () => {
  formData.value = {
    username: '',
    password: ''
  }
}

const handleClose = () => {
  emit('update:visible', false)
}

const handleConfirm = () => {
  emit('confirm', { ...formData.value })
}

watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    resetForm()
  } else {
    document.body.style.overflow = ''
  }
})

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
            <h3>登录</h3>
            <button class="btn-close" @click="handleClose">×</button>
          </div>

          <div class="dialog-body">
            <div class="form-group">
              <label>用户名</label>
              <input
                v-model="formData.username"
                type="text"
                class="form-input"
                placeholder="请输入用户名"
                @keyup.enter="handleConfirm"
              />
            </div>

            <div class="form-group">
              <label>密码</label>
              <input
                v-model="formData.password"
                type="password"
                class="form-input"
                placeholder="请输入密码"
                @keyup.enter="handleConfirm"
              />
            </div>
          </div>

          <div class="dialog-footer">
            <button class="btn-secondary" @click="handleClose">取消</button>
            <button class="btn-primary" :disabled="loading" @click="handleConfirm">
              {{ loading ? '登录中...' : '登录' }}
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
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 1.5rem;
}

.dialog-container {
  width: 100%;
  max-width: 420px;
  background: var(--bg-secondary);
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(15, 23, 42, 0.25);
  overflow: hidden;
}

.dialog-header,
.dialog-footer {
  padding: 1rem 1.25rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dialog-header {
  border-bottom: 1px solid var(--border-color);
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.1rem;
  color: var(--text-primary);
}

.btn-close {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  font-size: 1.5rem;
  line-height: 1;
  cursor: pointer;
}

.dialog-body {
  padding: 1.25rem;
}

.form-group + .form-group {
  margin-top: 1rem;
}

.form-group label {
  display: block;
  margin-bottom: 0.5rem;
  color: var(--text-primary);
  font-weight: 500;
}

.form-input {
  width: 100%;
  padding: 0.8rem 0.95rem;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  background: var(--bg-primary);
  color: var(--text-primary);
}

.form-input:focus {
  outline: none;
  border-color: var(--primary-color);
}

.dialog-footer {
  border-top: 1px solid var(--border-color);
  gap: 0.75rem;
  justify-content: flex-end;
}

.btn-secondary,
.btn-primary {
  min-width: 88px;
  padding: 0.7rem 1rem;
  border: none;
  border-radius: 10px;
  cursor: pointer;
}

.btn-secondary {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.btn-primary {
  background: var(--primary-gradient);
  color: white;
}

.btn-primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.2s ease;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}
</style>
