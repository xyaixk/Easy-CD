<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  conflicts: {
    type: Array,
    default: () => []
  },
  additionCount: {
    type: Number,
    default: 0
  }
})

const emit = defineEmits(['update:visible', 'confirm'])
const choices = ref({})

watch(
  [() => props.visible, () => props.conflicts],
  ([visible, conflicts]) => {
    if (!visible) return
    choices.value = Object.fromEntries(
      conflicts.map(conflict => [conflict.key, 'current'])
    )
  },
  { immediate: true }
)

const close = () => emit('update:visible', false)

const setAllChoices = resolution => {
  choices.value = Object.fromEntries(
    props.conflicts.map(conflict => [conflict.key, resolution])
  )
}

const confirm = () => emit('confirm', { ...choices.value })
const displayValue = value => value === '' ? '（空字符串）' : value
</script>

<template>
  <Teleport to="body">
    <Transition name="env-conflict-dialog">
      <div v-if="visible" class="env-conflict-overlay" @click.self="close">
        <div class="env-conflict-dialog" role="dialog" aria-modal="true" aria-labelledby="env-conflict-title">
          <div class="env-conflict-header">
            <div>
              <h4 id="env-conflict-title">解决环境变量冲突</h4>
              <p>发现 {{ conflicts.length }} 个同名但值不同的变量，请选择要保留的值。</p>
            </div>
            <button type="button" class="env-conflict-close" aria-label="关闭" @click="close">×</button>
          </div>

          <div class="env-conflict-toolbar">
            <span>批量选择</span>
            <button type="button" @click="setAllChoices('current')">全部保留当前</button>
            <button type="button" @click="setAllChoices('incoming')">全部使用粘贴值</button>
          </div>

          <div class="env-conflict-list">
            <section v-for="conflict in conflicts" :key="conflict.key" class="env-conflict-item">
              <div class="env-conflict-key">{{ conflict.key }}</div>
              <div class="env-conflict-options">
                <label :class="{ active: choices[conflict.key] === 'current' }">
                  <input
                    v-model="choices[conflict.key]"
                    type="radio"
                    :name="`conflict-${conflict.key}`"
                    value="current"
                  />
                  <span class="env-conflict-option-title">保留当前值</span>
                  <code>{{ displayValue(conflict.currentValue) }}</code>
                </label>
                <label :class="{ active: choices[conflict.key] === 'incoming' }">
                  <input
                    v-model="choices[conflict.key]"
                    type="radio"
                    :name="`conflict-${conflict.key}`"
                    value="incoming"
                  />
                  <span class="env-conflict-option-title">使用粘贴值</span>
                  <code>{{ displayValue(conflict.incomingValue) }}</code>
                </label>
              </div>
            </section>
          </div>

          <div class="env-conflict-footer">
            <span v-if="additionCount" class="env-conflict-summary">
              另有 {{ additionCount }} 个新变量将在确认后追加
            </span>
            <button type="button" class="env-conflict-btn env-conflict-btn-secondary" @click="close">取消</button>
            <button type="button" class="env-conflict-btn env-conflict-btn-primary" @click="confirm">应用粘贴</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.env-conflict-overlay {
  position: fixed;
  inset: 0;
  z-index: 2210;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
}

.env-conflict-dialog {
  width: 100%;
  max-width: 760px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: white;
  border-radius: 14px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.35);
}

.env-conflict-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 1.25rem;
  color: white;
  background: var(--primary-gradient);
}

.env-conflict-header h4 {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 600;
}

.env-conflict-header p {
  margin: 0.3rem 0 0;
  font-size: 0.8rem;
  color: rgba(255, 255, 255, 0.82);
}

.env-conflict-close {
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

.env-conflict-close:hover {
  background: rgba(255, 255, 255, 0.32);
}

.env-conflict-toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.7rem 1.25rem;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.env-conflict-toolbar span {
  margin-right: 0.2rem;
  color: var(--text-tertiary);
  font-size: 0.78rem;
}

.env-conflict-toolbar button {
  padding: 0.3rem 0.6rem;
  border: 1px solid var(--border-color);
  border-radius: 5px;
  background: white;
  color: var(--text-secondary);
  font-size: 0.76rem;
  cursor: pointer;
}

.env-conflict-toolbar button:hover {
  color: var(--primary-color);
  border-color: var(--primary-color);
}

.env-conflict-list {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  padding: 1rem 1.25rem;
  overflow-y: auto;
}

.env-conflict-item {
  padding: 0.85rem;
  border: 1px solid var(--border-color);
  border-radius: 9px;
  background: var(--bg-secondary);
}

.env-conflict-key {
  margin-bottom: 0.65rem;
  color: var(--text-primary);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 0.88rem;
  font-weight: 700;
}

.env-conflict-options {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.65rem;
}

.env-conflict-options label {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.25rem 0.45rem;
  align-items: center;
  padding: 0.7rem;
  border: 2px solid var(--border-color);
  border-radius: 7px;
  background: white;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.env-conflict-options label.active {
  border-color: var(--primary-color);
  background: var(--primary-light);
}

.env-conflict-options input {
  margin: 0;
  accent-color: var(--primary-color);
}

.env-conflict-option-title {
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
}

.env-conflict-options code {
  grid-column: 1 / -1;
  min-height: 36px;
  max-height: 110px;
  overflow: auto;
  padding: 0.5rem 0.6rem;
  border-radius: 5px;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 0.76rem;
  line-height: 1.4;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.env-conflict-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.65rem;
  padding: 0.85rem 1.25rem;
  border-top: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.env-conflict-summary {
  margin-right: auto;
  color: var(--text-tertiary);
  font-size: 0.76rem;
}

.env-conflict-btn {
  padding: 0.55rem 1.15rem;
  border-radius: 7px;
  font-size: 0.86rem;
  font-weight: 500;
  cursor: pointer;
  transition: transform 0.2s;
}

.env-conflict-btn-secondary {
  border: 2px solid var(--border-color);
  background: white;
  color: var(--text-primary);
}

.env-conflict-btn-primary {
  border: none;
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 2px 8px var(--primary-shadow);
}

.env-conflict-btn:hover {
  transform: translateY(-1px);
}

.env-conflict-dialog-enter-active,
.env-conflict-dialog-leave-active {
  transition: opacity 0.2s;
}

.env-conflict-dialog-enter-active .env-conflict-dialog,
.env-conflict-dialog-leave-active .env-conflict-dialog {
  transition: transform 0.2s, opacity 0.2s;
}

.env-conflict-dialog-enter-from,
.env-conflict-dialog-leave-to {
  opacity: 0;
}

.env-conflict-dialog-enter-from .env-conflict-dialog,
.env-conflict-dialog-leave-to .env-conflict-dialog {
  opacity: 0;
  transform: scale(0.95) translateY(-8px);
}

@media (max-width: 640px) {
  .env-conflict-options {
    grid-template-columns: 1fr;
  }

  .env-conflict-toolbar,
  .env-conflict-footer {
    flex-wrap: wrap;
  }

  .env-conflict-summary {
    width: 100%;
    margin-right: 0;
  }
}
</style>
