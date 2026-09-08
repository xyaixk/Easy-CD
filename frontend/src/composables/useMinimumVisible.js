import { onScopeDispose, readonly, ref, toValue, watch } from 'vue'

export function useMinimumVisible(source, minimumMs = 500) {
  const visible = ref(false)
  let visibleSince = 0
  let hideTimer = null

  const clearHideTimer = () => {
    if (!hideTimer) return
    clearTimeout(hideTimer)
    hideTimer = null
  }

  watch(
    () => Boolean(toValue(source)),
    shouldShow => {
      clearHideTimer()
      if (shouldShow) {
        if (!visible.value) {
          visible.value = true
          visibleSince = Date.now()
        }
        return
      }

      if (!visible.value) return
      const remainingMs = Math.max(0, minimumMs - (Date.now() - visibleSince))
      hideTimer = setTimeout(() => {
        visible.value = false
        hideTimer = null
      }, remainingMs)
    },
    { immediate: true, flush: 'sync' }
  )

  onScopeDispose(clearHideTimer)
  return readonly(visible)
}
