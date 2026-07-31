<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  filters: { type: Object, required: true },
  availableServices: { type: Array, default: () => [] },
  availableImages: { type: Array, default: () => [] },
  logs: { type: Array, default: () => [] }
})
const emit = defineEmits(['search'])

const ALL_LEVELS = ['ERROR', 'WARN', 'INFO', 'DEBUG']
const LEVEL_COLORS = { ERROR: '#ef4444', WARN: '#f59e0b', INFO: '#10b981', DEBUG: '#9ca3af' }

const collapsed = ref({ level: false, service: false, image: true, logger: false })
const toggleSection = (k) => { collapsed.value[k] = !collapsed.value[k] }

// ============ 基于当前结果集的命中计数 ============
const levelCounts = computed(() => {
  const map = {}
  props.logs.forEach(r => {
    const lv = (r.level || '').toUpperCase()
    if (lv) map[lv] = (map[lv] || 0) + 1
  })
  return map
})
const serviceCounts = computed(() => {
  const map = {}
  props.logs.forEach(r => {
    if (r.service) map[r.service] = (map[r.service] || 0) + 1
  })
  return map
})
// 服务列表：可选服务 ∪ 结果中出现的服务，已选 > 有命中 > 字母序
const serviceList = computed(() => {
  const set = new Set(props.availableServices)
  Object.keys(serviceCounts.value).forEach(s => set.add(s))
  return [...set].sort((a, b) => {
    const selA = props.filters.services.includes(a) ? 0 : 1
    const selB = props.filters.services.includes(b) ? 0 : 1
    if (selA !== selB) return selA - selB
    const cntA = serviceCounts.value[a] || 0
    const cntB = serviceCounts.value[b] || 0
    if (cntA !== cntB) return cntB - cntA
    return a.localeCompare(b)
  })
})
// 镜像计数与列表：可选镜像 ∪ 结果中出现的镜像
const imageCounts = computed(() => {
  const map = {}
  props.logs.forEach(r => {
    if (r.imageName) map[r.imageName] = (map[r.imageName] || 0) + 1
  })
  return map
})
const imageList = computed(() => {
  const set = new Set(props.availableImages)
  Object.keys(imageCounts.value).forEach(s => set.add(s))
  return [...set].sort((a, b) => {
    const selA = props.filters.images.includes(a) ? 0 : 1
    const selB = props.filters.images.includes(b) ? 0 : 1
    if (selA !== selB) return selA - selB
    const cntA = imageCounts.value[a] || 0
    const cntB = imageCounts.value[b] || 0
    if (cntA !== cntB) return cntB - cntA
    return a.localeCompare(b)
  })
})
// Top logger（取当前结果前 8）
const topLoggers = computed(() => {
  const map = {}
  props.logs.forEach(r => {
    if (r.logger) map[r.logger] = (map[r.logger] || 0) + 1
  })
  return Object.entries(map).sort((a, b) => b[1] - a[1]).slice(0, 8)
})

const serviceFilter = ref('')
const filteredServices = computed(() => {
  const kw = serviceFilter.value.trim().toLowerCase()
  if (!kw) return serviceList.value
  return serviceList.value.filter(s => s.toLowerCase().includes(kw))
})
const imageFilter = ref('')
const filteredImages = computed(() => {
  const kw = imageFilter.value.trim().toLowerCase()
  if (!kw) return imageList.value
  return imageList.value.filter(s => s.toLowerCase().includes(kw))
})

// ============ 交互 ============
const toggleLevel = (lv) => {
  const arr = props.filters.levels
  const i = arr.indexOf(lv)
  i >= 0 ? arr.splice(i, 1) : arr.push(lv)
  emit('search')
}
const toggleService = (svc) => {
  const arr = props.filters.services
  const i = arr.indexOf(svc)
  i >= 0 ? arr.splice(i, 1) : arr.push(svc)
  emit('search')
}
const toggleImage = (img) => {
  const arr = props.filters.images
  const i = arr.indexOf(img)
  i >= 0 ? arr.splice(i, 1) : arr.push(img)
  emit('search')
}
const shortImage = (img) => {
  const noReg = img.includes('/') ? img.slice(img.lastIndexOf('/') + 1) : img
  return noReg.length > 34 ? noReg.slice(0, 33) + '…' : noReg
}
const setLogger = (lg) => {
  props.filters.logger = props.filters.logger === lg ? '' : lg
  emit('search')
}
const shortLogger = (lg) => {
  const parts = lg.split('.')
  return parts.length <= 2 ? lg : parts.slice(0, -1).map(p => p[0]).join('.') + '.' + parts[parts.length - 1]
}
</script>

<template>
  <aside class="facet-panel">
    <!-- 级别 -->
    <div class="facet-section">
      <div class="facet-head" @click="toggleSection('level')">
        <svg class="chev" :class="{ open: !collapsed.level }" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        <span>级别</span>
      </div>
      <div v-show="!collapsed.level" class="facet-body">
        <div v-for="lv in ALL_LEVELS" :key="lv" class="facet-item"
          :class="{ active: filters.levels.includes(lv) }" @click="toggleLevel(lv)">
          <span class="lv-dot" :style="{ background: LEVEL_COLORS[lv] }"></span>
          <span class="fi-label">{{ lv }}</span>
          <span v-if="levelCounts[lv]" class="fi-count">{{ levelCounts[lv] }}</span>
          <svg v-if="filters.levels.includes(lv)" class="fi-check" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
        </div>
      </div>
    </div>

    <!-- 服务 -->
    <div class="facet-section grow-section">
      <div class="facet-head" @click="toggleSection('service')">
        <svg class="chev" :class="{ open: !collapsed.service }" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        <span>服务</span>
        <span v-if="filters.services.length" class="head-badge">{{ filters.services.length }}</span>
      </div>
      <div v-show="!collapsed.service" class="facet-body svc-body">
        <input v-if="serviceList.length > 8" v-model="serviceFilter" class="svc-filter" placeholder="过滤服务..."/>
        <div class="svc-list">
          <div v-for="svc in filteredServices" :key="svc" class="facet-item"
            :class="{ active: filters.services.includes(svc) }" @click="toggleService(svc)">
            <span class="fi-label mono" :title="svc">{{ svc }}</span>
            <span v-if="serviceCounts[svc]" class="fi-count">{{ serviceCounts[svc] }}</span>
            <svg v-if="filters.services.includes(svc)" class="fi-check" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
          </div>
          <div v-if="!filteredServices.length" class="facet-empty">无匹配服务</div>
        </div>
      </div>
    </div>

    <!-- 镜像 -->
    <div v-if="imageList.length" class="facet-section">
      <div class="facet-head" @click="toggleSection('image')">
        <svg class="chev" :class="{ open: !collapsed.image }" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        <span>镜像</span>
        <span v-if="filters.images.length" class="head-badge">{{ filters.images.length }}</span>
      </div>
      <div v-show="!collapsed.image" class="facet-body">
        <input v-if="imageList.length > 8" v-model="imageFilter" class="svc-filter" placeholder="过滤镜像..."/>
        <div v-for="img in filteredImages" :key="img" class="facet-item"
          :class="{ active: filters.images.includes(img) }" @click="toggleImage(img)">
          <span class="fi-label mono" :title="img">{{ shortImage(img) }}</span>
          <span v-if="imageCounts[img]" class="fi-count">{{ imageCounts[img] }}</span>
          <svg v-if="filters.images.includes(img)" class="fi-check" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
        </div>
      </div>
    </div>

    <!-- Top logger（来自当前结果） -->
    <div v-if="topLoggers.length" class="facet-section">
      <div class="facet-head" @click="toggleSection('logger')">
        <svg class="chev" :class="{ open: !collapsed.logger }" width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        <span>Top Logger</span>
      </div>
      <div v-show="!collapsed.logger" class="facet-body">
        <div v-for="[lg, cnt] in topLoggers" :key="lg" class="facet-item"
          :class="{ active: filters.logger === lg }" @click="setLogger(lg)">
          <span class="fi-label mono" :title="lg">{{ shortLogger(lg) }}</span>
          <span class="fi-count">{{ cnt }}</span>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.facet-panel {
  width: 218px; flex-shrink: 0;
  display: flex; flex-direction: column; gap: .15rem;
  overflow-y: auto; padding: .4rem .35rem .4rem .5rem;
  border-right: 1px solid var(--border-color);
  background: var(--bg-primary);
}
.facet-section { display: flex; flex-direction: column; }
.grow-section { flex: 1; min-height: 0; }
.grow-section .facet-body { flex: 1; min-height: 0; display: flex; flex-direction: column; }

.facet-head {
  display: flex; align-items: center; gap: .35rem;
  padding: .4rem .35rem; cursor: pointer; user-select: none;
  font-size: .72rem; font-weight: 700; letter-spacing: .5px;
  color: var(--text-secondary); text-transform: uppercase;
}
.facet-head:hover { color: var(--text-primary); }
.chev { transition: transform .15s; }
.chev.open { transform: rotate(90deg); }
.head-badge {
  min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px;
  background: var(--primary-color); color: #fff;
  font-size: .65rem; display: inline-flex; align-items: center; justify-content: center;
}

.facet-item {
  display: flex; align-items: center; gap: .4rem;
  padding: .3rem .45rem; border-radius: 6px; cursor: pointer;
  font-size: .78rem; color: var(--text-primary);
}
.facet-item:hover { background: var(--bg-hover); }
.facet-item.active { background: var(--primary-light); color: var(--primary-color); font-weight: 600; }
.lv-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.fi-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fi-label.mono { font-family: 'Consolas', 'Monaco', monospace; font-size: .74rem; }
.fi-count { font-size: .68rem; color: var(--text-tertiary); font-variant-numeric: tabular-nums; }
.facet-item.active .fi-count { color: var(--primary-color); }
.fi-check { color: var(--primary-color); flex-shrink: 0; }

.svc-filter {
  margin: 0 .3rem .3rem; padding: .28rem .5rem;
  border: 1px solid var(--border-color); border-radius: 6px;
  background: var(--bg-secondary); color: var(--text-primary); font-size: .74rem;
  outline: none;
}
.svc-filter:focus { border-color: var(--primary-color); }
.svc-list { flex: 1; min-height: 0; overflow-y: auto; }
.facet-empty { padding: .5rem; font-size: .74rem; color: var(--text-tertiary); text-align: center; }
</style>
