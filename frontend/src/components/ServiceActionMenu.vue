<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import ConfirmDialog from './ConfirmDialog.vue'
import RollbackDialog from './RollbackDialog.vue'
import ScaleDialog from './ScaleDialog.vue'

const props = defineProps({
  service: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['update', 'rollback', 'restart', 'stop', 'scale', 'view', 'edit', 'delete'])

const showMenu = ref(false)
const wrapperRef = ref(null)
const showConfirmDialog = ref(false)
const showRollbackDialog = ref(false)
const showScaleDialog = ref(false)
const pendingAction = ref(null)

const actionMessages = {
  update: { title: '确认更新', message: '确定要更新此服务吗？' },
  restart: { title: '确认重启', message: '确定要重启此服务吗？' },
  stop: { title: '确认停止', message: '确定要停止此服务吗？' },
  delete: { title: '确认删除', message: '此操作不可恢复，确定要删除此服务吗？' }
}

const handleAction = (action) => {
  showMenu.value = false
  
  // 查看副本直接执行，不需要确认
  if (action === 'view') {
    emit(action)
    return
  }
  
  // 编辑服务直接触发
  if (action === 'edit') {
    emit(action)
    return
  }
  
  // 回滚显示版本选择对话框
  if (action === 'rollback') {
    showRollbackDialog.value = true
    return
  }
  
  // 调整副本显示副本数量对话框
  if (action === 'scale') {
    showScaleDialog.value = true
    return
  }
  
  // 其他操作显示确认对话框
  pendingAction.value = action
  showConfirmDialog.value = true
}

const handleConfirm = () => {
  if (pendingAction.value) {
    emit(pendingAction.value)
    pendingAction.value = null
  }
}

const handleCancelConfirm = () => {
  pendingAction.value = null
}

const handleRollbackConfirm = (version) => {
  emit('rollback', { service: props.service, version })
}

const handleScaleConfirm = (replicas) => {
  emit('scale', { service: props.service, replicas })
}

const closeMenu = () => {
  showMenu.value = false
}

const toggleMenu = async (event) => {
  event.stopPropagation()
  
  // 关闭所有其他菜单
  window.dispatchEvent(new CustomEvent('close-all-menus'))
  
  showMenu.value = !showMenu.value
}

const handleClickOutside = (event) => {
  if (wrapperRef.value && !wrapperRef.value.contains(event.target)) {
    showMenu.value = false
  }
}

const handleCloseAllMenus = () => {
  showMenu.value = false
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  window.addEventListener('close-all-menus', handleCloseAllMenus)
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  window.removeEventListener('close-all-menus', handleCloseAllMenus)
})
</script>

<template>
  <div class="action-wrapper" ref="wrapperRef">
    <button 
      class="btn-action"
      @click="toggleMenu"
    >
      操作
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="5" cy="12" r="1.5"/>
        <circle cx="12" cy="12" r="1.5"/>
        <circle cx="19" cy="12" r="1.5"/>
      </svg>
    </button>
    
    <div v-if="showMenu" class="action-menu">
      <div class="action-grid">
        <button 
          class="grid-item"
          @click="handleAction('restart')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21.5 2v6h-6M2.5 22v-6h6M2 11.5a10 10 0 0 1 18.8-4.3M22 12.5a10 10 0 0 1-18.8 4.2"/>
          </svg>
          <span>重启</span>
        </button>
        <button 
          class="grid-item"
          :disabled="service.status === 'deploying'"
          @click="handleAction('update')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M5 12h14"/>
            <path d="m12 5 7 7-7 7"/>
          </svg>
          <span>更新</span>
        </button>
        <button 
          class="grid-item"
          :disabled="service.status === 'stopped'"
          @click="handleAction('rollback')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 7v6h6"/>
            <path d="M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"/>
          </svg>
          <span>回滚</span>
        </button>
        <button 
          class="grid-item"
          :disabled="service.status === 'stopped' || service.serviceMode === 'global'"
          :title="service.serviceMode === 'global' ? 'global 模式不支持手动调整副本数' : ''"
          @click="handleAction('scale')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
            <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
            <line x1="12" y1="22.08" x2="12" y2="12"/>
          </svg>
          <span>调整副本</span>
        </button>
        <button 
          class="grid-item"
          @click="handleAction('view')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
            <circle cx="12" cy="12" r="3"/>
          </svg>
          <span>查看</span>
        </button>
        <button 
          class="grid-item"
          @click="handleAction('edit')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
          </svg>
          <span>编辑</span>
        </button>
        <button 
          class="grid-item"
          :disabled="service.status === 'stopped'"
          @click="handleAction('stop')"
        >
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="6" y="6" width="12" height="12" rx="2"/>
          </svg>
          <span>停止</span>
        </button>
        <button class="grid-item danger" @click="handleAction('delete')">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
          </svg>
          <span>删除</span>
        </button>
      </div>
    </div>
    
    <ConfirmDialog
      v-model:visible="showConfirmDialog"
      :title="pendingAction ? actionMessages[pendingAction].title : ''"
      :message="pendingAction ? actionMessages[pendingAction].message : ''"
      @confirm="handleConfirm"
      @cancel="handleCancelConfirm"
    />
    
    <RollbackDialog
      v-model:visible="showRollbackDialog"
      :service="service"
      @confirm="handleRollbackConfirm"
    />
    
    <ScaleDialog
      v-model:visible="showScaleDialog"
      :service="service"
      @confirm="handleScaleConfirm"
    />
  </div>
</template>

<style scoped>
.action-wrapper {
  position: relative;
}

.btn-action {
  padding: 0.5rem 1rem;
  background: var(--primary-gradient);
  color: white;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.85rem;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  transition: all 0.2s;
  box-shadow: 0 2px 6px rgba(102, 126, 234, 0.2);
}

.action-wrapper:has(.action-menu) .btn-action {
  pointer-events: none;
  opacity: 0.7;
}

.btn-action:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.action-menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 0.5rem);
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  padding: 0.75rem;
  z-index: 99999;
  animation: slideUp 0.2s ease;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}

.grid-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.375rem;
  padding: 0.75rem 0.5rem;
  background: var(--bg-secondary);
  border: none;
  color: var(--text-primary);
  font-size: 0.8rem;
  cursor: pointer;
  transition: all 0.2s;
  aspect-ratio: 1;
}

.grid-item svg {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
}

.grid-item span {
  white-space: nowrap;
  font-weight: 500;
}

.grid-item:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--primary-color);
}

.grid-item:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.grid-item.danger {
  color: var(--danger-color);
}

.grid-item.danger:hover:not(:disabled) {
  background: color-mix(in srgb, var(--danger-color) 10%, transparent);
  color: var(--danger-color);
}

.action-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0.5rem 0;
}
</style>
