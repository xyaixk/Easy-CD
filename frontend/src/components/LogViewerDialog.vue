<script setup>
import { ref, watch, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { getServiceLogsWsUrl } from '@/api/service'

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

const emit = defineEmits(['update:visible'])

// 日志缓冲（解码后的原始文本块，用于下载和行数统计；渲染由 xterm 负责）
let logChunks = []
let bufferedChars = 0
const MAX_BUFFER_CHARS = 5 * 1024 * 1024 // 缓冲上限 5M 字符，超出丢最旧块
const lineCount = ref(0)
const isLoading = ref(false)
const isFollowing = ref(false)
const autoScroll = ref(true)

// WebSocket 连接（复用终端通道的 logs 模式，服务端推二进制字节流）
let ws = null
let decoder = null

// xterm 终端（只读，仅用于日志渲染）
const termRef = ref(null)
let term = null
let fitAddon = null
let resizeObserver = null

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
  fitAddon.fit()

  // 容器尺寸变化自适应
  resizeObserver = new ResizeObserver(() => {
    if (!fitAddon) return
    try { fitAddon.fit() } catch (_) {}
  })
  resizeObserver.observe(termRef.value)
}

const teardownTerminal = () => {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (term) { term.dispose(); term = null }
  fitAddon = null
}

// 写入灰色状态提示（ANSI 暗色）
const writeHint = (text) => {
  if (!term) return
  term.writeln(`\x1b[90m${text}\x1b[0m`)
  if (autoScroll.value) term.scrollToBottom()
}

// 监听对话框显示状态
watch(() => props.visible, async (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    await nextTick()
    initTerminal()
    loadLogs()
  } else {
    document.body.style.overflow = ''
    closeLogs()
    teardownTerminal()
  }
})

// 加载日志（建立 WebSocket 连接）
const loadLogs = () => {
  logChunks = []
  bufferedChars = 0
  lineCount.value = 0
  if (term) term.reset()
  isLoading.value = true
  decoder = new TextDecoder() // 每次连接新建，stream 模式处理跨帧多字节字符
  
  try {
    ws = new WebSocket(getServiceLogsWsUrl(props.serviceId, 500, isFollowing.value))
    ws.binaryType = 'arraybuffer'
    
    ws.onmessage = (event) => {
      isLoading.value = false
      if (!term) return
      
      let text
      if (typeof event.data === 'string') {
        // 文本帧：服务端状态提示（已带 ANSI 样式）
        text = event.data
        term.write(text)
      } else {
        // 二进制帧：命令原始输出字节流
        term.write(new Uint8Array(event.data))
        text = decoder.decode(event.data, { stream: true })
      }
      if (autoScroll.value) term.scrollToBottom()
      
      // 缓冲文本用于下载/行数统计，超出上限丢最旧块
      logChunks.push(text)
      bufferedChars += text.length
      lineCount.value += (text.match(/\n/g) || []).length
      while (bufferedChars > MAX_BUFFER_CHARS && logChunks.length > 1) {
        bufferedChars -= logChunks.shift().length
      }
    }
    
    ws.onclose = () => {
      // 服务端读完/断开会主动关连接；follow 模式的断开提示由服务端文本帧给出
      isLoading.value = false
      ws = null
    }
    
    ws.onerror = (error) => {
      console.error('日志流连接错误:', error)
      isLoading.value = false
      writeHint('--- 日志流连接错误 ---')
    }
    
  } catch (error) {
    console.error('加载日志失败:', error)
    writeHint('加载日志失败: ' + error.message)
    isLoading.value = false
  }
}

// 滚动到底部
const scrollToBottom = () => {
  if (term) term.scrollToBottom()
}

// 切换实时推送
const toggleFollow = () => {
  if (isFollowing.value) {
    // 从实时切换到静态:关闭连接,保留当前日志
    closeLogs()
    isFollowing.value = false
  } else {
    // 从静态切换到实时:重新加载日志并开启实时推送
    isFollowing.value = true
    closeLogs()
    loadLogs()
  }
}

// 切换自动滚动
const toggleAutoScroll = () => {
  autoScroll.value = !autoScroll.value
  if (autoScroll.value) {
    scrollToBottom()
  }
}

// 清空日志
const clearLogs = () => {
  logChunks = []
  bufferedChars = 0
  lineCount.value = 0
  if (term) term.reset()
}

// 下载日志（去除 ANSI 颜色控制码，统一换行符）
const downloadLogs = () => {
  const content = logChunks.join('')
    .replace(/\x1b\[[0-9;]*m/g, '')
    .replace(/\r\n/g, '\n')
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  const serviceName = props.replica?.name || 'service'
  a.download = `${serviceName}-logs-${Date.now()}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// 关闭日志流
const closeLogs = () => {
  if (ws) {
    ws.onclose = null
    try { ws.close() } catch (_) {}
    ws = null
  }
}

const handleClose = () => {
  emit('update:visible', false)
}

onUnmounted(() => {
  document.body.style.overflow = ''
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
            <button 
              class="toolbar-btn"
              :class="{ active: isFollowing }"
              @click="toggleFollow"
              title="实时推送"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                <polyline points="17 6 23 6 23 12"/>
              </svg>
              {{ isFollowing ? '实时' : '静态' }}
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
              <div ref="termRef" class="terminal-container"></div>
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
</style>
