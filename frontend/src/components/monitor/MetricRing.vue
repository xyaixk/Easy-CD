<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: {
    type: String,
    required: true
  },
  value: {
    type: [Number, String, null],
    default: null
  },
  hint: {
    type: String,
    default: ''
  }
})

const hasValue = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') return false
  return Number.isFinite(Number(props.value))
})

const normalizedValue = computed(() => {
  if (!hasValue.value) return 0
  return Math.max(0, Math.min(100, Number(props.value)))
})

const displayValue = computed(() => (
  hasValue.value ? `${Math.round(normalizedValue.value)}%` : '—'
))

const levelClass = computed(() => {
  if (!hasValue.value) return 'muted'
  if (normalizedValue.value >= 90) return 'danger'
  if (normalizedValue.value >= 70) return 'warning'
  return 'ok'
})

const accessibleText = computed(() => {
  const valueText = hasValue.value ? `使用率 ${displayValue.value}` : '暂无数据'
  return props.hint ? `${props.label} ${valueText}，${props.hint}` : `${props.label} ${valueText}`
})
</script>

<template>
  <div
    class="metric-ring"
    :class="levelClass"
    :title="accessibleText"
    role="progressbar"
    aria-valuemin="0"
    aria-valuemax="100"
    :aria-valuenow="hasValue ? normalizedValue : undefined"
    :aria-valuetext="accessibleText"
    :aria-label="label"
  >
    <div class="ring-visual" aria-hidden="true">
      <svg viewBox="0 0 44 44">
        <circle class="ring-track" cx="22" cy="22" r="18" pathLength="100" />
        <circle
          class="ring-value"
          cx="22"
          cy="22"
          r="18"
          pathLength="100"
          :stroke-dasharray="`${normalizedValue} 100`"
        />
      </svg>
      <span class="ring-number">{{ displayValue }}</span>
    </div>
    <span class="ring-label">{{ label }}</span>
  </div>
</template>

<style scoped>
.metric-ring {
  --ring-color: var(--success-color);
  display: grid;
  justify-items: center;
  gap: 0.25rem;
  min-width: 0;
}

.metric-ring.warning {
  --ring-color: var(--warning-color);
}

.metric-ring.danger {
  --ring-color: var(--danger-color);
}

.metric-ring.muted {
  --ring-color: var(--text-tertiary);
}

.ring-visual {
  position: relative;
  width: 44px;
  height: 44px;
}

.ring-visual svg {
  display: block;
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.ring-track,
.ring-value {
  fill: none;
  stroke-width: 4;
}

.ring-track {
  stroke: var(--border-color);
}

.ring-value {
  stroke: var(--ring-color);
  stroke-linecap: round;
  transition: stroke-dasharray 0.35s ease, stroke 0.2s ease;
}

.ring-number {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--ring-color);
  font-family: 'Courier New', monospace;
  font-size: 0.66rem;
  font-weight: 700;
  line-height: 1;
}

.ring-label {
  color: var(--text-secondary);
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 0.04em;
}

@media (prefers-reduced-motion: reduce) {
  .ring-value {
    transition: none;
  }
}
</style>
