<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import Sortable from 'sortablejs'
import ServiceCard from './ServiceCard.vue'
import { buildServiceBuckets, createServiceLayout } from '@/utils/serviceLayout'
import {
  getGroupCollapseKey,
  getGroupCollapseStorageKey,
  parseCollapsedGroupKeys,
  serializeCollapsedGroupKeys
} from '@/utils/serviceGroupCollapse'

const props = defineProps({
  environmentId: {
    type: Number,
    required: true
  },
  services: {
    type: Array,
    default: () => []
  },
  groups: {
    type: Array,
    default: () => []
  },
  dragDisabled: {
    type: Boolean,
    default: false
  },
  layoutSaving: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'layout-change',
  'dragging-change',
  'rename-group',
  'delete-group',
  'update',
  'rollback',
  'restart',
  'stop',
  'scale',
  'view',
  'edit',
  'copy',
  'delete'
])

const boardRoot = ref(null)
const namedGroupsRoot = ref(null)
const dragging = ref(false)
const collapsedGroupKeys = ref(new Set())
let sortableInstances = []
let draggingResetTimer = null
let layoutBeforeDrag = ''
let initializationVersion = 0

const buckets = computed(() => buildServiceBuckets(props.services, props.groups))
const namedBuckets = computed(() => buckets.value.filter(bucket => bucket.id !== null))
const ungroupedBucket = computed(() => buckets.value.find(bucket => bucket.id === null))

const loadCollapsedGroups = () => {
  if (typeof window === 'undefined') return

  const storageKey = getGroupCollapseStorageKey(props.environmentId)
  try {
    collapsedGroupKeys.value = new Set(
      parseCollapsedGroupKeys(window.localStorage.getItem(storageKey))
    )
  } catch (error) {
    console.warn(`读取环境 ${props.environmentId} 的分组折叠缓存失败:`, error)
    collapsedGroupKeys.value = new Set()
  }
}

const isGroupCollapsed = groupId =>
  collapsedGroupKeys.value.has(getGroupCollapseKey(groupId))

const toggleGroupCollapsed = groupId => {
  if (dragging.value) return

  const groupKey = getGroupCollapseKey(groupId)
  const nextKeys = new Set(collapsedGroupKeys.value)
  if (nextKeys.has(groupKey)) {
    nextKeys.delete(groupKey)
  } else {
    nextKeys.add(groupKey)
  }
  collapsedGroupKeys.value = nextKeys

  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(
      getGroupCollapseStorageKey(props.environmentId),
      serializeCollapsedGroupKeys(nextKeys)
    )
  } catch (error) {
    console.warn(`保存环境 ${props.environmentId} 的分组折叠缓存失败:`, error)
  }
}

const destroySortables = () => {
  sortableInstances.forEach(instance => instance.destroy())
  sortableInstances = []
}

const parseGroupId = value => value === 'ungrouped' ? null : Number(value)

const captureLayout = () => {
  const groupIds = Array.from(namedGroupsRoot.value?.children || [])
    .filter(element => element.classList.contains('named-service-group'))
    .map(element => Number(element.dataset.groupId))

  const serviceBuckets = Array.from(boardRoot.value?.querySelectorAll('.service-card-grid') || [])
    .map(element => ({
      groupId: parseGroupId(element.dataset.groupId),
      serviceIds: Array.from(element.children)
        .filter(child => child.classList.contains('service-card-item'))
        .map(child => Number(child.dataset.serviceId))
    }))

  return createServiceLayout(props.environmentId, groupIds, serviceBuckets)
}

const beginDragging = () => {
  if (draggingResetTimer) {
    clearTimeout(draggingResetTimer)
    draggingResetTimer = null
  }
  layoutBeforeDrag = JSON.stringify(captureLayout())
  dragging.value = true
  emit('dragging-change', true)
}

const finishDragging = () => {
  const nextLayout = captureLayout()
  if (JSON.stringify(nextLayout) !== layoutBeforeDrag) {
    emit('layout-change', nextLayout)
  }
  layoutBeforeDrag = ''
  draggingResetTimer = setTimeout(() => {
    dragging.value = false
    emit('dragging-change', false)
    draggingResetTimer = null
  }, 100)
}

const initializeSortables = async () => {
  const version = ++initializationVersion
  destroySortables()
  if (props.dragDisabled || props.layoutSaving) return

  await nextTick()
  if (version !== initializationVersion) return
  if (!boardRoot.value || !namedGroupsRoot.value) return

  sortableInstances.push(new Sortable(namedGroupsRoot.value, {
    animation: 180,
    draggable: '.named-service-group',
    handle: '.group-drag-handle',
    ghostClass: 'group-ghost',
    chosenClass: 'group-chosen',
    fallbackOnBody: true,
    swapThreshold: 0.65,
    onStart: beginDragging,
    onEnd: finishDragging
  }))

  boardRoot.value.querySelectorAll('.service-card-grid').forEach(grid => {
    sortableInstances.push(new Sortable(grid, {
      group: 'environment-services',
      animation: 180,
      draggable: '.service-card-item',
      filter: '.metrics-section, .metrics-section *, .service-actions, .service-actions *, button, input, textarea, select, a, [data-no-drag]',
      preventOnFilter: false,
      emptyInsertThreshold: 32,
      ghostClass: 'service-ghost',
      chosenClass: 'service-chosen',
      dragClass: 'service-dragging',
      fallbackOnBody: true,
      swapThreshold: 0.65,
      onStart: beginDragging,
      onEnd: finishDragging
    }))
  })
}

const layoutSignature = computed(() => [
  props.groups.map(group => `${group.id}:${group.sortOrder}`).join(','),
  props.services.map(service => `${service.id}:${service.groupId ?? 'u'}:${service.sortOrder}`).join(','),
  props.dragDisabled,
  props.layoutSaving
].join('|'))

watch(layoutSignature, initializeSortables, { flush: 'post' })
watch(() => props.environmentId, loadCollapsedGroups, { immediate: true })

onMounted(initializeSortables)

onUnmounted(() => {
  initializationVersion++
  destroySortables()
  if (draggingResetTimer) clearTimeout(draggingResetTimer)
  if (dragging.value) emit('dragging-change', false)
})
</script>

<template>
  <div
    ref="boardRoot"
    class="service-group-board"
    :class="{ 'drag-disabled': dragDisabled, 'layout-saving': layoutSaving }"
  >
    <div ref="namedGroupsRoot" class="named-groups">
      <section
        v-for="bucket in namedBuckets"
        :key="bucket.id"
        class="service-group named-service-group"
        :class="{ collapsed: isGroupCollapsed(bucket.id) }"
        :data-group-id="bucket.id"
      >
        <header class="group-header">
          <div class="group-heading">
            <button
              class="group-drag-handle"
              type="button"
              :disabled="dragDisabled || layoutSaving"
              title="拖动调整分组顺序"
              aria-label="拖动调整分组顺序"
            >
              ⋮⋮
            </button>
            <button
              class="group-collapse-button"
              type="button"
              :aria-expanded="!isGroupCollapsed(bucket.id)"
              :title="isGroupCollapsed(bucket.id) ? '展开分组' : '收起分组'"
              @click="toggleGroupCollapsed(bucket.id)"
            >
              <svg
                :class="{ collapsed: isGroupCollapsed(bucket.id) }"
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="m6 9 6 6 6-6"/>
              </svg>
            </button>
            <h2>{{ bucket.name }}</h2>
            <span class="service-count">{{ bucket.services.length }}</span>
          </div>
          <div class="group-actions" data-no-drag>
            <button
              type="button"
              :disabled="dragging || layoutSaving"
              @click="emit('rename-group', bucket)"
            >
              重命名
            </button>
            <button
              class="danger"
              type="button"
              :disabled="dragging || layoutSaving"
              @click="emit('delete-group', bucket)"
            >
              删除
            </button>
          </div>
        </header>

        <div
          v-show="!isGroupCollapsed(bucket.id)"
          class="service-card-grid"
          :data-group-id="bucket.id"
        >
          <div
            v-for="service in bucket.services"
            :key="service.id"
            class="service-card-item"
            :data-service-id="service.id"
          >
            <ServiceCard
              :service="service"
              :view-disabled="dragging"
              @update="emit('update', $event)"
              @rollback="emit('rollback', $event)"
              @restart="emit('restart', $event)"
              @stop="emit('stop', $event)"
              @scale="emit('scale', $event)"
              @view="emit('view', $event)"
              @edit="emit('edit', $event)"
              @copy="emit('copy', $event)"
              @delete="emit('delete', $event)"
            />
          </div>
          <div v-if="bucket.services.length === 0" class="empty-group">拖动服务到此分组</div>
        </div>
      </section>
    </div>

    <section
      v-if="ungroupedBucket"
      class="service-group ungrouped-service-group"
      :class="{ collapsed: isGroupCollapsed(null) }"
    >
      <header class="group-header">
        <div class="group-heading">
          <button
            class="group-collapse-button"
            type="button"
            :aria-expanded="!isGroupCollapsed(null)"
            :title="isGroupCollapsed(null) ? '展开分组' : '收起分组'"
            @click="toggleGroupCollapsed(null)"
          >
            <svg
              :class="{ collapsed: isGroupCollapsed(null) }"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            >
              <path d="m6 9 6 6 6-6"/>
            </svg>
          </button>
          <h2>{{ ungroupedBucket.name }}</h2>
          <span class="service-count">{{ ungroupedBucket.services.length }}</span>
        </div>
        <span class="fixed-group-hint">固定在底部</span>
      </header>

      <div
        v-show="!isGroupCollapsed(null)"
        class="service-card-grid"
        data-group-id="ungrouped"
      >
        <div
          v-for="service in ungroupedBucket.services"
          :key="service.id"
          class="service-card-item"
          :data-service-id="service.id"
        >
          <ServiceCard
            :service="service"
            :view-disabled="dragging"
            @update="emit('update', $event)"
            @rollback="emit('rollback', $event)"
            @restart="emit('restart', $event)"
            @stop="emit('stop', $event)"
            @scale="emit('scale', $event)"
            @view="emit('view', $event)"
            @edit="emit('edit', $event)"
            @copy="emit('copy', $event)"
            @delete="emit('delete', $event)"
          />
        </div>
        <div v-if="ungroupedBucket.services.length === 0" class="empty-group">暂无未分组服务</div>
      </div>
    </section>

    <div v-if="dragDisabled && services.length > 0" class="drag-disabled-hint">
      搜索或筛选状态下暂不支持拖动排序
    </div>
    <div v-if="layoutSaving" class="layout-saving-hint">正在保存布局…</div>
  </div>
</template>

<style scoped>
.service-group-board,
.named-groups {
  display: grid;
  gap: 1.25rem;
}

.service-group {
  padding: 1rem;
  background: color-mix(in srgb, var(--bg-secondary) 88%, var(--bg-primary));
  border: 1px solid var(--border-color);
  border-radius: 14px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 2.25rem;
  margin-bottom: 0.9rem;
}

.service-group.collapsed .group-header {
  margin-bottom: 0;
}

.group-heading,
.group-actions {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.group-heading h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 1rem;
}

.service-count {
  min-width: 1.45rem;
  padding: 0.1rem 0.4rem;
  color: var(--text-secondary);
  font-size: 0.75rem;
  text-align: center;
  background: var(--bg-primary);
  border-radius: 999px;
}

.group-drag-handle {
  width: 1.9rem;
  height: 1.9rem;
  padding: 0;
  color: var(--text-tertiary);
  font-weight: 700;
  letter-spacing: -0.2rem;
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: grab;
}

.group-drag-handle:hover:not(:disabled) {
  color: var(--primary-color);
  background: var(--bg-hover);
}

.group-drag-handle:active {
  cursor: grabbing;
}

.group-drag-handle:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.group-collapse-button {
  display: grid;
  width: 1.9rem;
  height: 1.9rem;
  padding: 0;
  place-items: center;
  color: var(--text-secondary);
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.group-collapse-button:hover {
  color: var(--primary-color);
  background: var(--bg-hover);
}

.group-collapse-button svg {
  transition: transform 0.2s ease;
}

.group-collapse-button svg.collapsed {
  transform: rotate(-90deg);
}

.group-actions button {
  padding: 0.35rem 0.55rem;
  color: var(--text-secondary);
  font-size: 0.78rem;
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  cursor: pointer;
}

.group-actions button:hover {
  color: var(--primary-color);
  border-color: var(--primary-color);
}

.group-actions button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.group-actions button.danger:hover {
  color: var(--danger-color);
  border-color: var(--danger-color);
}

.service-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.25rem;
  min-height: 4.5rem;
}

.service-card-item {
  min-width: 0;
  cursor: grab;
}

.service-card-item:active {
  cursor: grabbing;
}

.empty-group {
  grid-column: 1 / -1;
  display: grid;
  min-height: 4.5rem;
  place-items: center;
  color: var(--text-tertiary);
  font-size: 0.85rem;
  border: 1px dashed var(--border-color);
  border-radius: 10px;
}

.fixed-group-hint {
  color: var(--text-tertiary);
  font-size: 0.75rem;
}

.drag-disabled-hint,
.layout-saving-hint {
  position: fixed;
  right: 1.5rem;
  bottom: 1.5rem;
  z-index: 10;
  padding: 0.6rem 0.85rem;
  color: var(--text-secondary);
  font-size: 0.8rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: var(--shadow-lg);
}

.layout-saving-hint {
  color: var(--primary-color);
}

.group-ghost,
:deep(.service-ghost) {
  opacity: 0.35;
}

.group-chosen {
  border-color: var(--primary-color);
  box-shadow: var(--shadow-lg);
}

:deep(.service-chosen .service-card) {
  border-color: var(--primary-color);
  box-shadow: var(--shadow-lg);
}

:deep(.service-dragging) {
  transform: rotate(1deg);
}

.drag-disabled .service-card-item {
  cursor: pointer;
}

@media (max-width: 768px) {
  .service-group {
    padding: 0.75rem;
  }

  .service-card-grid {
    grid-template-columns: 1fr;
  }

  .group-actions {
    gap: 0.35rem;
  }
}
</style>
