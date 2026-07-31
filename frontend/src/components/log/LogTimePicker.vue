<script setup>
import { ref, computed } from 'vue'

// 直接读写父组件 filters 的 timeRange/from/to
const props = defineProps({
  filters: { type: Object, required: true },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['change'])

const QUICK_RANGES = [
  { value: '5m',  label: '最近 5 分钟' },
  { value: '15m', label: '最近 15 分钟' },
  { value: '30m', label: '最近 30 分钟' },
  { value: '1h',  label: '最近 1 小时' },
  { value: '3h',  label: '最近 3 小时' },
  { value: '6h',  label: '最近 6 小时' },
  { value: '12h', label: '最近 12 小时' },
  { value: '24h', label: '最近 24 小时' },
  { value: '2d',  label: '最近 2 天' },
  { value: '7d',  label: '最近 7 天' }
]
const WEEK_DAYS = ['一', '二', '三', '四', '五', '六', '日']

const open = ref(false)

// ============ 顶部按钮文案 ============
const label = computed(() => {
  if (props.filters.timeRange === 'custom') {
    const f = props.filters.from
    const t = props.filters.to
    if (f && t) {
      const short = (s) => s.replace('T', ' ').slice(5) // MM-DD HH:mm:ss
      return `${short(f)} ~ ${short(t)}`
    }
    return '自定义范围'
  }
  return QUICK_RANGES.find(r => r.value === props.filters.timeRange)?.label || '时间范围'
})

// ============ 日历状态 ============
const pad = (n) => String(n).padStart(2, '0')
const dateKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const todayKey = dateKey(new Date())

const viewYear = ref(new Date().getFullYear())
const viewMonth = ref(new Date().getMonth()) // 0-based
const startDate = ref('')   // YYYY-MM-DD
const endDate = ref('')
const startTime = ref('00:00:00')
const endTime = ref('23:59:59')

// 6 行 7 列日历格（周一开头）
const calendarCells = computed(() => {
  const first = new Date(viewYear.value, viewMonth.value, 1)
  const offset = (first.getDay() + 6) % 7 // 周一=0
  const cells = []
  for (let i = 0; i < 42; i++) {
    const d = new Date(viewYear.value, viewMonth.value, 1 - offset + i)
    cells.push({
      key: dateKey(d),
      day: d.getDate(),
      inMonth: d.getMonth() === viewMonth.value,
      isToday: dateKey(d) === todayKey,
      isFuture: dateKey(d) > todayKey
    })
  }
  return cells
})

const cellState = (cell) => {
  const k = cell.key
  const s = startDate.value
  const e = endDate.value
  if (s && k === s) return e ? 'edge' : 'edge-single'
  if (e && k === e) return 'edge'
  if (s && e && k > s && k < e) return 'in-range'
  return ''
}

const prevMonth = () => {
  if (viewMonth.value === 0) { viewMonth.value = 11; viewYear.value-- }
  else viewMonth.value--
}
const nextMonth = () => {
  if (viewMonth.value === 11) { viewMonth.value = 0; viewYear.value++ }
  else viewMonth.value++
}

// 第一次点选起点，第二次点选终点（早于起点则交换）
const pickDate = (cell) => {
  if (cell.isFuture) return
  const k = cell.key
  if (!startDate.value || (startDate.value && endDate.value)) {
    startDate.value = k
    endDate.value = ''
    return
  }
  if (k < startDate.value) {
    endDate.value = startDate.value
    startDate.value = k
  } else {
    endDate.value = k
  }
}

// ============ 时间输入校验（HH:mm 或 HH:mm:ss） ============
const normalizeTime = (v, fallback) => {
  const m = String(v || '').trim().match(/^(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?$/)
  if (!m) return null
  const h = Math.min(23, +m[1]), mi = Math.min(59, +m[2]), s = Math.min(59, m[3] === undefined ? fallback : +m[3])
  return `${pad(h)}:${pad(mi)}:${pad(s)}`
}
const canApply = computed(() =>
  !!startDate.value && !!endDate.value &&
  normalizeTime(startTime.value, 0) !== null && normalizeTime(endTime.value, 59) !== null
)

// ============ 打开/关闭/应用 ============
const toggle = () => {
  if (props.disabled) return
  open.value = !open.value
  if (!open.value) return
  // 回填当前 custom 范围；否则清空选区、日历跳到当月
  if (props.filters.timeRange === 'custom' && props.filters.from && props.filters.to) {
    startDate.value = props.filters.from.slice(0, 10)
    endDate.value = props.filters.to.slice(0, 10)
    startTime.value = props.filters.from.slice(11) || '00:00:00'
    endTime.value = props.filters.to.slice(11) || '23:59:59'
    viewYear.value = +endDate.value.slice(0, 4)
    viewMonth.value = +endDate.value.slice(5, 7) - 1
  } else {
    startDate.value = ''; endDate.value = ''
    startTime.value = '00:00:00'; endTime.value = '23:59:59'
    viewYear.value = new Date().getFullYear()
    viewMonth.value = new Date().getMonth()
  }
}
const close = () => { open.value = false }

const pickQuick = (v) => {
  props.filters.timeRange = v
  open.value = false
  emit('change')
}
const applyCustom = () => {
  if (!canApply.value) return
  const st = normalizeTime(startTime.value, 0)
  const et = normalizeTime(endTime.value, 59)
  let from = `${startDate.value}T${st}`
  let to = `${endDate.value}T${et}`
  if (from > to) [from, to] = [to, from]
  props.filters.timeRange = 'custom'
  props.filters.from = from
  props.filters.to = to
  open.value = false
  emit('change')
}
</script>

<template>
  <div class="time-picker" v-click-away="close">
    <button class="tp-btn" :class="{ open, disabled }" :disabled="disabled" @click="toggle">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
      </svg>
      <span class="tp-label">{{ label }}</span>
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
    </button>

    <div v-if="open" class="tp-dropdown">
      <!-- 左列：快捷区间 -->
      <div class="tp-quick">
        <button v-for="r in QUICK_RANGES" :key="r.value" class="tp-item"
          :class="{ active: filters.timeRange === r.value }" @click="pickQuick(r.value)">
          {{ r.label }}
        </button>
      </div>

      <!-- 右列：日历范围选择 -->
      <div class="tp-cal">
        <div class="cal-nav">
          <button class="cal-nav-btn" @click="prevMonth">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="15 18 9 12 15 6"/></svg>
          </button>
          <span class="cal-title">{{ viewYear }} 年 {{ viewMonth + 1 }} 月</span>
          <button class="cal-nav-btn" @click="nextMonth">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
          </button>
        </div>

        <div class="cal-grid cal-week">
          <span v-for="w in WEEK_DAYS" :key="w" class="cal-wd">{{ w }}</span>
        </div>
        <div class="cal-grid">
          <button v-for="cell in calendarCells" :key="cell.key" class="cal-day"
            :class="[cellState(cell), { dim: !cell.inMonth, today: cell.isToday, future: cell.isFuture }]"
            :disabled="cell.isFuture" @click="pickDate(cell)">
            {{ cell.day }}
          </button>
        </div>

        <div class="cal-times">
          <div class="cal-time-row">
            <span class="ct-label">从</span>
            <span class="ct-date">{{ startDate || '点击日历选择' }}</span>
            <input v-model="startTime" class="ct-input" placeholder="00:00:00" spellcheck="false"/>
          </div>
          <div class="cal-time-row">
            <span class="ct-label">至</span>
            <span class="ct-date">{{ endDate || (startDate ? '再点一天作为结束' : '—') }}</span>
            <input v-model="endTime" class="ct-input" placeholder="23:59:59" spellcheck="false"/>
          </div>
        </div>

        <button class="tp-apply" :disabled="!canApply" @click="applyCustom">应用范围</button>
      </div>
    </div>
  </div>
</template>

<script>
// 轻量 click-away 指令（无依赖）
export default {
  directives: {
    clickAway: {
      mounted(el, binding) {
        el.__clickAway = (e) => { if (!el.contains(e.target)) binding.value() }
        document.addEventListener('mousedown', el.__clickAway)
      },
      unmounted(el) {
        document.removeEventListener('mousedown', el.__clickAway)
      }
    }
  }
}
</script>

<style scoped>
.time-picker { position: relative; flex-shrink: 0; }
.tp-btn {
  display: flex; align-items: center; gap: .4rem;
  height: 36px; padding: 0 .7rem;
  border: 1px solid var(--border-color); border-radius: 8px;
  background: var(--bg-secondary); color: var(--text-primary);
  font-size: .8rem; cursor: pointer; white-space: nowrap;
}
.tp-btn:hover:not(.disabled) { border-color: var(--primary-color); }
.tp-btn.open { border-color: var(--primary-color); box-shadow: 0 0 0 3px rgba(102,126,234,.12); }
.tp-btn.disabled { opacity: .5; cursor: not-allowed; }
.tp-label { max-width: 300px; overflow: hidden; text-overflow: ellipsis; font-variant-numeric: tabular-nums; }

.tp-dropdown {
  position: absolute; top: calc(100% + .3rem); right: 0; z-index: 60;
  display: flex;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 10px; box-shadow: 0 8px 24px rgba(0,0,0,.14);
  overflow: hidden;
}

/* 左列快捷区间 */
.tp-quick {
  display: flex; flex-direction: column; gap: .1rem;
  width: 132px; padding: .4rem;
  border-right: 1px solid var(--border-color);
  max-height: 380px; overflow-y: auto;
}
.tp-item {
  padding: .4rem .6rem; border: none; border-radius: 6px;
  background: transparent; color: var(--text-primary);
  font-size: .78rem; cursor: pointer; text-align: left; white-space: nowrap;
}
.tp-item:hover { background: var(--bg-hover); }
.tp-item.active { background: var(--primary-light); color: var(--primary-color); font-weight: 600; }

/* 右列日历 */
.tp-cal { width: 264px; padding: .55rem .6rem .6rem; display: flex; flex-direction: column; }
.cal-nav { display: flex; align-items: center; justify-content: space-between; margin-bottom: .35rem; }
.cal-nav-btn {
  width: 26px; height: 26px; border: none; border-radius: 6px;
  background: transparent; color: var(--text-secondary); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.cal-nav-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.cal-title { font-size: .82rem; font-weight: 600; color: var(--text-primary); }

.cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); }
.cal-wd {
  text-align: center; font-size: .66rem; color: var(--text-tertiary);
  padding: .2rem 0; font-weight: 600;
}
.cal-day {
  height: 30px; border: none; background: transparent;
  color: var(--text-primary); font-size: .76rem; cursor: pointer;
  border-radius: 6px; font-variant-numeric: tabular-nums;
}
.cal-day:hover:not(:disabled) { background: var(--bg-hover); }
.cal-day.dim { color: var(--text-tertiary); opacity: .5; }
.cal-day.today { font-weight: 800; color: var(--primary-color); }
.cal-day.future { opacity: .25; cursor: not-allowed; }
.cal-day.in-range { background: var(--primary-light); border-radius: 0; }
.cal-day.edge, .cal-day.edge-single {
  background: var(--primary-color); color: #fff; font-weight: 700;
}
.cal-day.edge.today, .cal-day.edge-single.today { color: #fff; }

/* 时间行 */
.cal-times {
  margin-top: .45rem; padding-top: .45rem;
  border-top: 1px solid var(--border-color);
  display: flex; flex-direction: column; gap: .3rem;
}
.cal-time-row { display: flex; align-items: center; gap: .4rem; }
.ct-label { font-size: .72rem; color: var(--text-tertiary); flex-shrink: 0; }
.ct-date {
  flex: 1; font-size: .76rem; color: var(--text-primary);
  font-family: 'Consolas', 'Monaco', monospace; white-space: nowrap;
}
.ct-input {
  width: 78px; padding: .28rem .4rem; text-align: center;
  border: 1px solid var(--border-color); border-radius: 6px;
  background: var(--bg-primary); color: var(--text-primary);
  font-size: .76rem; font-family: 'Consolas', 'Monaco', monospace; outline: none;
}
.ct-input:focus { border-color: var(--primary-color); }

.tp-apply {
  margin-top: .5rem; padding: .45rem; border: none; border-radius: 6px;
  background: var(--primary-color); color: #fff;
  font-size: .8rem; font-weight: 600; cursor: pointer;
}
.tp-apply:disabled { opacity: .45; cursor: not-allowed; }
.tp-apply:hover:not(:disabled) { filter: brightness(1.08); }
</style>
