<script setup>
import { ref, computed } from 'vue'
import ServiceActionMenu from './ServiceActionMenu.vue'

const props = defineProps({
  service: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['update', 'rollback', 'restart', 'stop', 'scale', 'view', 'edit', 'delete'])

const showActionMenu = ref(false)

const getStatusColor = (status) => {
  const colors = {
    running: '#10b981',      // 绿色 - 运行中
    stopped: '#94a3b8',      // 灰色 - 已停止
    deploying: '#f59e0b',    // 橙色 - 部署中
    failed: '#ef4444',       // 红色 - 失败
    degraded: '#f97316',     // 深橙 - 降级运行
    scaling: '#3b82f6',      // 蓝色 - 扩缩容中
    unknown: '#6b7280'       // 深灰 - 未知
  }
  return colors[status] || '#94a3b8'
}

const getStatusText = (status) => {
  const texts = {
    running: '运行中',
    stopped: '已停止',
    deploying: '部署中',
    failed: '失败',
    degraded: '降级',
    scaling: '扩缩容中',
    unknown: '未知'
  }
  return texts[status] || status
}

// 格式化字节大小
const formatBytes = (bytes) => {
  if (!bytes || bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

// 计算CPU百分比显示
const cpuDisplay = computed(() => {
  const cpu = props.service.cpuPercent
  return cpu != null ? Math.round(cpu) + '%' : '0%'
})

// 计算内存显示
const memoryDisplay = computed(() => {
  const usage = props.service.memoryUsage
  return formatBytes(usage)
})
</script>

<template>
  <div class="service-card">
    <div class="service-header">
      <div class="service-title-section">
        <h3 class="service-name">{{ service.name }}</h3>
        <span 
          class="service-status"
          :style="{ '--status-color': getStatusColor(service.status) }"
        >
          <span class="status-dot" :class="{ 'spinning': service.status === 'deploying' }"></span>
          {{ getStatusText(service.status) }}
        </span>
        <span v-if="service.serviceMode === 'global'" class="service-mode-badge" title="全局模式：每个 Swarm 节点自动运行 1 个副本">
          GLOBAL
        </span>
      </div>
      <span class="service-version">{{ service.version }}</span>
    </div>
    
    <p class="service-description">{{ service.description }}</p>
    
    <div class="replicas-section">
      <div class="replicas-display">
        <span class="replica-value healthy">{{ service.healthyInstances }}</span>
        <span class="replica-label">健康</span>
        <span class="replica-divider">/</span>
        <span class="replica-value running">{{ service.instances }}</span>
        <span class="replica-label">运行</span>
        <span class="replica-divider">/</span>
        <span class="replica-value desired">{{ service.desiredInstances }}</span>
        <span class="replica-label">期望</span>
      </div>
    </div>
    
    <div class="service-footer">
      <span class="last-deploy">最后部署: {{ service.lastDeploy }}</span>
      <ServiceActionMenu 
        :service="service"
        @update="emit('update', service)"
        @rollback="(payload) => emit('rollback', payload)"
        @restart="emit('restart', service)"
        @stop="emit('stop', service)"
        @scale="(payload) => emit('scale', payload)"
        @view="emit('view', service)"
        @edit="emit('edit', service)"
        @delete="emit('delete', service)"
      />
    </div>
  </div>
</template>

<style scoped>
.service-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 1.25rem;
  transition: all 0.3s;
}

.service-card:hover {
  box-shadow: var(--shadow-xl);
  border-color: var(--primary-color);
}

.service-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 0.75rem;
}

.service-title-section {
  flex: 1;
}

.service-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}



.service-status {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.25rem 0.75rem;
  border-radius: 6px;
  background: color-mix(in srgb, var(--status-color) 10%, transparent);
  color: var(--status-color);
  font-size: 0.75rem;
  font-weight: 600;
}

.service-mode-badge {
  display: inline-flex;
  align-items: center;
  margin-left: 0.5rem;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.05em;
  vertical-align: middle;
}

.replicas-section {
  padding: 0.625rem 1rem;
  background: var(--bg-primary);
  border-radius: 8px;
  margin-bottom: 0.875rem;
  text-align: center;
}

.replicas-display {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 0.375rem;
}

.replica-label {
  font-size: 0.65rem;
  color: var(--text-tertiary);
  font-weight: 500;
  margin-right: 0.25rem;
}

.replica-value {
  font-size: 1.75rem;
  font-weight: 700;
  font-family: 'Courier New', monospace;
  line-height: 1;
}

.replica-value.healthy {
  color: var(--success-color);
}

.replica-value.running {
  color: var(--info-color);
}

.replica-value.desired {
  color: var(--text-secondary);
}

.replica-divider {
  font-size: 1.25rem;
  color: var(--text-tertiary);
  font-weight: 300;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 2s infinite;
}

.status-dot.spinning {
  width: 10px;
  height: 10px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  background: transparent;
  animation: spin 0.8s linear infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.service-version {
  padding: 0.25rem 0.625rem;
  background: var(--bg-hover);
  border-radius: 6px;
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-secondary);
  font-family: 'Courier New', monospace;
  white-space: nowrap;
  flex-shrink: 0;
}

.service-description {
  color: var(--text-secondary);
  font-size: 0.85rem;
  line-height: 1.5;
  margin-bottom: 0.875rem;
}

.service-info {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
  margin-bottom: 0.875rem;
  padding: 0.625rem;
  background: var(--bg-primary);
  border-radius: 8px;
}

.info-item {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
}

.info-item svg {
  color: var(--text-tertiary);
}

.service-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 0.875rem;
  border-top: 1px solid var(--border-color);
}

.last-deploy {
  font-size: 0.8rem;
  color: var(--text-tertiary);
}
</style>
