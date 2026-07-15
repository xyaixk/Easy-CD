<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 极简 SVG sparkline
 * - points: 数值数组（0-100 或任意数值范围，组件内部自动归一化）
 * - width/height: 视觉尺寸
 * - color: 描边色（默认跟随 currentColor / 环境主题色）
 * - fill: 是否填充下方渐变（默认 true）
 * - max: 归一化基准最大值（%指标固定 100，避免小数据放大噪声）
 *
 * 交互增强（默认关闭，用于详情大图）：
 * - interactive: 是否启用 hover 交互（十字辅助线 + 数据点 + tooltip）
 * - timestamps: 与 points 等长的时间戳数组（秒或毫秒），提供后 tooltip 显示时间
 * - unit: tooltip 数值单位（默认 '%'）
 * - label: tooltip 标题（如 'CPU'）
 * - valueFormat: 可选的自定义值格式化函数 (v) => string
 * - hintText: tooltip 中主数值下方的副信息（string 或 (index)=>string，如内存实际占用 "256M / 1G"）
 */
const props = defineProps({
  points: { type: Array, default: () => [] },
  width: { type: Number, default: 80 },
  height: { type: Number, default: 20 },
  color: { type: String, default: '' },
  fill: { type: Boolean, default: true },
  max: { type: Number, default: 100 },
  interactive: { type: Boolean, default: false },
  timestamps: { type: Array, default: () => [] },
  unit: { type: String, default: '%' },
  label: { type: String, default: '' },
  valueFormat: { type: Function, default: null },
  hintText: { type: [String, Function], default: '' }
})

const hasData = computed(() => Array.isArray(props.points) && props.points.length >= 2)

/* ---------- 真实宽度感知（避免 svg 非等比拉伸导致圆点变椭圆） ---------- */
const wrapperRef = ref(null)
const actualWidth = ref(props.width)
let resizeObserver = null

// 交互模式下，wrapper 会被外部 flex 拉伸，svg viewBox 必须跟随真实宽度
// 静态模式保持 props.width（外部不会用 CSS 拉伸）
const effectiveWidth = computed(() => (props.interactive ? actualWidth.value : props.width))

onMounted(() => {
  if (!props.interactive || !wrapperRef.value) return
  // 初始化真实宽度
  const initW = wrapperRef.value.getBoundingClientRect().width
  if (initW > 0) actualWidth.value = initW
  // 监听尺寸变化
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(entries => {
      for (const entry of entries) {
        const w = entry.contentRect.width
        if (w > 0 && Math.abs(w - actualWidth.value) > 0.5) {
          actualWidth.value = w
        }
      }
    })
    resizeObserver.observe(wrapperRef.value)
  }
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})

// 归一化：每个点算出 (x, y) 坐标 + 原始值
const normalized = computed(() => {
  if (!hasData.value) return []
  const n = props.points.length
  const w = effectiveWidth.value
  const stepX = w / (n - 1)
  return props.points.map((v, i) => {
    const num = Number(v) || 0
    const clamped = Math.max(0, Math.min(props.max, num))
    return {
      raw: num,
      x: i * stepX,
      y: props.height - (clamped / props.max) * props.height
    }
  })
})

const path = computed(() => {
  if (!hasData.value) return ''
  return normalized.value
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)},${p.y.toFixed(2)}`)
    .join(' ')
})

const fillPath = computed(() => {
  if (!hasData.value || !props.fill) return ''
  return `${path.value} L${effectiveWidth.value},${props.height} L0,${props.height} Z`
})

const gradientId = computed(() => `spark-grad-${Math.random().toString(36).slice(2, 8)}`)
const strokeColor = computed(() => props.color || 'var(--primary-color)')

/* ---------- Hover 交互（interactive=true 时启用） ---------- */
const hoverIndex = ref(-1)

const hoverPoint = computed(() => {
  if (hoverIndex.value < 0 || !hasData.value) return null
  return normalized.value[hoverIndex.value]
})

const handleMouseMove = (e) => {
  if (!props.interactive || !hasData.value) return
  const rect = e.currentTarget.getBoundingClientRect()
  const w = effectiveWidth.value
  const localX = ((e.clientX - rect.left) / rect.width) * w
  const n = props.points.length
  const stepX = w / (n - 1)
  const idx = Math.max(0, Math.min(n - 1, Math.round(localX / stepX)))
  hoverIndex.value = idx
}

const handleMouseLeave = () => {
  hoverIndex.value = -1
}

// tooltip 内容
const formatTime = (ts) => {
  if (ts == null) return ''
  // 数值：小于 10^12 视为秒级时间戳
  const ms = typeof ts === 'number' ? (ts < 1e12 ? ts * 1000 : ts) : new Date(ts).getTime()
  if (!ms || Number.isNaN(ms)) return ''
  const d = new Date(ms)
  const pad = (v) => String(v).padStart(2, '0')
  const hh = pad(d.getHours())
  const mm = pad(d.getMinutes())
  const ss = pad(d.getSeconds())
  // 若跨天了显示日期
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  return sameDay ? `${hh}:${mm}:${ss}` : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hh}:${mm}`
}

const tooltipTime = computed(() => {
  if (hoverIndex.value < 0) return ''
  return formatTime(props.timestamps[hoverIndex.value])
})

const tooltipValue = computed(() => {
  if (!hoverPoint.value) return ''
  const v = hoverPoint.value.raw
  if (typeof props.valueFormat === 'function') return props.valueFormat(v)
  const n = Number(v)
  return `${n.toFixed(1)}${props.unit}`
})

const tooltipHint = computed(() => {
  if (hoverIndex.value < 0) return ''
  if (typeof props.hintText === 'function') {
    try { return props.hintText(hoverIndex.value) || '' } catch (e) { return '' }
  }
  return props.hintText || ''
})

// tooltip 定位（跟随 hover 点，右侧越界时改为向左展开）
const tooltipStyle = computed(() => {
  if (!hoverPoint.value) return { display: 'none' }
  const pct = (hoverPoint.value.x / effectiveWidth.value) * 100
  const leftPct = Math.max(0, Math.min(100, pct))
  const transform = leftPct > 65 ? 'translate(-100%, -100%)' : 'translate(0, -100%)'
  const offsetX = leftPct > 65 ? -8 : 8
  return {
    left: `calc(${leftPct}% + ${offsetX}px)`,
    top: '0',
    transform
  }
})
</script>

<template>
  <!-- 交互模式：wrapper + tooltip 层（viewBox 跟随真实宽度，避免非等比拉伸变形） -->
  <div
    v-if="interactive"
    ref="wrapperRef"
    class="sparkline-wrap"
    :style="{ width: width + 'px', height: height + 'px' }"
  >
    <svg
      class="sparkline"
      :width="effectiveWidth"
      :height="height"
      :viewBox="`0 0 ${effectiveWidth} ${height}`"
      preserveAspectRatio="none"
      @mousemove="handleMouseMove"
      @mouseleave="handleMouseLeave"
    >
      <template v-if="hasData">
        <defs v-if="fill">
          <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" :stop-color="strokeColor" stop-opacity="0.35" />
            <stop offset="100%" :stop-color="strokeColor" stop-opacity="0" />
          </linearGradient>
        </defs>
        <path v-if="fill" :d="fillPath" :fill="`url(#${gradientId})`" stroke="none" />
        <path
          :d="path"
          fill="none"
          :stroke="strokeColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
        <!-- Hover 辅助线 + 数据点 -->
        <template v-if="hoverPoint">
          <line
            :x1="hoverPoint.x"
            y1="0"
            :x2="hoverPoint.x"
            :y2="height"
            :stroke="strokeColor"
            stroke-width="1"
            stroke-dasharray="3,3"
            opacity="0.55"
          />
          <circle
            :cx="hoverPoint.x"
            :cy="hoverPoint.y"
            r="3.5"
            :fill="strokeColor"
            stroke="#fff"
            stroke-width="1.5"
          />
        </template>
        <!-- 透明命中区（保证 mousemove 稳定捕获） -->
        <rect x="0" y="0" :width="effectiveWidth" :height="height" fill="transparent" />
      </template>
      <line
        v-else
        x1="0"
        :y1="height / 2"
        :x2="effectiveWidth"
        :y2="height / 2"
        stroke="var(--border-color)"
        stroke-width="1"
        stroke-dasharray="2,2"
      />
    </svg>
   <div v-if="hoverPoint" class="spark-tooltip" :style="tooltipStyle">
      <div v-if="label" class="tt-label">{{ label }}</div>
      <div class="tt-value" :style="{ color: strokeColor }">{{ tooltipValue }}</div>
      <div v-if="tooltipHint" class="tt-sub">{{ tooltipHint }}</div>
      <div v-if="tooltipTime" class="tt-time">{{ tooltipTime }}</div>
    </div>
  </div>

  <!-- 静态模式：原样输出 svg，保持父级 flex 布局兼容 -->
  <svg
    v-else
    class="sparkline"
    :width="width"
    :height="height"
    :viewBox="`0 0 ${width} ${height}`"
    preserveAspectRatio="none"
  >
    <template v-if="hasData">
      <defs v-if="fill">
        <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="strokeColor" stop-opacity="0.35" />
          <stop offset="100%" :stop-color="strokeColor" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path v-if="fill" :d="fillPath" :fill="`url(#${gradientId})`" stroke="none" />
      <path
        :d="path"
        fill="none"
        :stroke="strokeColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </template>
    <line
      v-else
      x1="0"
      :y1="height / 2"
      :x2="width"
      :y2="height / 2"
      stroke="var(--border-color)"
      stroke-width="1"
      stroke-dasharray="2,2"
    />
  </svg>
</template>

<style scoped>
.sparkline {
  display: block;
  overflow: visible;
}

.sparkline-wrap {
  position: relative;
  display: block;
}

/* 交互模式下，svg 完全跟随 wrapper 尺寸（便于外部 flex 拉伸） */
.sparkline-wrap .sparkline {
  width: 100%;
  height: 100%;
}

.spark-tooltip {
  position: absolute;
  pointer-events: none;
  background: rgba(28, 34, 46, 0.94);
  color: #f0f2f5;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 0.72rem;
  line-height: 1.4;
  white-space: nowrap;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
  z-index: 20;
  margin-top: -6px;
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}

.tt-label {
  font-size: 0.6rem;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.55);
  margin-bottom: 3px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.tt-value {
  font-family: 'Courier New', monospace;
  font-weight: 700;
  font-size: 0.95rem;
  line-height: 1;
}

.tt-sub {
  font-family: 'Courier New', monospace;
  font-size: 0.72rem;
  color: rgba(255, 255, 255, 0.78);
  margin-top: 3px;
  letter-spacing: 0.02em;
}

.tt-time {
  font-family: 'Courier New', monospace;
  font-size: 0.68rem;
  color: rgba(255, 255, 255, 0.6);
  margin-top: 4px;
}
</style>
