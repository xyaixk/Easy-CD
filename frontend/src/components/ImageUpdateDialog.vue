<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import {
  buildDockerImageReference,
  isValidDockerImageTag,
  parseDockerImageReference
} from '../utils/dockerImage.js'
import toast from '../utils/toast.js'

const HOLD_DURATION_MS = 1000

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

const imageTag = ref('')
const tagInputRef = ref(null)
const isHolding = ref(false)
const holdProgress = ref(0)
let holdTimer = null
let progressInterval = null

const currentImage = computed(() => String(props.service?.dockerImage ?? '').trim())
const imageParts = computed(() => parseDockerImageReference(currentImage.value))
const normalizedTag = computed(() => imageTag.value.trim())
const tagError = computed(() => {
  if (!normalizedTag.value) return '请输入镜像 Tag'
  if (!isValidDockerImageTag(normalizedTag.value)) {
    return '仅允许字母、数字、下划线、点和短横线，且最长 128 个字符'
  }
  return ''
})
const canSubmit = computed(() => Boolean(imageParts.value.repository) && !tagError.value)
const targetImage = computed(() => {
  if (!canSubmit.value) return ''
  return buildDockerImageReference(currentImage.value, normalizedTag.value)
})
const isSameTag = computed(() => normalizedTag.value === imageParts.value.tag)

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
  try {
    const dockerImage = buildDockerImageReference(currentImage.value, normalizedTag.value)
    emit('confirm', dockerImage)
    emit('update:visible', false)
  } catch (error) {
    toast.warning(error.message)
  }
}

const startHold = (event) => {
  if (!canSubmit.value || isHolding.value) return
  event?.preventDefault()
  isHolding.value = true
  holdProgress.value = 0
  const startedAt = Date.now()

  progressInterval = setInterval(() => {
    const elapsed = Date.now() - startedAt
    holdProgress.value = Math.min((elapsed / HOLD_DURATION_MS) * 100, 100)
  }, 16)

  holdTimer = setTimeout(handleConfirm, HOLD_DURATION_MS)
}

const handleCancel = () => {
  endHold()
  emit('cancel')
  emit('update:visible', false)
}

watch(() => props.visible, async (visible) => {
  endHold()
  if (!visible) {
    imageTag.value = ''
    return
  }

  imageTag.value = imageParts.value.tag
  await nextTick()
  tagInputRef.value?.focus()
  tagInputRef.value?.select()
})

onUnmounted(endHold)
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div
        v-if="visible"
        class="dialog-overlay"
        @click.self="handleCancel"
      >
        <div class="image-update-dialog" role="dialog" aria-modal="true" aria-labelledby="image-update-title">
          <div class="dialog-header">
            <div class="header-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4a2 2 0 0 0 1-1.73z"/>
                <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
                <line x1="12" y1="22.08" x2="12" y2="12"/>
              </svg>
            </div>
            <div>
              <h3 id="image-update-title">更新服务镜像</h3>
              <p>{{ service.name }}</p>
            </div>
          </div>

          <div class="dialog-body">
            <div class="image-field">
              <span class="field-label">当前镜像</span>
              <code>{{ currentImage || '-' }}</code>
            </div>

            <label class="tag-field">
              <span class="field-label">镜像 Tag</span>
              <input
                ref="tagInputRef"
                v-model="imageTag"
                type="text"
                maxlength="128"
                placeholder="例如：1.2.3"
                autocomplete="off"
                :aria-invalid="Boolean(tagError)"
                @input="endHold"
              />
            </label>
            <p v-if="tagError" class="field-error">{{ tagError }}</p>
            <p v-else class="field-hint">仓库和镜像名称保持不变，仅更新 Tag。</p>

            <div class="image-field target-image">
              <span class="field-label">更新后镜像</span>
              <code>{{ targetImage || '-' }}</code>
            </div>
            <p v-if="isSameTag && !tagError" class="same-tag-hint">
              当前 Tag 未变化，提交后仍会强制重建服务副本。
            </p>
          </div>

          <div class="dialog-footer">
            <button class="btn-cancel" type="button" @click="handleCancel">取消</button>
            <button
              class="btn-confirm"
              type="button"
              :disabled="!canSubmit"
              @mousedown="startHold"
              @mouseup="endHold"
              @mouseleave="endHold"
              @touchstart="startHold"
              @touchend="endHold"
              @touchcancel="endHold"
            >
              <span class="btn-text">{{ isHolding ? '继续按住...' : '长按确认更新' }}</span>
              <span class="hold-progress" :style="{ width: `${holdProgress}%` }"></span>
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
  z-index: 100000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(8px);
}

.image-update-dialog {
  width: min(520px, 100%);
  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: 16px;
  background: var(--bg-secondary);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.dialog-header {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--border-color);
}

.header-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  border-radius: 50%;
  color: white;
  background: var(--primary-gradient);
}

.dialog-header h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 1.2rem;
}

.dialog-header p {
  margin: 0.25rem 0 0;
  color: var(--text-secondary);
  font-size: 0.85rem;
}

.dialog-body {
  padding: 1.5rem;
}

.image-field,
.tag-field {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.tag-field {
  margin-top: 1.25rem;
}

.field-label {
  color: var(--text-secondary);
  font-size: 0.85rem;
  font-weight: 600;
}

.image-field code {
  overflow-wrap: anywhere;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-primary);
  background: var(--bg-primary);
  font-size: 0.85rem;
}

.tag-field input {
  box-sizing: border-box;
  width: 100%;
  padding: 0.75rem 0.875rem;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-primary);
  background: var(--bg-primary);
  font: inherit;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.tag-field input:focus {
  border-color: var(--primary-color);
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-shadow);
}

.tag-field input[aria-invalid="true"] {
  border-color: var(--danger-color);
}

.field-error,
.field-hint,
.same-tag-hint {
  margin: 0.5rem 0 0;
  font-size: 0.8rem;
}

.field-error {
  color: var(--danger-color);
}

.field-hint,
.same-tag-hint {
  color: var(--text-tertiary);
}

.target-image {
  margin-top: 1.25rem;
}

.target-image code {
  color: var(--primary-color);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 0 1.5rem 1.5rem;
}

.btn-cancel,
.btn-confirm {
  position: relative;
  min-width: 120px;
  overflow: hidden;
  padding: 0.75rem 1.25rem;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  font-size: 0.9rem;
  font-weight: 500;
}

.btn-cancel {
  border: 1px solid var(--border-color);
  color: var(--text-primary);
  background: var(--bg-primary);
}

.btn-confirm {
  min-width: 150px;
  color: white;
  background: var(--primary-gradient);
  user-select: none;
}

.btn-confirm:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.btn-text {
  position: relative;
  z-index: 2;
}

.hold-progress {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 1;
  background: rgba(255, 255, 255, 0.25);
}

.dialog-enter-active,
.dialog-leave-active {
  transition: opacity 0.2s;
}

.dialog-enter-active .image-update-dialog,
.dialog-leave-active .image-update-dialog {
  transition: transform 0.2s, opacity 0.2s;
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-from .image-update-dialog,
.dialog-leave-to .image-update-dialog {
  opacity: 0;
  transform: scale(0.96) translateY(-12px);
}
</style>
