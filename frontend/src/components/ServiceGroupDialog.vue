<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  mode: {
    type: String,
    default: 'create'
  },
  group: {
    type: Object,
    default: null
  },
  loading: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'confirm'])
const name = ref('')
const nameInput = ref(null)

watch(
  () => [props.visible, props.group],
  async ([visible]) => {
    if (!visible) return
    name.value = props.group?.name || ''
    await nextTick()
    nameInput.value?.focus()
    nameInput.value?.select()
  },
  { immediate: true }
)

const close = () => {
  if (!props.loading) emit('close')
}

const submit = () => {
  const normalizedName = name.value.trim()
  if (!normalizedName || props.loading) return
  emit('confirm', normalizedName)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="group-dialog-mask">
      <form class="group-dialog" @submit.prevent="submit">
        <header class="group-dialog-header">
          <h3>{{ mode === 'edit' ? '重命名分组' : '新建分组' }}</h3>
          <button type="button" class="icon-button" aria-label="关闭" @click="close">×</button>
        </header>

        <div class="group-dialog-body">
          <label for="service-group-name">分组名称</label>
          <input
            id="service-group-name"
            ref="nameInput"
            v-model="name"
            maxlength="50"
            autocomplete="off"
            placeholder="请输入分组名称"
          >
          <span class="character-count">{{ name.length }}/50</span>
        </div>

        <footer class="group-dialog-footer">
          <button type="button" class="button secondary" :disabled="loading" @click="close">取消</button>
          <button type="submit" class="button primary" :disabled="!name.trim() || loading">
            {{ loading ? '保存中…' : '保存' }}
          </button>
        </footer>
      </form>
    </div>
  </Teleport>
</template>

<style scoped>
.group-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: rgba(15, 23, 42, 0.52);
  backdrop-filter: blur(3px);
}

.group-dialog {
  width: min(420px, 100%);
  overflow: hidden;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 14px;
  box-shadow: var(--shadow-xl);
}

.group-dialog-header,
.group-dialog-footer {
  display: flex;
  align-items: center;
  padding: 1.25rem 1.5rem;
}

.group-dialog-header {
  justify-content: space-between;
  border-bottom: 1px solid var(--border-color);
}

.group-dialog-header h3 {
  margin: 0;
  color: var(--text-primary);
  font-size: 1.1rem;
}

.icon-button {
  width: 2rem;
  height: 2rem;
  padding: 0;
  color: var(--text-secondary);
  font-size: 1.5rem;
  line-height: 1;
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.icon-button:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.group-dialog-body {
  position: relative;
  display: grid;
  gap: 0.55rem;
  padding: 1.5rem;
}

.group-dialog-body label {
  color: var(--text-primary);
  font-size: 0.9rem;
  font-weight: 600;
}

.group-dialog-body input {
  width: 100%;
  padding: 0.75rem 3.5rem 0.75rem 0.9rem;
  color: var(--text-primary);
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 9px;
  outline: none;
}

.group-dialog-body input:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.character-count {
  position: absolute;
  right: 2.2rem;
  bottom: 2.25rem;
  color: var(--text-tertiary);
  font-size: 0.75rem;
}

.group-dialog-footer {
  justify-content: flex-end;
  gap: 0.75rem;
  border-top: 1px solid var(--border-color);
}

.button {
  min-width: 5rem;
  padding: 0.65rem 1rem;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
}

.button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.button.secondary {
  color: var(--text-primary);
  background: var(--bg-primary);
  border-color: var(--border-color);
}

.button.primary {
  color: white;
  background: var(--primary-color);
}
</style>
