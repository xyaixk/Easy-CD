<script setup>
import { ref, watch, computed } from 'vue'
import { getAvailableVersions } from '@/api/service'
import toast from '@/utils/toast'

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

// Version history from backend
const versionHistory = ref([])
const loading = ref(false)
const searchKeyword = ref('')

const selectedVersion = ref('')
const isHolding = ref(false)
const holdProgress = ref(0)
let holdTimer = null
let progressInterval = null

// Fetch available versions when dialog opens
watch(() => props.visible, async (visible) => {
  if (visible && props.service?.id) {
    await fetchVersions()
  } else if (!visible) {
    // Only reset when dialog closes (not when opening)
    versionHistory.value = []
    selectedVersion.value = ''
    searchKeyword.value = ''
  }
})

// Format file size for display
const formatSize = (bytes) => {
  if (!bytes || bytes === 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(1)} ${units[unitIndex]}`
}

// Fetch versions from backend
const fetchVersions = async () => {
  loading.value = true
  try {
    // Note: request interceptor already extracts response.data
    const data = await getAvailableVersions(props.service.id)
    
    if (Array.isArray(data)) {
      versionHistory.value = data.map(v => ({
        version: v.version,
        isCurrent: v.isCurrent,
        digest: v.digest,
        size: v.size,
        createdTime: v.createdTime
      }))
      
      // Sort: current version first, then 'latest', then by version name descending
      versionHistory.value.sort((a, b) => {
        if (a.isCurrent) return -1
        if (b.isCurrent) return 1
        if (a.version === 'latest') return -1
        if (b.version === 'latest') return 1
        // Priority tags come before version numbers
        const priorityTags = ['stable', 'mainline', 'alpine']
        const aPriority = priorityTags.includes(a.version)
        const bPriority = priorityTags.includes(b.version)
        if (aPriority && !bPriority) return -1
        if (!aPriority && bPriority) return 1
        return b.version.localeCompare(a.version)
      })
    } else {
      console.error('Invalid data:', data)
      toast.error('Failed to fetch versions')
    }
  } catch (error) {
    console.error('Failed to fetch versions:', error)
    toast.error('Failed to fetch versions: ' + error.message)
  } finally {
    loading.value = false
  }
}

// Filtered version list based on search keyword
const filteredVersions = computed(() => {
  if (!searchKeyword.value.trim()) {
    return versionHistory.value
  }
  const keyword = searchKeyword.value.toLowerCase().trim()
  return versionHistory.value.filter(v => v.version.toLowerCase().includes(keyword))
})

const startHold = () => {
  if (!selectedVersion.value) return
  
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
  emit('confirm', selectedVersion.value)
  emit('update:visible', false)
  selectedVersion.value = ''
}

const handleCancel = () => {
  endHold()
  emit('cancel')
  emit('update:visible', false)
  selectedVersion.value = ''
}

const handleClickOutside = (e) => {
  if (e.target.classList.contains('confirm-dialog-overlay')) {
    handleCancel()
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
            <h3>回滚服务</h3>
          </div>
          
          <div class="dialog-body">
            <p class="dialog-desc">请选择要回滚到的版本：</p>
            
            <!-- Search Input -->
            <div v-if="!loading && versionHistory.length > 0" class="search-box">
              <input 
                type="text" 
                v-model="searchKeyword" 
                placeholder="搜索版本..."
                class="search-input"
              />
            </div>
            
            <!-- Loading State -->
            <div v-if="loading" class="loading-state">
              <div class="spinner"></div>
              <p>加载版本列表中...</p>
            </div>
            
            <!-- Empty State -->
            <div v-else-if="!versionHistory.length" class="empty-state">
              <p>暂无可用版本</p>
            </div>
            
            <!-- Version List -->
            <div v-else class="version-list">
              <label 
                v-for="item in filteredVersions" 
                :key="item.version"
                class="version-item"
                :class="{ 'selected': selectedVersion === item.version, 'current': item.isCurrent }"
              >
                <input 
                  type="radio" 
                  :value="item.version" 
                  v-model="selectedVersion"
                  name="version"
                  :disabled="item.isCurrent"
                />
                <div class="version-info">
                  <div class="version-header">
                    <span class="version-tag">{{ item.version }}</span>
                    <span v-if="item.isCurrent" class="current-badge">当前版本</span>
                  </div>
                  <div class="version-meta">
                    <span v-if="item.size" class="meta-item">
                      <span class="meta-label">大小:</span>
                      <span class="meta-value">{{ formatSize(item.size) }}</span>
                    </span>
                    <span v-if="item.digest" class="meta-item">
                      <span class="meta-label">Digest:</span>
                      <span class="meta-value digest-hash">{{ item.digest.substring(7, 19) }}</span>
                    </span>
                  </div>
                </div>
              </label>
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
              :disabled="!selectedVersion"
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
  min-width: 500px;
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
  padding: 1.5rem;
}

.dialog-desc {
  margin: 0 0 1rem 0;
  font-size: 0.95rem;
  color: var(--text-secondary);
}

.search-box {
  margin-bottom: 1rem;
}

.search-input {
  width: 100%;
  padding: 0.75rem 1rem;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-primary);
  color: var(--text-primary);
  font-size: 0.95rem;
  transition: border-color 0.2s;
}

.search-input:focus {
  outline: none;
  border-color: var(--primary-color);
}

.search-input::placeholder {
  color: var(--text-tertiary);
}

.loading-state,
.empty-state {
  text-align: center;
  padding: 2rem;
  color: var(--text-secondary);
}

.spinner {
  margin: 0 auto 1rem;
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-color);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.version-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-height: 300px;
  overflow-y: auto;
}

.version-item {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  border: 2px solid var(--border-color);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.version-item:hover {
  border-color: var(--primary-color);
  background: var(--bg-hover);
}

.version-item.selected {
  border-color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 10%, transparent);
}

.version-item.current {
  opacity: 0.6;
  cursor: not-allowed;
}

.version-item.current:hover {
  border-color: var(--border-color);
  background: transparent;
}

.version-item input[type="radio"] {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.version-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.version-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.version-tag {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 1rem;
}

.current-badge {
  padding: 0.125rem 0.5rem;
  background: var(--primary-color);
  color: white;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 500;
}

.version-meta {
  display: flex;
  gap: 1rem;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.85rem;
}

.meta-label {
  color: var(--text-tertiary);
  font-weight: 500;
}

.meta-value {
  color: var(--text-secondary);
}

.digest-hash {
  font-family: monospace;
  font-size: 0.8rem;
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
