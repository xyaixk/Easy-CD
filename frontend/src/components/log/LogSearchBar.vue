<script setup>
import { ref, computed, watch } from 'vue'

// filters 为父组件的 reactive 对象；pills 完全由 filters 派生，删除/添加直接写回 filters
const props = defineProps({
  filters: { type: Object, required: true },
  availableServices: { type: Array, default: () => [] },
  availableContainers: { type: Array, default: () => [] },
  availableImages: { type: Array, default: () => [] },
  logs: { type: Array, default: () => [] }
})
const emit = defineEmits(['search'])

const ALL_LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR']
// 可检索字段定义（single 字段重复输入时覆盖旧值）
const KEYS = [
  { key: 'service',   desc: '服务名，可多个',      single: false },
  { key: 'image',     desc: '镜像名，可多个',      single: false },
  { key: 'level',     desc: '日志级别，可多个',    single: false },
  { key: 'container', desc: '容器名包含',          single: true },
  { key: 'logger',    desc: 'logger 前缀',        single: true },
  { key: 'trace',     desc: 'traceId 精确匹配',   single: true },
  { key: 'thread',    desc: '线程名前缀',          single: true }
]

const inputRef = ref(null)
const text = ref('')
const focused = ref(false)
const hoverIndex = ref(0)

// ============ pills：由 filters 派生 ============
const pills = computed(() => {
  const out = []
  props.filters.services.forEach(v => out.push({ kind: 'service', value: v }))
  props.filters.images.forEach(v => out.push({ kind: 'image', value: v }))
  props.filters.levels.forEach(v => out.push({ kind: 'level', value: v }))
  if (props.filters.traceId) out.push({ kind: 'trace', value: props.filters.traceId })
  if (props.filters.logger) out.push({ kind: 'logger', value: props.filters.logger })
  if (props.filters.containerName) out.push({ kind: 'container', value: props.filters.containerName })
  if (props.filters.thread) out.push({ kind: 'thread', value: props.filters.thread })
  if (props.filters.keyword) out.push({ kind: 'keyword', value: props.filters.keyword })
  return out
})

const removePill = (pill) => {
  const f = props.filters
  if (pill.kind === 'service') f.services.splice(f.services.indexOf(pill.value), 1)
  else if (pill.kind === 'image') f.images.splice(f.images.indexOf(pill.value), 1)
  else if (pill.kind === 'level') f.levels.splice(f.levels.indexOf(pill.value), 1)
  else if (pill.kind === 'trace') f.traceId = ''
  else if (pill.kind === 'logger') f.logger = ''
  else if (pill.kind === 'container') f.containerName = ''
  else if (pill.kind === 'thread') f.thread = ''
  else if (pill.kind === 'keyword') f.keyword = ''
  emit('search')
}

const clearAll = () => {
  const f = props.filters
  f.services.splice(0)
  f.images.splice(0)
  f.levels.splice(0)
  f.traceId = ''; f.logger = ''; f.containerName = ''; f.thread = ''; f.keyword = ''
  emit('search')
}

// ============ 输入解析 + 候选下拉 ============
const parsed = computed(() => {
  const idx = text.value.indexOf(':')
  if (idx < 0) return { key: null, value: text.value.trim() }
  return { key: text.value.slice(0, idx).trim().toLowerCase(), value: text.value.slice(idx + 1).trim() }
})

// 从当前结果集派生某字段的去重候选值（用于 logger/thread/trace 联想）
const logValues = (field) => {
  const seen = new Set()
  const out = []
  for (const r of props.logs) {
    const v = r?.[field]
    if (v && !seen.has(v)) { seen.add(v); out.push(v); if (out.length >= 200) break }
  }
  return out
}

// 多选标签候选（service / image）
const multiValueSuggestions = (key, pool, selected, value) => {
  const out = []
  const kw = value.toLowerCase()
  pool
    .filter(s => !selected.includes(s))
    .filter(s => !kw || s.toLowerCase().includes(kw))
    .slice(0, 20)
    .forEach(s => out.push({ type: 'value', key, value: s, label: s }))
  return out
}

// 单值字段候选（容器名走标签候选，logger/thread/trace 走结果集派生）+ 始终提供“确认输入”项
const singleValueSuggestions = (key, pool, value) => {
  const out = []
  const kw = value.toLowerCase()
  pool
    .filter(s => !kw || s.toLowerCase().includes(kw))
    .filter(s => s !== value)
    .slice(0, 20)
    .forEach(s => out.push({ type: 'value', key, value: s, label: s }))
  if (value) out.unshift({ type: 'value', key, value, label: `${key}: ${value}`, hint: '按 Enter 确认' })
  return out
}

const suggestions = computed(() => {
  const { key, value } = parsed.value
  const out = []
  if (key === null) {
    // 未输入冒号：匹配字段前缀 + 关键字搜索项
    const kw = value.toLowerCase()
    KEYS.filter(k => !kw || k.key.startsWith(kw)).forEach(k =>
      out.push({ type: 'key', key: k.key, label: `${k.key}:`, hint: k.desc }))
    if (value) out.push({ type: 'keyword', value, label: `搜索包含 "${value}" 的日志`, hint: '全文关键字' })
    return out
  }
  if (key === 'service') {
    return multiValueSuggestions('service', props.availableServices, props.filters.services, value)
  }
  if (key === 'image') {
    return multiValueSuggestions('image', props.availableImages, props.filters.images, value)
  }
  if (key === 'level') {
    ALL_LEVELS
      .filter(lv => !props.filters.levels.includes(lv))
      .filter(lv => !value || lv.toLowerCase().startsWith(value.toLowerCase()))
      .forEach(lv => out.push({ type: 'value', key, value: lv, label: lv }))
    return out
  }
  if (key === 'container') {
    return singleValueSuggestions('container', props.availableContainers, value)
  }
  if (key === 'logger') {
    return singleValueSuggestions('logger', logValues('logger'), value)
  }
  if (key === 'thread') {
    return singleValueSuggestions('thread', logValues('thread'), value)
  }
  if (key === 'trace') {
    return singleValueSuggestions('trace', logValues('traceId'), value)
  }
  // 未知 key：整体当关键字
  if (text.value.trim()) {
    out.push({ type: 'keyword', value: text.value.trim(), label: `搜索包含 "${text.value.trim()}" 的日志`, hint: '全文关键字' })
  }
  return out
})

const showDropdown = computed(() => focused.value && suggestions.value.length > 0)
watch(suggestions, () => { hoverIndex.value = 0 })

// ============ 应用候选 ============
const applySuggestion = (item) => {
  if (item.type === 'key') {
    text.value = item.key + ':'
    inputRef.value?.focus()
    return
  }
  const f = props.filters
  if (item.type === 'keyword') {
    f.keyword = item.value
  } else if (item.key === 'service') {
    if (!f.services.includes(item.value)) f.services.push(item.value)
  } else if (item.key === 'image') {
    if (!f.images.includes(item.value)) f.images.push(item.value)
  } else if (item.key === 'level') {
    const lv = item.value.toUpperCase()
    if (ALL_LEVELS.includes(lv) && !f.levels.includes(lv)) f.levels.push(lv)
  } else if (item.key === 'trace') {
    f.traceId = item.value
  } else if (item.key === 'logger') {
    f.logger = item.value
  } else if (item.key === 'container') {
    f.containerName = item.value
  } else if (item.key === 'thread') {
    f.thread = item.value
  }
  text.value = ''
  emit('search')
  inputRef.value?.focus()
}

const onKeydown = (e) => {
  if (e.key === 'Backspace' && !text.value && pills.value.length) {
    removePill(pills.value[pills.value.length - 1])
    return
  }
  if (e.key === 'Escape') { focused.value = false; return }
  if (!showDropdown.value) {
    if (e.key === 'Enter' && text.value.trim()) {
      e.preventDefault()
      applySuggestion({ type: 'keyword', value: text.value.trim() })
    }
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    hoverIndex.value = (hoverIndex.value + 1) % suggestions.value.length
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    hoverIndex.value = (hoverIndex.value - 1 + suggestions.value.length) % suggestions.value.length
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const item = suggestions.value[hoverIndex.value]
    if (item) applySuggestion(item)
  }
}

const onBlur = () => {
  // 延迟关闭，允许点击下拉项
  setTimeout(() => { focused.value = false }, 150)
}
const focusInput = () => inputRef.value?.focus()

const pillClass = (pill) => {
  if (pill.kind === 'level') return `p-level p-lv-${pill.value}`
  return `p-${pill.kind}`
}
const pillLabel = (pill) => pill.kind === 'keyword' ? `"${pill.value}"` : `${pill.kind}: ${pill.value}`
</script>

<template>
  <div class="search-bar" :class="{ focused }" @mousedown.self.prevent="focusInput">
    <svg class="sb-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
      <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
    </svg>

    <!-- 条件 pills -->
    <span v-for="pill in pills" :key="pill.kind + ':' + pill.value"
      class="pill" :class="pillClass(pill)">
      {{ pillLabel(pill) }}
      <button class="pill-x" @mousedown.prevent @click="removePill(pill)">
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </span>

    <input ref="inputRef" v-model="text" class="sb-input" type="text"
      :placeholder="pills.length ? '' : '搜索关键字，或输入 service: / image: / level: / container: / logger: 过滤...'"
      @focus="focused = true" @blur="onBlur" @keydown="onKeydown"/>

    <button v-if="pills.length || text" class="sb-clear" title="清空全部条件"
      @mousedown.prevent @click="text = ''; clearAll()">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
        <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
      </svg>
    </button>

    <!-- 自动补全下拉 -->
    <div v-if="showDropdown" class="sb-dropdown">
      <div v-for="(item, i) in suggestions" :key="item.type + (item.key || '') + (item.value || item.label)"
        class="sb-option" :class="{ hover: i === hoverIndex }"
        @mousedown.prevent @click="applySuggestion(item)" @mousemove="hoverIndex = i">
        <span class="opt-label" :class="{ 'opt-key': item.type === 'key' }">{{ item.label }}</span>
        <span v-if="item.hint" class="opt-hint">{{ item.hint }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-bar {
  position: relative;
  flex: 1; min-width: 260px;
  display: flex; align-items: center; flex-wrap: wrap; gap: .3rem;
  min-height: 36px; padding: .2rem .55rem;
  border: 1px solid var(--border-color); border-radius: 8px;
  background: var(--bg-secondary); cursor: text;
  transition: border-color .15s, box-shadow .15s;
}
.search-bar.focused { border-color: var(--primary-color); box-shadow: 0 0 0 3px rgba(102,126,234,.12); }
.sb-icon { color: var(--text-tertiary); flex-shrink: 0; }

.sb-input {
  flex: 1; min-width: 140px; height: 26px;
  border: none; outline: none; background: transparent;
  color: var(--text-primary); font-size: .85rem;
}
.sb-clear {
  flex-shrink: 0; width: 22px; height: 22px; border-radius: 50%;
  border: none; background: transparent; color: var(--text-tertiary);
  display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.sb-clear:hover { background: var(--bg-hover); color: var(--text-primary); }

/* pills */
.pill {
  display: inline-flex; align-items: center; gap: .3rem;
  height: 24px; padding: 0 .3rem 0 .55rem; border-radius: 6px;
  font-size: .76rem; font-weight: 600; white-space: nowrap;
  font-family: 'Consolas', 'Monaco', monospace;
}
.pill-x {
  width: 16px; height: 16px; border-radius: 4px; border: none;
  background: rgba(0,0,0,.12); color: inherit; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; padding: 0;
}
.pill-x:hover { background: rgba(0,0,0,.25); }
.p-service   { background: #dbeafe; color: #1d4ed8; }
.p-image     { background: #e0e7ff; color: #4338ca; }
.p-keyword   { background: #ede9fe; color: #6d28d9; }
.p-trace     { background: #d1fae5; color: #047857; }
.p-logger    { background: #cffafe; color: #0e7490; }
.p-container { background: #ffedd5; color: #c2410c; }
.p-thread    { background: #fce7f3; color: #be185d; }
.p-level     { background: #e5e7eb; color: #374151; }
.p-lv-ERROR  { background: #fee2e2; color: #b91c1c; }
.p-lv-WARN   { background: #fef3c7; color: #b45309; }
.p-lv-INFO   { background: #d1fae5; color: #047857; }
.p-lv-DEBUG  { background: #e5e7eb; color: #4b5563; }

/* 下拉 */
.sb-dropdown {
  position: absolute; top: calc(100% + .3rem); left: 0; right: 0; z-index: 60;
  max-height: 320px; overflow: auto;
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: 10px; box-shadow: 0 8px 24px rgba(0,0,0,.14);
  padding: .3rem;
}
.sb-option {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem;
  padding: .45rem .65rem; border-radius: 6px; cursor: pointer;
  font-size: .82rem;
}
.sb-option.hover { background: var(--bg-hover); }
.opt-label { color: var(--text-primary); font-family: 'Consolas', 'Monaco', monospace; }
.opt-label.opt-key { color: var(--primary-color); font-weight: 700; }
.opt-hint { color: var(--text-tertiary); font-size: .72rem; white-space: nowrap; }
</style>
