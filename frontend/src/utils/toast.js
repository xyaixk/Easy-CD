import { createApp } from 'vue'
import Toast from '@/components/Toast.vue'

const toasts = []

function createToast(options) {
  const container = document.createElement('div')
  container.style.cssText = `
    position: fixed;
    right: 20px;
    z-index: 10000;
    transition: all 0.3s ease;
  `
  document.body.appendChild(container)
  
  const app = createApp(Toast, {
    ...options,
    onDestroy: () => {
      app.unmount()
      document.body.removeChild(container)
      const index = toasts.indexOf(container)
      if (index > -1) {
        toasts.splice(index, 1)
      }
      updatePositions()
    }
  })
  
  app.mount(container)
  toasts.push(container)
  updatePositions()
}

function updatePositions() {
  let top = 20
  toasts.forEach(container => {
    container.style.top = top + 'px'
    top += container.offsetHeight + 12
  })
}

function showToast(message, type = 'info', duration = 3000) {
  const options = { message, type, duration }
  createToast(options)
}

export default {
  success(message, duration) {
    showToast(message, 'success', duration)
  },
  error(message, duration) {
    showToast(message, 'error', duration)
  },
  warning(message, duration) {
    showToast(message, 'warning', duration)
  },
  info(message, duration) {
    showToast(message, 'info', duration)
  }
}
