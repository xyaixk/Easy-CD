<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  buckets: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['brush'])

// 堆叠顺序（自底向上）与配色：DEBUG灰 / INFO绿 / WARN黄 / ERROR红 / other浅灰
const STACK = [
  { key: 'debug', color: '#9ca3af' },
  { key: 'info',  color: '#10b981' },
  { key: 'warn',  color: '#f59e0b' },
  { key: 'error', color: '#ef4444' },
  { key: 'other', color: '#d1d5db' }
]
const CHART_H = 72
const AXIS_H = 18

const wrapRef = ref(null)
const width = ref(600)
let resizeObserver = null
onMounted(() => {
  resizeObserver = new ResizeObserver((entries) => {
    const w = entries[0]?.contentRect?.width
    if (w) width.value = w
  })
  if (wrapRef.value) resizeObserver.observe(wrapRef.value)
})
onUnmounted(() => resizeObserver?.disconnect())

const maxTotal = computed(() => Math.max(1, ...props.buckets.map(b =>
  (b.error || 0) + (b.warn || 0) + (b.info || 0) + (b.debug || 0) + (b.other || 0))))

// 桶几何：等宽柱，间隙 1px
const bars = computed(() => {
  const n = props.buckets.length
  if (!n) return []
  const slot = width.value / n
  const barW = Math.max(1, slot - 1)
  return props.buckets.map((b, i) => {
    let y = CHART_H
    const segs = []
    for (const s of STACK) {
      const v = b[s.key] || 0
      if (v <= 0) continue
      const h = Math.max(1, (v / maxTotal.value) * (CHART_H - 4))
      y -= h
      segs.push({ y, h, color: s.color })
    }
    return { x: i * slot, w: barW, segs, bucket: b }
  })
})

// 时间轴刻度（约 6 个）
const ticks = computed(() => {
  const n = props.buckets.length
  if (n < 2) return []
  const step = Math.max(1, Math.floor(n / 6))
  const slot = width.value / n
  const out = []
  for (let i = 0; i < n; i += step) {
    out.push({ x: i * slot + slot / 2, label: fmtTick(props.buckets[i].tsMs) })
  }
  return out
})
const fmtTick = (ms) => {
  const d = new Date(ms)
  const pad = (v) => String(v).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// ============ 拖拽框选 ============
const brushing = ref(false)
const brushStart = ref(0)
const brushEnd = ref(0)
const svgRef = ref(null)

const clientX2Local = (e) => {
  const rect = svgRef.value.getBoundingClientRect()
  return Math.min(Math.max(0, e.clientX - rect.left), rect.width)
}
const onMouseDown = (e) => {
  if (!props.buckets.length) return
  brushing.value = true
  brushStart.value = clientX2Local(e)
  brushEnd.value = brushStart.value
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}
const onMouseMove = (e) => {
  if (brushing.value) brushEnd.value = clientX2Local(e)
}
const onMouseUp = () => {
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseup', onMouseUp)
  if (!brushing.value) return
  brushing.value = false
  const x1 = Math.min(brushStart.value, brushEnd.value)
  const x2 = Math.max(brushStart.value, brushEnd.value)
  if (x2 - x1 < 6) return // 视为点击，忽略
  const n = props.buckets.length
  if (n < 2) return
  const slot = width.value / n
  const bucketMs = props.buckets[1].tsMs - props.buckets[0].tsMs
  const i1 = Math.min(n - 1, Math.max(0, Math.floor(x1 / slot)))
  const i2 = Math.min(n - 1, Math.max(0, Math.floor(x2 / slot)))
  emit('brush', {
    fromMs: props.buckets[i1].tsMs,
    toMs: props.buckets[i2].tsMs + bucketMs
  })
}

const brushRect = computed(() => {
  if (!brushing.value) return null
  const x = Math.min(brushStart.value, brushEnd.value)
  const w = Math.abs(brushEnd.value - brushStart.value)
  return { x, w }
})

// hover 提示
const hoverIndex = ref(-1)
const onSvgMove = (e) => {
  if (!props.buckets.length) { hoverIndex.value = -1; return }
  const x = clientX2Local(e)
  const slot = width.value / props.buckets.length
  hoverIndex.value = Math.min(props.buckets.length - 1, Math.floor(x / slot))
}
const hoverBucket = computed(() => hoverIndex.value >= 0 ? props.buckets[hoverIndex.value] : null)
const fmtHoverTime = (ms) => {
  const d = new Date(ms)
  const pad = (v) => String(v).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<template>
  <div class="histogram" ref="wrapRef">
    <svg ref="svgRef" :width="width" :height="CHART_H + AXIS_H"
      class="histo-svg" :class="{ empty: !buckets.length }"
      @mousedown="onMouseDown" @mousemove="onSvgMove" @mouseleave="hoverIndex = -1">
      <!-- 柱体 -->
      <g v-for="(bar, i) in bars" :key="bar.bucket.tsMs">
        <rect v-for="(seg, j) in bar.segs" :key="j"
          :x="bar.x" :y="seg.y" :width="bar.w" :height="seg.h" :fill="seg.color" rx="1"/>
        <!-- hover 高亮层 -->
        <rect v-if="i === hoverIndex" :x="bar.x" y="0" :width="bar.w" :height="CHART_H"
          fill="rgba(102,126,234,0.12)"/>
      </g>
      <!-- 时间刻度 -->
      <g class="ticks">
        <text v-for="t in ticks" :key="t.x" :x="t.x" :y="CHART_H + 13" text-anchor="middle">{{ t.label }}</text>
      </g>
      <!-- 框选矩形 -->
      <rect v-if="brushRect" :x="brushRect.x" y="0" :width="brushRect.w" :height="CHART_H"
        fill="rgba(102,126,234,0.18)" stroke="var(--primary-color)" stroke-width="1" stroke-dasharray="3,2"/>
    </svg>
    <!-- hover 提示 -->
    <div v-if="hoverBucket && !brushing" class="histo-tip">
      <span class="tip-time">{{ fmtHoverTime(hoverBucket.tsMs) }}</span>
      <span v-if="hoverBucket.error" class="tip-item t-error">E {{ hoverBucket.error }}</span>
      <span v-if="hoverBucket.warn" class="tip-item t-warn">W {{ hoverBucket.warn }}</span>
      <span v-if="hoverBucket.info" class="tip-item t-info">I {{ hoverBucket.info }}</span>
      <span v-if="hoverBucket.debug" class="tip-item t-debug">D {{ hoverBucket.debug }}</span>
      <span v-if="hoverBucket.other" class="tip-item t-other">O {{ hoverBucket.other }}</span>
    </div>
    <div v-if="!buckets.length && !loading" class="histo-empty">无统计数据</div>
  </div>
</template>

<style scoped>
.histogram {
  position: relative;
  padding: .5rem 1.5rem .25rem;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
  user-select: none;
}
.histo-svg { display: block; cursor: crosshair; }
.histo-svg.empty { cursor: default; }
.ticks text { font-size: 10px; fill: var(--text-tertiary); }

.histo-tip {
  position: absolute; top: .35rem; right: 1.5rem;
  display: inline-flex; align-items: center; gap: .6rem;
  padding: .25rem .6rem; border-radius: 6px;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  font-size: .72rem; pointer-events: none; z-index: 2;
}
.tip-time { color: var(--text-secondary); font-family: 'Consolas', monospace; }
.tip-item { font-weight: 700; font-family: 'Consolas', monospace; }
.t-error { color: #ef4444; }
.t-warn  { color: #f59e0b; }
.t-info  { color: #10b981; }
.t-debug { color: #6b7280; }
.t-other { color: #9ca3af; }

.histo-empty {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  color: var(--text-tertiary); font-size: .8rem; pointer-events: none;
}
</style>
