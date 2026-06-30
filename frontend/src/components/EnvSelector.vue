<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

defineProps({
  environments: {
    type: Array,
    required: true
  },
  selectedEnv: {
    type: Number,
    default: null
  },
  currentEnvironment: {
    type: Object,
    default: null
  },
  currentUser: {
    type: Object,
    default: null
  }
})

const emit = defineEmits(['update:selectedEnv', 'addEnvironment', 'deleteEnvironment'])

const showEnvSelector = ref(false)
const selectorRef = ref(null)

const selectEnv = (envId) => {
  emit('update:selectedEnv', envId)
  showEnvSelector.value = false
}

const deleteEnv = (event, envId) => {
  event.stopPropagation()
  emit('deleteEnvironment', envId)
}

const addEnvironment = () => {
  emit('addEnvironment')
  showEnvSelector.value = false
}

const handleClickOutside = (event) => {
  if (selectorRef.value && !selectorRef.value.contains(event.target)) {
    showEnvSelector.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="env-selector-wrapper" ref="selectorRef">
    <!-- 环境徽章 -->
    <button 
      class="env-badge" 
      :style="{ '--env-color': currentEnvironment?.color || '#667eea' }"
      @click.stop="showEnvSelector = !showEnvSelector"
    >
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
      </svg>
      <span class="env-name">{{ currentEnvironment?.name || '未选择环境' }}</span>
      <span class="env-type">{{ currentEnvironment?.deployType || '' }}</span>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="env-arrow">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </button>
    
    <div v-if="showEnvSelector" class="env-dropdown">
      <div class="env-dropdown-header">
        <span>选择环境</span>
        <button class="env-close" @click="showEnvSelector = false">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
      <div class="env-dropdown-list">
        <button 
          v-for="env in environments" 
          :key="env.id"
          :class="['env-dropdown-item', { active: selectedEnv === env.id }]"
          :style="{ '--env-color': env.color }"
          @click="selectEnv(env.id)"
        >
          <span class="env-dropdown-dot"></span>
          <span class="env-dropdown-name">{{ env.name }}</span>
          <div class="env-actions">
            <svg v-if="selectedEnv === env.id" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <polyline points="20 6 9 17 4 12"/>
            </svg>
            <button 
              v-else
              class="env-delete-btn" 
              @click="deleteEnv($event, env.id)"
              title="删除环境"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
        </button>
      </div>
      <div class="env-dropdown-footer">
        <button v-if="currentUser" class="env-add-btn" @click="addEnvironment">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="12" y1="5" x2="12" y2="19"/>
            <line x1="5" y1="12" x2="19" y2="12"/>
          </svg>
          添加新环境
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.env-selector-wrapper {
  position: relative;
}

/* 环境徽章按钮 */
.env-badge {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.625rem 1rem;
  background: var(--bg-secondary);
  border: 2px solid var(--env-color);
  border-radius: 10px;
  color: var(--text-primary);
  font-weight: 500;
  transition: all 0.2s;
  cursor: pointer;
  height: 40px;
}

.env-badge:hover {
  border-color: var(--env-color);
  background: var(--bg-hover);
}

.env-badge svg:first-child {
  color: var(--env-color);
  flex-shrink: 0;
}

.env-name {
  font-weight: 600;
  font-size: 0.875rem;
  color: var(--text-primary);
}

.env-type {
  padding: 0.25rem 0.5rem;
  background: color-mix(in srgb, var(--env-color) 15%, transparent);
  color: var(--env-color);
  border-radius: 6px;
  font-size: 0.75rem;
  font-weight: 500;
  text-transform: capitalize;
}

.env-arrow {
  margin-left: 0.25rem;
  color: var(--text-tertiary);
  transition: transform 0.2s;
}

.env-badge:hover .env-arrow {
  color: var(--env-color);
}

.env-dropdown {
  position: absolute;
  right: 0;
  top: calc(100% + 0.75rem);
  width: 280px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 10px 40px rgba(102, 126, 234, 0.25);
  overflow: hidden;
  z-index: 1000;
  animation: slideDown 0.2s ease;
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(-10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.env-dropdown-header {
  padding: 1rem 1.25rem;
  background: var(--primary-gradient);
  color: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
  font-size: 0.875rem;
}

.env-close {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.env-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.env-dropdown-list {
  padding: 0.5rem;
  max-height: 320px;
  overflow-y: auto;
}

.env-dropdown-item {
  width: 100%;
  padding: 0.75rem 1rem;
  border-radius: 8px;
  background: transparent;
  border: none;
  color: var(--text-primary);
  font-weight: 500;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  text-align: left;
  transition: all 0.2s;
  margin-bottom: 0.25rem;
}

.env-dropdown-item:hover {
  background: var(--bg-hover);
}

.env-dropdown-item.active {
  background: var(--primary-light);
  color: var(--primary-color);
}

.env-dropdown-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--env-color);
  flex-shrink: 0;
}

.env-dropdown-name {
  flex: 1;
}

.env-actions {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  flex-shrink: 0;
}

.env-actions svg {
  color: var(--primary-color);
  flex-shrink: 0;
}

.env-delete-btn {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: transparent;
  border: none;
  color: var(--text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  opacity: 0;
  flex-shrink: 0;
}

.env-dropdown-item:hover .env-delete-btn {
  opacity: 1;
}

.env-delete-btn:hover {
  background: rgba(239, 68, 68, 0.1);
  color: var(--danger-color);
}

.env-dropdown-footer {
  padding: 0.5rem;
  border-top: 1px solid var(--border-color);
}

.env-add-btn {
  width: 100%;
  padding: 0.75rem 1rem;
  border-radius: 8px;
  background: var(--primary-gradient);
  border: none;
  color: white;
  font-weight: 500;
  font-size: 0.875rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  transition: all 0.2s;
}

.env-add-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}
</style>
