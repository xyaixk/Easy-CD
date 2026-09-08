<script setup>
import { computed } from 'vue'
import EnvSelector from './EnvSelector.vue'

const props = defineProps({
  environments: {
    type: Array,
    required: true
  },
  selectedEnv: {
    type: Number,
    default: null
  },
  currentUser: {
    type: Object,
    default: null
  },
  activeTaskCount: {
    type: Number,
    default: 0
  },
  tasksOpen: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:selectedEnv', 'addEnvironment', 'deleteEnvironment', 'openConfig', 'openLogin', 'logout', 'openLogs', 'openTasks'])

const currentEnvironment = computed(() => {
  return props.environments.find(e => e.id === props.selectedEnv)
})

const hasLokiConfig = computed(() => {
  const rawConfig = currentEnvironment.value?.config
  if (!rawConfig) return false
  try {
    const config = typeof rawConfig === 'string' ? JSON.parse(rawConfig) : rawConfig
    return typeof config?.lokiUri === 'string' && config.lokiUri.trim().length > 0
  } catch (error) {
    return false
  }
})
</script>

<template>
  <header class="header">
    <div class="header-content">
      <div class="logo-section">
        <div class="logo-icon">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <circle cx="12" cy="12" r="3"/>
            <path d="M12 1v6m0 6v6m5.2-14.2l-4.2 4.2m-1 1l-4.2 4.2M23 12h-6m-6 0H5m14.2 5.2l-4.2-4.2m-1-1l-4.2-4.2"/>
          </svg>
        </div>
        <div class="logo-info">
          <h1 class="logo-text">CD Platform</h1>
          <span class="logo-subtitle">持续部署平台</span>
        </div>
      </div>
      
      <div class="header-actions">
        <!-- 环境选择器 -->
        <EnvSelector 
          :environments="environments"
          :selected-env="selectedEnv"
          :current-environment="currentEnvironment"
          :current-user="currentUser"
          @update:selectedEnv="emit('update:selectedEnv', $event)"
          @add-environment="emit('addEnvironment')"
          @delete-environment="emit('deleteEnvironment', $event)"
        />

        <button
          class="header-btn task-btn"
          :class="{ active: tasksOpen }"
          title="部署任务"
          :aria-pressed="tasksOpen"
          @click="emit('openTasks')"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10"/>
            <polyline points="12 6 12 12 16 14"/>
          </svg>
          <span v-if="activeTaskCount > 0" class="task-badge">{{ activeTaskCount }}</span>
        </button>
        
        <button v-if="hasLokiConfig" class="header-btn" title="日志" @click="emit('openLogs')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="8" y1="13" x2="16" y2="13"/>
            <line x1="8" y1="17" x2="16" y2="17"/>
            <line x1="8" y1="9" x2="10" y2="9"/>
          </svg>
        </button>

        <button class="header-btn" title="配置管理" @click="emit('openConfig')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/>
            <circle cx="12" cy="12" r="3"/>
          </svg>
        </button>
        
        <button
          v-if="!currentUser"
          class="header-btn user-btn"
          title="登录"
          @click="emit('openLogin')"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
            <circle cx="12" cy="7" r="4"/>
          </svg>
        </button>

        <button
          v-else
          class="header-user"
          type="button"
          @click="emit('logout')"
          :title="`退出 ${currentUser.username}`"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
            <circle cx="12" cy="7" r="4"/>
          </svg>
          <span>{{ currentUser.username }}</span>
          <span class="logout-text">退出</span>
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.header {
  background: var(--primary-gradient);
  border-bottom: none;
  box-shadow: var(--header-shadow);
  position: sticky;
  top: 0;
  z-index: 100;
  transition: background 0.3s ease, box-shadow 0.3s ease;
}

.header-content {
  max-width: var(--content-max-width);
  margin: 0 auto;
  padding: 0 2rem;
  height: 68px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 2rem;
}

.logo-section {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  flex-shrink: 0;
}

.logo-icon {
  width: 42px;
  height: 42px;
  background: rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(10px);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  border: 1px solid rgba(255, 255, 255, 0.3);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.logo-info {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.logo-text {
  font-size: 1.25rem;
  font-weight: 700;
  color: white;
  line-height: 1;
  letter-spacing: -0.02em;
}

.logo-subtitle {
  font-size: 0.7rem;
  color: rgba(255, 255, 255, 0.8);
  font-weight: 500;
  letter-spacing: 0.05em;
}

.header-actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-shrink: 0;
}

.header-btn {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.15);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  backdrop-filter: blur(10px);
}

.header-btn:hover {
  background: rgba(255, 255, 255, 0.25);
  border-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-1px);
}

.user-btn {
  background: rgba(255, 255, 255, 0.2);
}

.task-btn {
  position: relative;
}

.task-btn.active {
  color: var(--primary-color);
  background: #fff;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.28);
}

.task-badge {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--warning-color, #f59e0b);
  color: white;
  font-size: 0.68rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.25);
}

.header-user {
  height: 40px;
  border-radius: 10px;
  padding: 0 0.875rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(255, 255, 255, 0.15);
  color: white;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  backdrop-filter: blur(10px);
}

.header-user:hover {
  background: rgba(255, 255, 255, 0.25);
}

.logout-text {
  font-size: 0.75rem;
  opacity: 0.8;
}

@media (max-width: 640px) {
  .header-content {
    height: 60px;
    padding: 0 0.75rem;
    gap: 0.5rem;
  }

  .logo-section {
    gap: 0;
  }

  .logo-icon {
    width: 36px;
    height: 36px;
    border-radius: 10px;
  }

  .logo-icon svg {
    width: 20px;
    height: 20px;
  }

  .logo-info {
    display: none;
  }

  .header-actions {
    flex: 1;
    flex-shrink: 1;
    min-width: 0;
    justify-content: flex-end;
    gap: 0.375rem;
  }

  .header-btn,
  .header-user {
    width: 36px;
    height: 36px;
    flex: 0 0 36px;
    padding: 0;
  }

  .header-user {
    justify-content: center;
  }

  .header-user span {
    display: none;
  }
}

@media (max-width: 420px) {
  .header-content {
    padding: 0 0.5rem;
    gap: 0.375rem;
  }

  .logo-icon,
  .header-btn,
  .header-user {
    width: 34px;
    height: 34px;
    flex-basis: 34px;
  }

  .header-actions {
    gap: 0.25rem;
  }
}
</style>
