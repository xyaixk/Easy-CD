<script setup>
import { computed } from 'vue'

/**
 * 水平进度条 + 百分比数字
 * 三档色语义：<70% success / 70-90% warning / >90% danger
 */
const props = defineProps({
  label: {
    type: String,
    default: ''
  },
  value: {
    type: [Number, String, null],
    default: null
  },
  // 附加副文本（如 "1.2G / 4G"）
  hint: {
    type: String,
    default: ''
  },
  height: {
    type: Number,
    default: 6
  }
})

const hasValue = computed(() => props.value !== null && props.value !== undefined && !Number.isNaN(Number(props.value)))
const numValue = computed(() => (hasValue.value ? Math.max(0, Math.min(100, Number(props.value))) : 0))
const displayValue = computed(() => (hasValue.value ? `${numValue.value.toFixed(0)}%` : '-'))

const levelClass = computed(() => {
  if (!hasValue.value) return 'muted'
  const v = numValue.value
  if (v >= 90) return 'danger'
  if (v >= 70) return 'warning'
  return 'ok'
})
</script>

<template>
  <div class="metric-bar" :class="levelClass">
    <div class="row">
      <span class="label">{{ label }}</span>
      <span class="value">{{ displayValue }}</span>
    </div>
    <div class="track" :style="{ height: `${height}px` }">
      <div class="fill" :style="{ width: `${numValue}%` }"></div>
    </div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.metric-bar {
  --fill-color: var(--success-color);
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  width: 100%;
}

.metric-bar.warning {
  --fill-color: var(--warning-color);
}

.metric-bar.danger {
  --fill-color: var(--danger-color);
}

.metric-bar.muted {
  --fill-color: var(--text-tertiary);
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 0.7rem;
  color: var(--text-secondary);
}

.label {
  font-weight: 600;
  letter-spacing: 0.02em;
}

.value {
  font-family: 'Courier New', monospace;
  font-weight: 700;
  color: var(--fill-color);
  font-size: 0.75rem;
}

.track {
  width: 100%;
  background: var(--bg-hover);
  border-radius: 4px;
  overflow: hidden;
}

.fill {
  height: 100%;
  background: var(--fill-color);
  transition: width 0.4s ease, background 0.3s;
  border-radius: 4px;
}

.hint {
  font-size: 0.65rem;
  color: var(--text-tertiary);
  font-family: 'Courier New', monospace;
}
</style>
