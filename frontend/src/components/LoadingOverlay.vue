<script setup>
defineProps({
  label: {
    type: String,
    default: '加载中…'
  }
})
</script>

<template>
  <div class="loading-overlay" role="status" aria-live="polite" :aria-label="label">
    <span class="loading-spinner" aria-hidden="true"></span>
    <span>{{ label }}</span>
  </div>
</template>

<style scoped>
.loading-overlay {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.65rem;
  color: var(--primary-color);
  font-size: 0.85rem;
  font-weight: 700;
  background: color-mix(in srgb, var(--bg-secondary) 82%, transparent);
  border-radius: inherit;
  backdrop-filter: blur(2px);
  animation: loading-overlay-in 0.15s ease-out;
}

.loading-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid color-mix(in srgb, var(--primary-color) 24%, transparent);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: loading-overlay-spin 0.75s linear infinite;
}

@keyframes loading-overlay-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes loading-overlay-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .loading-overlay,
  .loading-spinner {
    animation: none;
  }
}
</style>
