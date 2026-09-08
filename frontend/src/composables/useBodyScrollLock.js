import { onScopeDispose, toValue, watch } from 'vue'

const activeLocks = new Set()
let savedStyles = null

function lockPage(token) {
  if (activeLocks.has(token)) return
  activeLocks.add(token)
  if (activeLocks.size > 1 || typeof document === 'undefined') return

  const root = document.documentElement
  const body = document.body
  const scrollbarWidth = Math.max(window.innerWidth - root.clientWidth, 0)
  const hasStableScrollbarGutter = window.CSS?.supports?.('scrollbar-gutter: stable') ?? false
  const bodyPaddingRight = Number.parseFloat(window.getComputedStyle(body).paddingRight) || 0

  savedStyles = {
    rootOverflow: root.style.overflow,
    rootOverscrollBehavior: root.style.overscrollBehavior,
    bodyOverflow: body.style.overflow,
    bodyOverscrollBehavior: body.style.overscrollBehavior,
    bodyPaddingRight: body.style.paddingRight
  }

  root.style.overflow = 'hidden'
  root.style.overscrollBehavior = 'none'
  body.style.overflow = 'hidden'
  body.style.overscrollBehavior = 'none'
  if (scrollbarWidth > 0 && !hasStableScrollbarGutter) {
    body.style.paddingRight = `${bodyPaddingRight + scrollbarWidth}px`
  }
}

function unlockPage(token) {
  if (!activeLocks.delete(token) || activeLocks.size > 0 || !savedStyles || typeof document === 'undefined') {
    return
  }

  const root = document.documentElement
  const body = document.body
  root.style.overflow = savedStyles.rootOverflow
  root.style.overscrollBehavior = savedStyles.rootOverscrollBehavior
  body.style.overflow = savedStyles.bodyOverflow
  body.style.overscrollBehavior = savedStyles.bodyOverscrollBehavior
  body.style.paddingRight = savedStyles.bodyPaddingRight
  savedStyles = null
}

export function useBodyScrollLock(locked) {
  const token = Symbol('body-scroll-lock')

  watch(
    () => Boolean(toValue(locked)),
    shouldLock => {
      if (shouldLock) lockPage(token)
      else unlockPage(token)
    },
    { immediate: true, flush: 'sync' }
  )

  onScopeDispose(() => unlockPage(token))
}
