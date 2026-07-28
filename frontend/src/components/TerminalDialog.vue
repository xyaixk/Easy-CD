<script setup>
import { ref, watch, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { getToken } from '@/utils/auth'

const props = defineProps({
  visible: { type: Boolean, default: false },
  serviceId: { type: [Number, String], default: null },
  replica: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:visible'])

// 连接状态：connecting / connected / closed
const connStatus = ref('connecting')

const termRef = ref(null)
let term = null
let fitAddon = null
let ws = null
let resizeObserver = null

const statusText = {
  connecting: '连接中',
  connected: '已连接',
  closed: '已断开'
}

const buildWsUrl = () => {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const params = new URLSearchParams({
    serviceId: String(props.serviceId),
    replicaId: String(props.replica.id || '')
  })
  const token = getToken()
  if (token) params.set('token', token)
  return `${proto}//${location.host}/api/terminal?${params.toString()}`
}

const sendResize = () => {
  if (ws && ws.readyState === WebSocket.OPEN && term) {
    ws.send('1' + JSON.stringify({ cols: term.cols, rows: term.rows }))
  }
}

const initTerminal = () => {
  term = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    fontFamily: "'Courier New', Consolas, monospace",
    scrollback: 5000,
    theme: {
      background: '#0f172a',
      foreground: '#e2e8f0',
      cursor: '#e2e8f0',
      selectionBackground: 'rgba(148, 163, 184, 0.3)'
    }
  })
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(termRef.value)
  fitAddon.fit()
  term.focus()

  // 键盘输入 -> 后端（'0' 前缀）
  term.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send('0' + data)
    }
  })

  // 容器尺寸变化自适应并同步 PTY 大小
  resizeObserver = new ResizeObserver(() => {
    if (!fitAddon) return
    try { fitAddon.fit() } catch (_) { return }
    sendResize()
  })
  resizeObserver.observe(termRef.value)
}

const connect = () => {
  connStatus.value = 'connecting'
  term.writeln(`\x1b[90m正在连接 ${props.replica.name || ''} ...\x1b[0m`)

  ws = new WebSocket(buildWsUrl())
  ws.binaryType = 'arraybuffer'

  ws.onopen = () => {
    connStatus.value = 'connected'
    sendResize()
  }
  ws.onmessage = (event) => {
    if (!term) return
    if (typeof event.data === 'string') {
      term.write(event.data)
    } else {
      term.write(new Uint8Array(event.data))
    }
  }
  ws.onclose = () => {
    connStatus.value = 'closed'
    if (term) term.writeln('\r\n\x1b[90m[连接已断开，关闭窗口后可重新进入]\x1b[0m')
  }
  ws.onerror = () => {
    connStatus.value = 'closed'
  }
}

const teardown = () => {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (ws) {
    ws.onclose = null
    try { ws.close() } catch (_) {}
    ws = null
  }
  if (term) { term.dispose(); term = null }
  fitAddon = null
}

watch(() => props.visible, async (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    await nextTick()
    initTerminal()
    connect()
  } else {
    document.body.style.overflow = ''
    teardown()
  }
})

const handleClose = () => {
  emit('update:visible', false)
}

onUnmounted(() => {
  document.body.style.overflow = ''
  teardown()
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
                  <polyline points="4 17 10 11 4 5"/>
                  <line x1="12" y1="19" x2="20" y2="19"/>
                </svg>
              </div>
              <h3>{{ replica.name || '容器终端' }}</h3>
              <span class="conn-status" :class="connStatus">{{ statusText[connStatus] }}</span>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <div class="terminal-body">
            <div ref="termRef" class="terminal-container"></div>
          </div>

          <div class="dialog-footer">
            <span class="footer-hint">连接节点 {{ replica.nodeIp || replica.node || '-' }} · 容器 {{ replica.containerId || '-' }}</span>
            <button class="btn btn-secondary" @click="handleClose">关闭</button>
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
  z-index: 10001;
  padding: 1rem;
}

.dialog-container {
  background: var(--bg-secondary);
  border-radius: 16px;
  width: 100%;
  max-width: 960px;
  height: 640px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
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
  font-size: 1.15rem;
  font-weight: 600;
  color: white;
  font-family: 'Courier New', monospace;
}

.conn-status {
  padding: 0.25rem 0.625rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
  background: rgba(255, 255, 255, 0.2);
  color: white;
}

.conn-status.connected {
  background: rgba(34, 197, 94, 0.35);
}

.conn-status.closed {
  background: rgba(239, 68, 68, 0.4);
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

/* 终端区域：深色底、内边距和 xterm 背景一致 */
.terminal-body {
  flex: 1;
  min-height: 0;
  background: #0f172a;
  padding: 0.75rem;
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
  background: #0f172a;
}

.terminal-container :deep(.xterm-viewport)::-webkit-scrollbar-thumb {
  background: #334155;
  border-radius: 4px;
}

.dialog-footer {
  padding: 1rem 2rem;
  border-top: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
  background: var(--bg-secondary);
}

.footer-hint {
  font-size: 0.78rem;
  color: var(--text-tertiary);
  font-family: 'Courier New', monospace;
}

.btn {
  padding: 0.625rem 1.5rem;
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
