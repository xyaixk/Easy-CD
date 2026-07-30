<script setup>
import { computed, ref } from 'vue'
import EnvConflictDialog from './EnvConflictDialog.vue'
import EnvManualPasteDialog from './EnvManualPasteDialog.vue'
import {
  EnvClipboardError,
  applyEnvMerge,
  getEffectiveEnvEntries,
  parseEnvText,
  prepareEnvMerge,
  serializeEnvList
} from '../utils/envClipboard.js'
import toast from '../utils/toast.js'

const props = defineProps({
  envList: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['apply'])

const showManualPaste = ref(false)
const manualPasteText = ref('')
const showConflictDialog = ref(false)
const pendingMergePlan = ref(null)

const conflicts = computed(() => pendingMergePlan.value?.conflicts || [])
const additionCount = computed(() => pendingMergePlan.value?.additions.length || 0)

const formatError = error => {
  if (error instanceof EnvClipboardError && error.lineNumber) {
    return `第 ${error.lineNumber} 行：${error.message}`
  }
  return error?.message || '未知错误'
}

const copyWithLegacyApi = text => {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0;'
  document.body.appendChild(textarea)
  textarea.select()

  try {
    if (!document.execCommand('copy')) throw new Error('浏览器未允许复制操作')
  } finally {
    document.body.removeChild(textarea)
  }
}

const copyEnvList = async () => {
  let text
  let entryCount
  try {
    const entries = getEffectiveEnvEntries(props.envList)
    if (!entries.length) {
      toast.warning('暂无可复制的环境变量')
      return
    }
    entryCount = entries.length
    text = serializeEnvList(props.envList)
  } catch (error) {
    toast.error(`复制失败：${formatError(error)}`)
    return
  }

  let clipboardError = null
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      toast.success(`已复制 ${entryCount} 个环境变量`)
      return
    } catch (error) {
      clipboardError = error
    }
  }

  try {
    copyWithLegacyApi(text)
    toast.success(`已复制 ${entryCount} 个环境变量`)
  } catch (fallbackError) {
    console.error('复制环境变量失败', clipboardError || fallbackError)
    toast.error('复制失败，请检查浏览器剪贴板权限')
  }
}

const setManualPasteVisibility = visible => {
  showManualPaste.value = visible
  if (!visible) manualPasteText.value = ''
}

const openManualPasteDialog = () => {
  manualPasteText.value = ''
  showManualPaste.value = true
  toast.info('浏览器无法直接读取剪贴板，请在弹窗中按 Ctrl+V')
}

const resetConflictDialog = () => {
  showConflictDialog.value = false
  pendingMergePlan.value = null
}

const setConflictVisibility = visible => {
  if (visible) {
    showConflictDialog.value = true
  } else {
    resetConflictDialog()
  }
}

const processPasteText = text => {
  let entries
  let mergePlan

  try {
    entries = parseEnvText(text)
    if (!entries.length) {
      toast.warning('剪贴板中没有可粘贴的环境变量')
      return false
    }
    mergePlan = prepareEnvMerge(props.envList, entries)
  } catch (error) {
    toast.error(`粘贴失败：${formatError(error)}`, 5000)
    return false
  }

  if (!mergePlan.conflicts.length) {
    if (!mergePlan.additions.length) {
      toast.info('粘贴内容与当前环境变量一致，无需更新')
      return true
    }

    emit('apply', applyEnvMerge(props.envList, mergePlan))
    toast.success(`已新增 ${mergePlan.additions.length} 个环境变量`)
    return true
  }

  pendingMergePlan.value = mergePlan
  showConflictDialog.value = true
  return true
}

const pasteFromClipboard = async () => {
  if (!navigator.clipboard?.readText) {
    openManualPasteDialog()
    return
  }

  let text
  try {
    text = await navigator.clipboard.readText()
  } catch (error) {
    console.warn('读取剪贴板失败，切换到手动粘贴', error)
    openManualPasteDialog()
    return
  }

  processPasteText(text)
}

const submitManualPaste = () => {
  if (processPasteText(manualPasteText.value)) setManualPasteVisibility(false)
}

const confirmConflictChoices = resolutions => {
  const mergePlan = pendingMergePlan.value
  if (!mergePlan) return

  const overwrittenCount = mergePlan.conflicts.filter(
    conflict => resolutions[conflict.key] === 'incoming'
  ).length
  emit('apply', applyEnvMerge(props.envList, mergePlan, resolutions))

  const resultParts = []
  if (mergePlan.additions.length) resultParts.push(`新增 ${mergePlan.additions.length} 个`)
  if (overwrittenCount) resultParts.push(`覆盖 ${overwrittenCount} 个`)
  const retainedCount = mergePlan.conflicts.length - overwrittenCount
  if (retainedCount) resultParts.push(`保留 ${retainedCount} 个`)
  toast.success(`环境变量已更新：${resultParts.join('，')}`)
  resetConflictDialog()
}
</script>

<template>
  <div class="env-clipboard-actions">
    <button
      type="button"
      class="clipboard-action"
      title="复制为 .env 格式"
      aria-label="复制环境变量"
      @click.stop="copyEnvList"
    >
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <rect x="9" y="9" width="13" height="13" rx="2"/>
        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
      </svg>
      复制
    </button>
    <button
      type="button"
      class="clipboard-action"
      title="从剪贴板粘贴 .env"
      aria-label="粘贴环境变量"
      @click.stop="pasteFromClipboard"
    >
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/>
        <rect x="8" y="2" width="8" height="4" rx="1"/>
      </svg>
      粘贴
    </button>
  </div>

  <EnvManualPasteDialog
    :visible="showManualPaste"
    :model-value="manualPasteText"
    @update:visible="setManualPasteVisibility"
    @update:model-value="manualPasteText = $event"
    @submit="submitManualPaste"
  />

  <EnvConflictDialog
    :visible="showConflictDialog"
    :conflicts="conflicts"
    :addition-count="additionCount"
    @update:visible="setConflictVisibility"
    @confirm="confirmConflictChoices"
  />
</template>

<style scoped>
.env-clipboard-actions {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.clipboard-action {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.28rem 0.55rem;
  border: 1px solid var(--border-color);
  background: white;
  color: var(--text-secondary);
  border-radius: 5px;
  font-size: 0.76rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}

.clipboard-action:hover {
  color: var(--primary-color);
  border-color: var(--primary-color);
  background: var(--primary-light);
}

@media (max-width: 640px) {
  .clipboard-action {
    padding: 0.3rem;
    font-size: 0;
  }

  .clipboard-action svg {
    width: 15px;
    height: 15px;
  }
}
</style>
