<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  modelValue: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['update:visible', 'update:modelValue', 'submit'])
const textareaRef = ref(null)

watch(() => props.visible, async visible => {
  if (!visible) return
  await nextTick()
  textareaRef.value?.focus()
})

const close = () => emit('update:visible', false)
</script>

<template>
  <Teleport to="body">
    <Transition name="env-paste-dialog">
      <div v-if="visible" class="env-paste-overlay" @click.self="close">
        <div class="env-paste-dialog" role="dialog" aria-modal="true" aria-labelledby="manual-paste-title">
          <div class="env-paste-header">
            <h4 id="manual-paste-title">粘贴环境变量</h4>
            <button type="button" class="env-paste-close" aria-label="关闭" @click="close">×</button>
          </div>
          <div class="env-paste-body">
            <p class="env-paste-hint">
              浏览器未授权直接读取剪贴板，请在下方按 <kbd>Ctrl</kbd> + <kbd>V</kbd>。支持常用 <code>.env</code> 格式。
            </p>
            <textarea
              ref="textareaRef"
              :value="modelValue"
              class="env-paste-textarea"
              rows="12"
              placeholder="APP_ENV=release&#10;API_URL=https://example.test/api&#10;EMPTY_VALUE="
              @input="emit('update:modelValue', $event.target.value)"
            />
          </div>
          <div class="env-paste-footer">
            <button type="button" class="env-paste-btn env-paste-btn-secondary" @click="close">取消</button>
            <button type="button" class="env-paste-btn env-paste-btn-primary" @click="emit('submit')">解析并粘贴</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.env-paste-overlay {
  position: fixed;
  inset: 0;
  z-index: 2200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
}

.env-paste-dialog {
  width: 100%;
  max-width: 620px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: white;
  border-radius: 14px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.35);
}

.env-paste-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 1.25rem;
  color: white;
  background: var(--primary-gradient);
}

.env-paste-header h4 {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.env-paste-close {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border: none;
  border-radius: 6px;
  color: white;
  background: rgba(255, 255, 255, 0.2);
  font-size: 1.15rem;
  line-height: 1;
  cursor: pointer;
}

.env-paste-close:hover {
  background: rgba(255, 255, 255, 0.32);
}

.env-paste-body {
  padding: 1rem 1.25rem;
}

.env-paste-hint {
  margin: 0 0 0.75rem;
  color: var(--text-secondary);
  font-size: 0.82rem;
  line-height: 1.5;
}

.env-paste-hint code,
.env-paste-hint kbd {
  padding: 0.08rem 0.28rem;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-family: 'Consolas', 'Monaco', monospace;
}

.env-paste-textarea {
  width: 100%;
  min-height: 230px;
  resize: vertical;
  padding: 0.75rem 0.9rem;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  outline: none;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 0.85rem;
  line-height: 1.5;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.env-paste-textarea:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.env-paste-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.65rem;
  padding: 0.85rem 1.25rem;
  border-top: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.env-paste-btn {
  padding: 0.55rem 1.15rem;
  border-radius: 7px;
  font-size: 0.86rem;
  font-weight: 500;
  cursor: pointer;
  transition: transform 0.2s;
}

.env-paste-btn-secondary {
  border: 2px solid var(--border-color);
  background: white;
  color: var(--text-primary);
}

.env-paste-btn-primary {
  border: none;
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 2px 8px var(--primary-shadow);
}

.env-paste-btn:hover {
  transform: translateY(-1px);
}

.env-paste-dialog-enter-active,
.env-paste-dialog-leave-active {
  transition: opacity 0.2s;
}

.env-paste-dialog-enter-active .env-paste-dialog,
.env-paste-dialog-leave-active .env-paste-dialog {
  transition: transform 0.2s, opacity 0.2s;
}

.env-paste-dialog-enter-from,
.env-paste-dialog-leave-to {
  opacity: 0;
}

.env-paste-dialog-enter-from .env-paste-dialog,
.env-paste-dialog-leave-to .env-paste-dialog {
  opacity: 0;
  transform: scale(0.95) translateY(-8px);
}
</style>
