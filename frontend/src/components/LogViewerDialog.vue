<script setup>
import { computed, ref, watch, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { getServiceLogInstances, getServiceLogsWsUrl } from '@/api/service'
import { useBodyScrollLock } from '@/composables/useBodyScrollLock'
import {
  canFollowLogTarget,
  formatServiceLogInstanceLabel,
  measureLogColumns,
  shortTaskId,
  sortServiceLogInstances
} from '@/utils/serviceLogs'

const LOG_TAIL = 500
const INSTANCE_REFRESH_INTERVAL = 5000
const MAX_BUFFER_CHARS = 5 * 1024 * 1024
const SINGLE_LINE_COLUMN_STEP = 64
const MAX_SINGLE_LINE_COLUMNS = 4096

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  replica: {
    type: Object,
    default: () => ({})
  },
  serviceId: {
    type: Number,
    required: true
  }
})

useBodyScrollLock(() => props.visible)

const emit = defineEmits(['update:visible'])

let logChunks = []
let bufferedChars = 0
const lineCount = ref(0)
const isLoading = ref(false)
const isFollowing = ref(false)
const autoScroll = ref(true)
const wrapLines = ref(false)

const logInstances = ref([])
const selectedTaskId = ref('')
const expiredSelection = ref(null)
const instancesLoading = ref(false)
const instancesError = ref('')
let instanceRefreshTimer = null

const runningCount = computed(() => logInstances.value.filter(item => item.running).length)
const selectedInstance = computed(() =>
  logInstances.value.find(item => item.taskId === selectedTaskId.value)
  || (expiredSelection.value?.taskId === selectedTaskId.value ? expiredSelection.value : null)
)
const followAvailable = computed(() =>
  canFollowLogTarget(selectedTaskId.value, logInstances.value)
)

let ws = null

const termRef = ref(null)
let term = null
let fitAddon = null
let resizeObserver = null
let terminalScreen = null
let currentLogColumn = 0
let maxLogColumns = 0

const syncHorizontalScroll = () => {
  if (!termRef.value) return
  terminalScreen ||= termRef.value.querySelector('.xterm-screen')
  if (!terminalScreen) return

  const scrollLeft = wrapLines.value ? 0 : termRef.value.scrollLeft
  term?.element?.style.setProperty('--log-content-width', `${terminalScreen.offsetWidth}px`)
  terminalScreen.style.setProperty('--log-horizontal-offset', `${-scrollLeft}px`)
}

const fitTerminal = () => {
  if (!term || !fitAddon) return
  const dimensions = fitAddon.proposeDimensions()
  if (!dimensions) return

  let columns = dimensions.cols
  if (!wrapLines.value && maxLogColumns > columns) {
    columns = Math.min(
      MAX_SINGLE_LINE_COLUMNS,
      Math.ceil(maxLogColumns / SINGLE_LINE_COLUMN_STEP) * SINGLE_LINE_COLUMN_STEP
    )
  }
  if (term.cols !== columns || term.rows !== dimensions.rows) {
    term.resize(columns, dimensions.rows)
  }
  if (wrapLines.value && termRef.value) termRef.value.scrollLeft = 0
  syncHorizontalScroll()
}

const trackLogWidth = (text) => {
  const measurement = measureLogColumns(text, currentLogColumn, maxLogColumns)
  currentLogColumn = measurement.currentColumn
  maxLogColumns = measurement.maxColumns
  if (!wrapLines.value && term && maxLogColumns > term.cols) fitTerminal()
}

const writeTerminalText = (text) => {
  if (!term || !text) return
  trackLogWidth(text)
  term.write(text)
  if (autoScroll.value) term.scrollToBottom()
}

const initTerminal = () => {
  if (term) return
  term = new Terminal({
    disableStdin: true,
    cursorBlink: false,
    fontSize: 13,
    fontFamily: "'Consolas', 'Monaco', 'Courier New', monospace",
    scrollback: 10000,
    theme: {
      background: '#1e1e1e',
      foreground: '#d4d4d4',
      cursor: '#1e1e1e',
      selectionBackground: 'rgba(148, 163, 184, 0.3)'
    }
  })
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(termRef.value)
  terminalScreen = termRef.value.querySelector('.xterm-screen')
  fitTerminal()

  resizeObserver = new ResizeObserver(() => {
    if (!fitAddon) return
    try { fitTerminal() } catch (_) {}
  })
  resizeObserver.observe(termRef.value)
}

const teardownTerminal = () => {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (term) { term.dispose(); term = null }
  fitAddon = null
  terminalScreen = null
}

const writeHint = (text) => {
  writeTerminalText(`\x1b[90m${text}\x1b[0m\r\n`)
}

const resetLogOutput = () => {
  logChunks = []
  bufferedChars = 0
  lineCount.value = 0
  currentLogColumn = 0
  maxLogColumns = 0
  if (term) {
    term.reset()
    fitTerminal()
  }
  if (termRef.value) termRef.value.scrollLeft = 0
  syncHorizontalScroll()
}

const appendLogText = (text) => {
  logChunks.push(text)
  bufferedChars += text.length
  lineCount.value += (text.match(/\n/g) || []).length
  while (bufferedChars > MAX_BUFFER_CHARS && logChunks.length > 1) {
    bufferedChars -= logChunks.shift().length
  }
}

const closeLogs = () => {
  if (!ws) return
  const connection = ws
  ws = null
  connection.onopen = null
  connection.onmessage = null
  connection.onclose = null
  connection.onerror = null
  try { connection.close() } catch (_) {}
}

const loadLogs = ({ reset = true } = {}) => {
  if (reset) resetLogOutput()
  isLoading.value = true
  const connectionDecoder = new TextDecoder()

  try {
    const follow = isFollowing.value
    const tail = follow ? 0 : LOG_TAIL
    const connection = new WebSocket(getServiceLogsWsUrl(
      props.serviceId,
      tail,
      follow,
      selectedTaskId.value || null
    ))
    ws = connection
    connection.binaryType = 'arraybuffer'

    connection.onopen = () => {
      if (ws !== connection) return
      isLoading.value = false
      if (follow) writeHint('--- 实时日志已连接，仅追加新输出 ---')
    }

    connection.onmessage = (event) => {
      if (ws !== connection || !term) return
      isLoading.value = false

      let text
      if (typeof event.data === 'string') {
        text = event.data
      } else {
        text = connectionDecoder.decode(event.data, { stream: true })
      }
      writeTerminalText(text)
      appendLogText(text)
    }

    connection.onclose = () => {
      if (ws !== connection) return
      isLoading.value = false
      if (follow) isFollowing.value = false
      ws = null
    }

    connection.onerror = (error) => {
      if (ws !== connection) return
      console.error('日志流连接错误:', error)
      isLoading.value = false
      if (follow) isFollowing.value = false
      writeHint('--- 日志流连接错误 ---')
    }
  } catch (error) {
    console.error('加载日志失败:', error)
    writeHint('加载日志失败: ' + error.message)
    isLoading.value = false
  }
}

const refreshLogInstances = async () => {
  if (instancesLoading.value) return
  instancesLoading.value = true
  const previousSelection = selectedInstance.value
  try {
    const instances = sortServiceLogInstances(await getServiceLogInstances(props.serviceId))
    logInstances.value = instances
    instancesError.value = ''

    if (selectedTaskId.value && !instances.some(item => item.taskId === selectedTaskId.value)) {
      expiredSelection.value = previousSelection || {
        taskId: selectedTaskId.value,
        name: `实例 ${shortTaskId(selectedTaskId.value)}`,
        running: false
      }
    } else {
      expiredSelection.value = null
    }

    if (isFollowing.value && !followAvailable.value) {
      closeLogs()
      isFollowing.value = false
      writeHint('--- 当前实例已停止或不可追溯，实时推送已结束 ---')
    }
  } catch (error) {
    console.error('加载日志实例失败:', error)
    instancesError.value = error.message || '实例列表加载失败'
  } finally {
    instancesLoading.value = false
  }
}

const startInstanceRefresh = () => {
  stopInstanceRefresh()
  instanceRefreshTimer = setInterval(refreshLogInstances, INSTANCE_REFRESH_INTERVAL)
}

const stopInstanceRefresh = () => {
  if (!instanceRefreshTimer) return
  clearInterval(instanceRefreshTimer)
  instanceRefreshTimer = null
}

watch(() => props.visible, async (visible) => {
  if (visible) {
    isFollowing.value = false
    wrapLines.value = false
    selectedTaskId.value = ''
    expiredSelection.value = null
    instancesError.value = ''
    isLoading.value = true
    await nextTick()
    initTerminal()
    await refreshLogInstances()
    if (!props.visible) return
    loadLogs()
    startInstanceRefresh()
  } else {
    stopInstanceRefresh()
    closeLogs()
    isFollowing.value = false
    isLoading.value = false
    teardownTerminal()
  }
})

const handleTargetChange = () => {
  closeLogs()
  isFollowing.value = false
  if (!selectedTaskId.value || logInstances.value.some(item => item.taskId === selectedTaskId.value)) {
    expiredSelection.value = null
  }
  loadLogs()
}

const scrollToBottom = () => {
  if (term) term.scrollToBottom()
}

const toggleFollow = () => {
  if (isFollowing.value) {
    closeLogs()
    isFollowing.value = false
    return
  }
  if (!followAvailable.value) return

  isFollowing.value = true
  closeLogs()
  loadLogs({ reset: false })
}

const toggleAutoScroll = () => {
  autoScroll.value = !autoScroll.value
  if (autoScroll.value) scrollToBottom()
}

const toggleLineWrap = () => {
  wrapLines.value = !wrapLines.value
  try { fitTerminal() } catch (_) {}
  if (autoScroll.value) scrollToBottom()
}

const clearLogs = () => resetLogOutput()

const downloadLogs = () => {
  const content = logChunks.join('')
    .replace(/\x1b\[[0-9;]*m/g, '')
    .replace(/\r\n/g, '\n')
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  const serviceName = props.replica?.name || 'service'
  const targetName = selectedInstance.value?.name || 'running'
  const safeName = `${serviceName}-${targetName}`.replace(/[\\/:*?"<>|]/g, '-')
  a.download = `${safeName}-logs-${Date.now()}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const handleClose = () => emit('update:visible', false)

onUnmounted(() => {
  stopInstanceRefresh()
  closeLogs()
  teardownTerminal()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay">
        <div class="dialog-container" @click.stop>
          <div class="dialog-header">
            <div class="header-content">
              <div class="header-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                  <line x1="16" y1="13" x2="8" y2="13"/>
                  <line x1="16" y1="17" x2="8" y2="17"/>
                  <polyline points="10 9 9 9 8 9"/>
                </svg>
              </div>
              <div>
                <h3>服务日志</h3>
                <p class="replica-name">{{ replica?.name || '' }}</p>
              </div>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
      
          <div class="dialog-toolbar">
            <div class="instance-selector">
              <label for="service-log-instance">实例</label>
              <select
                id="service-log-instance"
                v-model="selectedTaskId"
                :disabled="instancesLoading && !logInstances.length"
                @change="handleTargetChange"
              >
                <option value="">全部运行中实例（{{ runningCount }}）</option>
                <option
                  v-if="expiredSelection"
                  :value="expiredSelection.taskId"
                  disabled
                >
                  {{ expiredSelection.name }} · 已不可追溯 · {{ shortTaskId(expiredSelection.taskId) }}
                </option>
                <option
                  v-for="instance in logInstances"
                  :key="instance.taskId"
                  :value="instance.taskId"
                >
                  {{ formatServiceLogInstanceLabel(instance) }}
                </option>
              </select>
              <button
                class="toolbar-btn refresh-instances"
                type="button"
                :disabled="instancesLoading"
                @click="refreshLogInstances"
                title="刷新实例列表"
                aria-label="刷新实例列表"
              >
                <svg
                  class="refresh-icon"
                  :class="{ spinning: instancesLoading }"
                  width="16"
                  height="16"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M20 12a8 8 0 1 1-2.34-5.66L20 8"/>
                  <polyline points="20 3 20 8 15 8"/>
                </svg>
              </button>
            </div>

            <span v-if="instancesError" class="instance-error" :title="instancesError">
              实例列表加载失败
            </span>

            <button 
              class="toolbar-btn"
              :class="{ active: isFollowing }"
              :disabled="!followAvailable"
              @click="toggleFollow"
              :title="followAvailable ? '实时推送' : '历史实例仅支持静态日志'"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                <polyline points="17 6 23 6 23 12"/>
              </svg>
              {{ isFollowing ? '实时' : '静态' }}
            </button>

            <button
              class="toolbar-btn"
              :class="{ active: wrapLines }"
              type="button"
              @click="toggleLineWrap"
              :title="wrapLines ? '切换为单行展示' : '切换为自动换行'"
              :aria-pressed="wrapLines"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M4 6h16M4 12h13a3 3 0 0 1 0 6h-3M4 18h6"/>
                <polyline points="17 15 14 18 17 21"/>
              </svg>
              {{ wrapLines ? '自动换行' : '单行' }}
            </button>
            
            <button 
              class="toolbar-btn"
              :class="{ active: autoScroll }"
              @click="toggleAutoScroll"
              title="自动滚动"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/>
                <polyline points="19 12 12 19 5 12"/>
              </svg>
              自动滚动
            </button>
            
            <button 
              class="toolbar-btn"
              @click="clearLogs"
              title="清空日志"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
              </svg>
              清空
            </button>
            
            <button 
              class="toolbar-btn"
              @click="downloadLogs"
              title="下载日志"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                <polyline points="7 10 12 15 17 10"/>
                <line x1="12" y1="15" x2="12" y2="3"/>
              </svg>
              下载
            </button>
            
            <div class="toolbar-info">
              <span>共 {{ lineCount }} 行</span>
            </div>
          </div>
          
          <div class="dialog-body">
            <div v-show="isLoading" class="loading-state">
              <div class="loading-spinner"></div>
              <p>正在加载日志...</p>
            </div>
            
            <div class="terminal-body">
              <div
                ref="termRef"
                class="terminal-container"
                :class="{ 'wrap-lines': wrapLines }"
                @scroll.passive="syncHorizontalScroll"
              ></div>
            </div>
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
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
}

.dialog-container {
  background: var(--bg-secondary);
  border-radius: 0;
  width: 100vw;
  height: 100vh;
  display: flex;
  flex-direction: column;
  box-shadow: none;
  overflow: hidden;
}

.dialog-header {
  padding: 0.875rem 1.5rem;
  background: var(--primary-gradient);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.header-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.dialog-header h3 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: white;
}

.replica-name {
  margin: 0;
  font-size: 0.875rem;
  color: rgba(255, 255, 255, 0.8);
  margin-top: 0.25rem;
}

.btn-close {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-close:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.dialog-toolbar {
  padding: 1rem 2rem;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  gap: 0.75rem;
  align-items: center;
  flex-wrap: wrap;
  background: var(--bg-primary);
}

.instance-selector {
  min-width: 320px;
  max-width: 660px;
  flex: 1 1 440px;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.instance-selector label {
  flex: none;
  color: var(--text-secondary);
  font-size: 0.82rem;
  font-weight: 600;
}

.instance-selector select {
  min-width: 0;
  width: 100%;
  height: 34px;
  padding: 0 2rem 0 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.82rem;
  cursor: pointer;
}

.instance-selector select:focus {
  border-color: var(--primary-color);
  outline: none;
  box-shadow: 0 0 0 2px var(--primary-light);
}

.refresh-instances {
  flex: none;
  width: 34px;
  height: 34px;
  padding: 0;
  justify-content: center;
}

.refresh-icon {
  display: block;
  width: 16px;
  height: 16px;
  flex: none;
  transform-origin: center;
}

.refresh-icon.spinning {
  animation: spin 0.8s linear infinite;
}

.instance-error {
  max-width: 130px;
  overflow: hidden;
  color: var(--danger-color, #dc2626);
  font-size: 0.78rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toolbar-btn {
  padding: 0.5rem 1rem;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.875rem;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
}

.toolbar-btn:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
  background: var(--primary-light);
}

.toolbar-btn.active {
  border-color: var(--primary-color);
  background: var(--primary-color);
  color: white;
}

.toolbar-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.toolbar-btn:disabled:hover {
  border-color: var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-primary);
}

.toolbar-info {
  margin-left: auto;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.dialog-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  position: relative;
}

.loading-state {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(30, 30, 30, 0.85);
  color: #d4d4d4;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--border-color);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 1rem;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 终端区域：深色底，内边距和 xterm 背景一致 */
.terminal-body {
  flex: 1;
  min-height: 0;
  background: #1e1e1e;
  padding: 0.75rem 1rem;
}

.terminal-container {
  width: 100%;
  height: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-color: #555 #2d2d2d;
  scrollbar-width: thin;
}

.terminal-container.wrap-lines {
  overflow-x: hidden;
}

.terminal-container:not(.wrap-lines) :deep(.xterm) {
  position: sticky;
  left: 0;
}

.terminal-container:not(.wrap-lines) :deep(.xterm::after) {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: var(--log-content-width, 100%);
  height: 1px;
  pointer-events: none;
}

.terminal-container :deep(.xterm-screen) {
  translate: var(--log-horizontal-offset, 0) 0;
}

.terminal-container::-webkit-scrollbar {
  height: 8px;
}

.terminal-container::-webkit-scrollbar-track {
  background: #2d2d2d;
}

.terminal-container::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.terminal-container::-webkit-scrollbar-thumb:hover {
  background: #666;
}

/* xterm 自身滚动条深色化 */
.terminal-container :deep(.xterm-viewport) {
  overscroll-behavior: contain;
}

.terminal-container :deep(.xterm-viewport)::-webkit-scrollbar {
  width: 8px;
}

.terminal-container :deep(.xterm-viewport)::-webkit-scrollbar-track {
  background: #2d2d2d;
}

.terminal-container :deep(.xterm-viewport)::-webkit-scrollbar-thumb {
  background: #555;
  border-radius: 4px;
}

.terminal-container :deep(.xterm-viewport)::-webkit-scrollbar-thumb:hover {
  background: #666;
}

.dialog-footer {
  padding: 1.5rem 2rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 1rem;
  justify-content: flex-end;
  flex-shrink: 0;
  background: var(--bg-secondary);
}

.btn {
  padding: 0.75rem 1.5rem;
  border-radius: 8px;
  font-weight: 500;
  font-size: 0.9rem;
  transition: all 0.2s;
  cursor: pointer;
  border: none;
}

.btn-secondary {
  background: white;
  border: 2px solid var(--border-color);
  color: var(--text-primary);
}

.btn-secondary:hover {
  border-color: var(--border-hover);
  background: var(--bg-hover);
}

.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.3s;
}

.dialog-fade-enter-active .dialog-container,
.dialog-fade-leave-active .dialog-container {
  transition: transform 0.3s, opacity 0.3s;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-from .dialog-container,
.dialog-fade-leave-to .dialog-container {
  transform: scale(0.9);
  opacity: 0;
}

@media (max-width: 760px) {
  .dialog-toolbar {
    padding: 0.75rem 1rem;
  }

  .instance-selector {
    min-width: 100%;
    max-width: none;
    flex-basis: 100%;
  }

  .toolbar-info {
    margin-left: 0;
  }
}
</style>
