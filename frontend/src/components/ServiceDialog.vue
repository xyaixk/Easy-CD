<script setup>
import { ref, watch, onUnmounted, computed, reactive } from 'vue'
import { parseDockerCommand } from '../utils/dockerCommand.js'
import toast from '../utils/toast.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  currentEnvironment: { type: Object, required: true },
  service: { type: Object, default: null }
})

const emit = defineEmits(['update:visible', 'confirm'])

// 内置 key 名单（保持与后端 isDockerBuiltInParam 一致）
const BUILT_IN_KEYS = new Set([
  'replicas', 'cpus', 'memory', 'memory-reservation', 'cpu-reservation',
  'restart', 'restart-max-attempts', 'restart-delay',
  'publish', 'network', 'endpoint-mode',
  'healthcheck', 'healthcheck_interval', 'healthcheck_timeout',
  'healthcheck_retries', 'healthcheck_start_period',
  'update_parallelism', 'update_delay', 'update_monitor',
  'update_failure_action', 'update_order',
  'rollback_parallelism', 'rollback_delay', 'rollback_monitor',
  'rollback_failure_action', 'rollback_order',
  'container-label', 'container-labels', 'mounts',
  'log-driver', 'log-opts', 'command', 'constraints',
  'stop-grace-period', 'replicas-max-per-node'
])

const emptyForm = () => ({
  serviceName: '', description: '', dockerImage: '',
  serviceMode: 'replicated', replicas: 1,
  // 结构化分区
  network: '', endpointMode: '',
  publishList: [],       // ['8080:8080', 'mode=host,target=X,published=Y']
  mountList: [],         // ['/host:/container', 'type=bind,src=X,dst=Y']
  envList: [],           // [{ key, value }]
  constraintList: [],    // ['node.role==manager', 'node.labels.zone==us-east']
  cpus: '', memory: '', memoryReservation: '', cpuReservation: '',
  replicasMaxPerNode: '',
  restart: '', restartMaxAttempts: '', restartDelay: '',
  stopGracePeriod: '',
  healthcheck: '', healthcheckInterval: '', healthcheckTimeout: '',
  healthcheckRetries: '', healthcheckStartPeriod: '',
  updateParallelism: '', updateDelay: '', updateMonitor: '',
  updateFailureAction: '', updateOrder: '',
  rollbackParallelism: '', rollbackDelay: '', rollbackMonitor: '',
  rollbackFailureAction: '', rollbackOrder: '',
  logDriver: '', logOptsList: [], // [{ key, value }]
  labelList: [],         // ['prometheus.scrape=true']
  command: ''
})

const formData = ref(emptyForm())

// 分组折叠状态
const sections = reactive({
  env: true, network: true, mount: true, constraint: false, resource: false,
  restart: false, health: false, update: false, rollback: false, logAndLabel: false, command: false
})

// 导入 Docker 命令弹窗
const showImportDialog = ref(false)
const importText = ref('')

const currentDeployType = computed(() => props.currentEnvironment?.deployType || 'docker')
const isDockerDeploy = computed(() => currentDeployType.value === 'docker')

watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
    props.service ? loadServiceData() : (formData.value = emptyForm())
  } else {
    document.body.style.overflow = ''
  }
})

/** 从 service.dockerParams (JSON) 反解结构化字段 */
const loadServiceData = () => {
  if (!props.service) return
  let map = {}
  try { map = props.service.dockerParams ? JSON.parse(props.service.dockerParams) : {} } catch (_) { map = {} }

  const replicas = props.service.replicas || props.service.desiredInstances || map.replicas || 1
  delete map.replicas

  const form = emptyForm()
  form.serviceName = props.service.name || ''
  form.description = props.service.description || ''
  form.dockerImage = props.service.dockerImage || ''
  form.serviceMode = props.service.serviceMode || 'replicated'
  form.replicas = Number(replicas) || 1

  mapToForm(map, form)
  formData.value = form
}

/** dockerParams map → 结构化 formData */
const mapToForm = (map, form) => {
  form.network = strOr(map.network, '')
  form.endpointMode = strOr(map['endpoint-mode'], '')
  form.publishList = splitMulti(map.publish)
  form.mountList = splitMulti(map.mounts)
  form.labelList = splitMulti(map['container-labels'] ?? map['container-label'])
  form.constraintList = splitMulti(map.constraints)
  form.cpus = strOr(map.cpus, '')
  form.memory = strOr(map.memory, '')
  form.memoryReservation = strOr(map['memory-reservation'], '')
  form.cpuReservation = strOr(map['cpu-reservation'], '')
  form.replicasMaxPerNode = strOr(map['replicas-max-per-node'], '')
  form.restart = strOr(map.restart, '')
  form.restartMaxAttempts = strOr(map['restart-max-attempts'], '')
  form.restartDelay = strOr(map['restart-delay'], '')
  form.stopGracePeriod = strOr(map['stop-grace-period'], '')
  form.healthcheck = strOr(map.healthcheck, '')
  form.healthcheckInterval = strOr(map.healthcheck_interval, '')
  form.healthcheckTimeout = strOr(map.healthcheck_timeout, '')
  form.healthcheckRetries = strOr(map.healthcheck_retries, '')
  form.healthcheckStartPeriod = strOr(map.healthcheck_start_period, '')
  form.updateParallelism = strOr(map.update_parallelism, '')
  form.updateDelay = strOr(map.update_delay, '')
  form.updateMonitor = strOr(map.update_monitor, '')
  form.updateFailureAction = strOr(map.update_failure_action, '')
  form.updateOrder = strOr(map.update_order, '')
  form.rollbackParallelism = strOr(map.rollback_parallelism, '')
  form.rollbackDelay = strOr(map.rollback_delay, '')
  form.rollbackMonitor = strOr(map.rollback_monitor, '')
  form.rollbackFailureAction = strOr(map.rollback_failure_action, '')
  form.rollbackOrder = strOr(map.rollback_order, '')
  form.logDriver = strOr(map['log-driver'], '')
  form.command = strOr(map.command, '')

  // log-opts 可能是 JSON 字符串
  if (map['log-opts']) {
    try {
      const obj = typeof map['log-opts'] === 'string' ? JSON.parse(map['log-opts']) : map['log-opts']
      form.logOptsList = Object.entries(obj).map(([key, value]) => ({ key, value: String(value) }))
    } catch (_) {
      // 非 JSON，按 key=value 换行/逗号解析
      form.logOptsList = splitMulti(map['log-opts']).map(kv => {
        const eq = kv.indexOf('=')
        return eq > 0 ? { key: kv.substring(0, eq), value: kv.substring(eq + 1) } : { key: kv, value: '' }
      })
    }
  }

  // 剩余非内置 key 作为环境变量
  form.envList = []
  Object.entries(map).forEach(([k, v]) => {
    if (BUILT_IN_KEYS.has(k)) return
    form.envList.push({ key: k, value: v == null ? '' : String(v) })
  })
}

/** 结构化 formData → dockerParams map */
const formToMap = () => {
  const map = {}
  const f = formData.value
  if (f.network) map.network = f.network
  if (f.endpointMode) map['endpoint-mode'] = f.endpointMode
  const pub = f.publishList.map(s => (s || '').trim()).filter(Boolean)
  if (pub.length) map.publish = pub.join('\n')
  const mnt = f.mountList.map(s => (s || '').trim()).filter(Boolean)
  if (mnt.length) map.mounts = mnt.join('\n')
  const lbl = f.labelList.map(s => (s || '').trim()).filter(Boolean)
  if (lbl.length) map['container-labels'] = lbl.join('\n')
  const cst = f.constraintList.map(s => (s || '').trim()).filter(Boolean)
  if (cst.length) map.constraints = cst.join('\n')
  if (f.cpus) map.cpus = f.cpus
  if (f.memory) map.memory = f.memory
  if (f.memoryReservation) map['memory-reservation'] = f.memoryReservation
  if (f.cpuReservation) map['cpu-reservation'] = f.cpuReservation
  if (f.replicasMaxPerNode) map['replicas-max-per-node'] = f.replicasMaxPerNode
  if (f.restart) map.restart = f.restart
  if (f.restartMaxAttempts) map['restart-max-attempts'] = f.restartMaxAttempts
  if (f.restartDelay) map['restart-delay'] = f.restartDelay
  if (f.stopGracePeriod) map['stop-grace-period'] = f.stopGracePeriod
  if (f.healthcheck) map.healthcheck = f.healthcheck
  if (f.healthcheckInterval) map.healthcheck_interval = f.healthcheckInterval
  if (f.healthcheckTimeout) map.healthcheck_timeout = f.healthcheckTimeout
  if (f.healthcheckRetries) map.healthcheck_retries = f.healthcheckRetries
  if (f.healthcheckStartPeriod) map.healthcheck_start_period = f.healthcheckStartPeriod
  if (f.updateParallelism) map.update_parallelism = f.updateParallelism
  if (f.updateDelay) map.update_delay = f.updateDelay
  if (f.updateMonitor) map.update_monitor = f.updateMonitor
  if (f.updateFailureAction) map.update_failure_action = f.updateFailureAction
  if (f.updateOrder) map.update_order = f.updateOrder
  if (f.rollbackParallelism) map.rollback_parallelism = f.rollbackParallelism
  if (f.rollbackDelay) map.rollback_delay = f.rollbackDelay
  if (f.rollbackMonitor) map.rollback_monitor = f.rollbackMonitor
  if (f.rollbackFailureAction) map.rollback_failure_action = f.rollbackFailureAction
  if (f.rollbackOrder) map.rollback_order = f.rollbackOrder
  if (f.logDriver) map['log-driver'] = f.logDriver
  if (f.logOptsList && f.logOptsList.length) {
    const obj = {}
    f.logOptsList.forEach(({ key, value }) => { if (key && key.trim()) obj[key.trim()] = value == null ? '' : String(value) })
    if (Object.keys(obj).length) map['log-opts'] = JSON.stringify(obj)
  }
  if (f.command && f.command.trim()) map.command = f.command.trim()
  // 环境变量
  f.envList.forEach(({ key, value }) => {
    if (!key || !key.trim()) return
    const k = key.trim()
    if (BUILT_IN_KEYS.has(k)) return
    map[k] = value == null ? '' : String(value)
  })
  return map
}

const splitMulti = (v) => {
  if (v == null || v === '') return []
  if (Array.isArray(v)) return v.map(x => String(x)).filter(x => x.trim())
  const str = String(v)
  const parts = str.includes('\n') ? str.split(/\r?\n/) : str.split(',')
  return parts.map(x => x.trim()).filter(Boolean)
}
const strOr = (v, d) => (v == null || v === '') ? d : String(v)

// 列表增删
const addItem = (list, item) => { list.push(item) }
const removeItem = (list, idx) => { list.splice(idx, 1) }

const handleClose = () => { emit('update:visible', false); formData.value = emptyForm() }

const validateForm = () => {
  const f = formData.value
  if (!f.serviceName.trim()) { toast.warning('请输入服务名称'); return false }
  if (f.description && f.description.length > 20) { toast.warning('服务描述最多20字符'); return false }
  if (!f.dockerImage.trim()) { toast.warning('请输入 Docker 镜像'); return false }
  if (f.serviceMode !== 'global' && (f.replicas < 1 || f.replicas > 100)) {
    toast.warning('副本数必须在 1-100 之间'); return false
  }
  return true
}

const handleConfirm = () => {
  if (!validateForm()) return
  const f = formData.value
  const submitData = {
    environmentId: props.currentEnvironment.id,
    name: f.serviceName,
    description: f.description,
    dockerImage: f.dockerImage,
    serviceMode: f.serviceMode,
    replicas: f.serviceMode === 'global' ? 0 : f.replicas,
    dockerParams: JSON.stringify(formToMap())
  }
  emit('confirm', submitData)
  handleClose()
}

// 打开导入弹窗
const openImportDialog = () => { importText.value = ''; showImportDialog.value = true }

// 执行导入
const doImport = () => {
  const text = importText.value.trim()
  if (!text) { toast.warning('请粘贴 Docker 命令'); return }
  try {
    const parsed = parseDockerCommand(text)
    const form = emptyForm()
    // 基本信息（仅未填时补，或强制覆盖：这里选择强制覆盖以匹配用户"直接搞进去"预期）
    if (parsed.serviceName) form.serviceName = parsed.serviceName
    if (parsed.dockerImage) form.dockerImage = parsed.dockerImage
    if (parsed.serviceMode) form.serviceMode = parsed.serviceMode
    if (parsed.replicas) form.replicas = parsed.replicas
    // 保留当前的 description（导入不覆盖）
    form.description = formData.value.description
    // command 从解析结果拿
    if (parsed.command) parsed.dockerParams.command = parsed.command
    mapToForm(parsed.dockerParams, form)
    formData.value = form
    // 展开所有有内容的分区
    sections.env = form.envList.length > 0 || sections.env
    sections.network = form.network || form.publishList.length > 0 || sections.network
    sections.mount = form.mountList.length > 0 || sections.mount
    sections.constraint = form.constraintList.length > 0 || sections.constraint
    sections.resource = !!(form.cpus || form.memory || form.memoryReservation || form.cpuReservation || form.replicasMaxPerNode) || sections.resource
    sections.restart = !!(form.restart || form.restartMaxAttempts || form.stopGracePeriod) || sections.restart
    sections.health = !!form.healthcheck || sections.health
    sections.update = !!(form.updateParallelism || form.updateDelay) || sections.update
    sections.rollback = !!(form.rollbackParallelism || form.rollbackDelay) || sections.rollback
    sections.logAndLabel = !!(form.logDriver || form.logOptsList.length || form.labelList.length) || sections.logAndLabel
    sections.command = !!form.command || sections.command
    showImportDialog.value = false
    const unknownCount = parsed.unknownFlags?.length || 0
    if (unknownCount > 0) {
      toast.warning(`已导入，${unknownCount} 个未识别 flag 被忽略`)
    } else {
      toast.success('Docker 命令已导入')
    }
  } catch (e) {
    console.error('解析 Docker 命令失败', e)
    toast.error('解析失败：' + (e.message || '未知错误'))
  }
}

onUnmounted(() => { document.body.style.overflow = '' })
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="dialog-overlay">
        <div class="dialog-container" @click.stop>
          <!-- 顶部标题 -->
          <div class="dialog-header">
            <div class="header-content">
              <div class="header-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/>
                </svg>
              </div>
              <h3>{{ service ? '编辑服务' : '新增服务' }}</h3>
            </div>
            <button class="btn-close" @click="handleClose">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>

          <div class="dialog-body">
            <!-- 环境信息 -->
            <div class="env-info">
              <span class="env-label">部署环境：</span>
              <span class="env-name">{{ currentEnvironment.name }}</span>
              <span class="env-type">({{ currentEnvironment.deployType }})</span>
              <button type="button" class="btn-import" @click="openImportDialog">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
                从 Docker 命令导入
              </button>
            </div>

            <!-- 基础信息 -->
            <div class="form-group">
              <label>服务名称 <span class="required">*</span></label>
              <input v-model="formData.serviceName" type="text" placeholder="user-service" class="form-input" :disabled="!!service" />
            </div>

            <div class="form-group">
              <label>服务描述 <span class="optional">(可选，最多20字)</span></label>
              <input v-model="formData.description" type="text" placeholder="用户管理服务" class="form-input" maxlength="20" />
              <div class="form-hint">{{ formData.description.length }}/20 字符</div>
            </div>

            <div class="form-group">
              <label>Docker 镜像 <span class="required">*</span></label>
              <input v-model="formData.dockerImage" type="text" placeholder="harbor.example.com/project/user-service:1.0.0" class="form-input" />
              <div class="form-hint">完整镜像地址，包含仓库/项目/服务名和标签</div>
            </div>

            <div class="form-group">
              <label>部署模式 <span class="required">*</span></label>
              <div class="radio-group">
                <label class="radio-item" :class="{ active: formData.serviceMode === 'replicated' }">
                  <input type="radio" v-model="formData.serviceMode" value="replicated" :disabled="!!service" />
                  <span>副本模式</span>
                </label>
                <label class="radio-item" :class="{ active: formData.serviceMode === 'global' }">
                  <input type="radio" v-model="formData.serviceMode" value="global" :disabled="!!service" />
                  <span>全局模式</span>
                </label>
              </div>
              <div class="form-hint">副本模式手动指定副本数；全局模式每个节点各运行 1 个（创建后不可修改）</div>
            </div>

            <div class="form-group" v-if="formData.serviceMode !== 'global'">
              <label>副本数量 <span class="required">*</span></label>
              <input v-model.number="formData.replicas" type="number" min="1" max="100" class="form-input" style="max-width: 200px;" />
            </div>

            <!-- 分组：环境变量 -->
            <div class="section" :class="{ open: sections.env }">
              <div class="section-header" @click="sections.env = !sections.env">
                <span class="section-title">
                  <span class="section-chevron">›</span>
                  环境变量
                  <span class="section-count" v-if="formData.envList.length">{{ formData.envList.length }}</span>
                </span>
                <button type="button" class="btn-add-mini" @click.stop="addItem(formData.envList, { key: '', value: '' })">+ 添加</button>
              </div>
              <div class="section-body" v-show="sections.env">
                <div v-if="!formData.envList.length" class="empty-tip">暂无环境变量，点击右上角 "+ 添加"</div>
                <div v-for="(item, idx) in formData.envList" :key="idx" class="kv-row">
                  <input v-model="item.key" placeholder="KEY (如 SPRING_PROFILES_ACTIVE)" class="form-input kv-key" />
                  <span class="kv-sep">=</span>
                  <input v-model="item.value" placeholder="VALUE" class="form-input kv-val" />
                  <button type="button" class="btn-del" @click="removeItem(formData.envList, idx)" title="删除">×</button>
                </div>
              </div>
            </div>

            <!-- 分组：网络与端口 -->
            <div class="section" :class="{ open: sections.network }">
              <div class="section-header" @click="sections.network = !sections.network">
                <span class="section-title">
                  <span class="section-chevron">›</span>
                  网络与端口
                  <span class="section-count" v-if="formData.publishList.length">{{ formData.publishList.length }}</span>
                </span>
              </div>
              <div class="section-body" v-show="sections.network">
                <div class="form-row-2">
                  <div class="form-group">
                    <label class="sub-label">网络 (network)</label>
                    <input v-model="formData.network" type="text" placeholder="overlay / host / release-overlay" class="form-input" />
                  </div>
                  <div class="form-group">
                    <label class="sub-label">端点模式 (endpoint-mode)</label>
                    <select v-model="formData.endpointMode" class="form-input">
                      <option value="">默认 (vip)</option>
                      <option value="vip">vip</option>
                      <option value="dnsrr">dnsrr</option>
                    </select>
                  </div>
                </div>
                <div class="form-group">
                  <div class="label-with-button">
                    <label class="sub-label">端口映射 (publish)</label>
                    <button type="button" class="btn-add-mini" @click="addItem(formData.publishList, '')">+ 添加</button>
                  </div>
                  <div v-if="!formData.publishList.length" class="empty-tip">暂无端口映射</div>
                  <div v-for="(_, idx) in formData.publishList" :key="idx" class="list-row">
                    <input v-model="formData.publishList[idx]" placeholder="8080:8080 或 mode=host,target=X,published=Y" class="form-input" />
                    <button type="button" class="btn-del" @click="removeItem(formData.publishList, idx)">×</button>
                  </div>
                  <div class="form-hint">支持简写 `host:container` 或完整 `mode=host,target=X,published=Y,protocol=tcp`</div>
                </div>
              </div>
            </div>

            <!-- 分组：挂载卷 -->
            <div class="section" :class="{ open: sections.mount }">
              <div class="section-header" @click="sections.mount = !sections.mount">
                <span class="section-title">
                  <span class="section-chevron">›</span>
                  挂载卷
                  <span class="section-count" v-if="formData.mountList.length">{{ formData.mountList.length }}</span>
                </span>
                <button type="button" class="btn-add-mini" @click.stop="addItem(formData.mountList, '')">+ 添加</button>
              </div>
              <div class="section-body" v-show="sections.mount">
                <div v-if="!formData.mountList.length" class="empty-tip">暂无挂载</div>
                <div v-for="(_, idx) in formData.mountList" :key="idx" class="list-row">
                  <input v-model="formData.mountList[idx]" placeholder="/host/path:/container/path[:ro] 或 type=bind,src=X,dst=Y" class="form-input" />
                  <button type="button" class="btn-del" @click="removeItem(formData.mountList, idx)">×</button>
                </div>
                <div class="form-hint">支持简写 `src:dst[:ro]` 或完整 `type=bind,src=X,dst=Y[,readonly]`</div>
              </div>
            </div>

            <!-- 分组：节点约束 -->
            <div class="section" :class="{ open: sections.constraint }">
              <div class="section-header" @click="sections.constraint = !sections.constraint">
                <span class="section-title">
                  <span class="section-chevron">›</span>
                  节点约束
                  <span class="section-count" v-if="formData.constraintList.length">{{ formData.constraintList.length }}</span>
                </span>
                <button type="button" class="btn-add-mini" @click.stop="addItem(formData.constraintList, '')">+ 添加</button>
              </div>
              <div class="section-body" v-show="sections.constraint">
                <div v-if="!formData.constraintList.length" class="empty-tip">暂无节点约束，点击右上角 "+ 添加"</div>
                <div v-for="(_, idx) in formData.constraintList" :key="idx" class="list-row">
                  <input v-model="formData.constraintList[idx]" placeholder="node.role==manager 或 node.labels.zone==us-east" class="form-input" />
                  <button type="button" class="btn-del" @click="removeItem(formData.constraintList, idx)">×</button>
                </div>
                <div class="form-hint">常用：<code>node.role==manager</code>、<code>node.role==worker</code>、<code>node.labels.KEY==VALUE</code>、<code>node.hostname==NAME</code></div>
              </div>
            </div>

            <!-- 分组：资源限制 -->
            <div class="section" :class="{ open: sections.resource }">
              <div class="section-header" @click="sections.resource = !sections.resource">
                <span class="section-title"><span class="section-chevron">›</span>资源限制</span>
              </div>
              <div class="section-body" v-show="sections.resource">
                <div class="form-row-3">
                  <div><label class="sub-label">CPU 限制 (limit-cpu)</label><input v-model="formData.cpus" type="text" placeholder="2.0" class="form-input" /></div>
                  <div><label class="sub-label">内存限制 (limit-memory)</label><input v-model="formData.memory" type="text" placeholder="4G" class="form-input" /></div>
                  <div><label class="sub-label">内存预留 (reserve-memory)</label><input v-model="formData.memoryReservation" type="text" placeholder="2G" class="form-input" /></div>
                </div>
                <div class="form-row-3">
                  <div><label class="sub-label">CPU 预留 (reserve-cpu)</label><input v-model="formData.cpuReservation" type="text" placeholder="0.25" class="form-input" /></div>
                  <div><label class="sub-label">每节点最大副本</label><input v-model="formData.replicasMaxPerNode" type="text" placeholder="1" class="form-input" /></div>
                  <div></div>
                </div>
              </div>
            </div>

            <!-- 分组：重启策略 -->
            <div class="section" :class="{ open: sections.restart }">
              <div class="section-header" @click="sections.restart = !sections.restart">
                <span class="section-title"><span class="section-chevron">›</span>重启策略</span>
              </div>
              <div class="section-body" v-show="sections.restart">
                <div class="form-row-3">
                  <div>
                    <label class="sub-label">条件 (restart)</label>
                    <select v-model="formData.restart" class="form-input">
                      <option value="">默认</option>
                      <option value="any">any</option>
                      <option value="none">none</option>
                      <option value="on-failure">on-failure</option>
                    </select>
                  </div>
                  <div><label class="sub-label">最大次数</label><input v-model="formData.restartMaxAttempts" type="text" placeholder="0 表示无限" class="form-input" /></div>
                  <div><label class="sub-label">延迟</label><input v-model="formData.restartDelay" type="text" placeholder="5s" class="form-input" /></div>
                </div>
                <div class="form-row-3">
                  <div><label class="sub-label">停止优雅期 (stop-grace-period)</label><input v-model="formData.stopGracePeriod" type="text" placeholder="10s" class="form-input" /></div>
                  <div></div>
                  <div></div>
                </div>
              </div>
            </div>

            <!-- 分组：健康检查 -->
            <div class="section" :class="{ open: sections.health }">
              <div class="section-header" @click="sections.health = !sections.health">
                <span class="section-title"><span class="section-chevron">›</span>健康检查</span>
              </div>
              <div class="section-body" v-show="sections.health">
                <div class="form-group">
                  <label class="sub-label">命令 (healthcheck)</label>
                  <input v-model="formData.healthcheck" type="text" placeholder="curl -f http://localhost:8080/actuator/health || exit 1" class="form-input" />
                </div>
                <div class="form-row-4">
                  <div><label class="sub-label">interval</label><input v-model="formData.healthcheckInterval" type="text" placeholder="10s" class="form-input" /></div>
                  <div><label class="sub-label">timeout</label><input v-model="formData.healthcheckTimeout" type="text" placeholder="5s" class="form-input" /></div>
                  <div><label class="sub-label">retries</label><input v-model="formData.healthcheckRetries" type="text" placeholder="3" class="form-input" /></div>
                  <div><label class="sub-label">start_period</label><input v-model="formData.healthcheckStartPeriod" type="text" placeholder="30s" class="form-input" /></div>
                </div>
              </div>
            </div>

            <!-- 分组：滚动更新 -->
            <div class="section" :class="{ open: sections.update }">
              <div class="section-header" @click="sections.update = !sections.update">
                <span class="section-title"><span class="section-chevron">›</span>滚动更新</span>
              </div>
              <div class="section-body" v-show="sections.update">
                <div class="form-row-3">
                  <div><label class="sub-label">parallelism</label><input v-model="formData.updateParallelism" type="text" placeholder="1" class="form-input" /></div>
                  <div><label class="sub-label">delay</label><input v-model="formData.updateDelay" type="text" placeholder="10s" class="form-input" /></div>
                  <div><label class="sub-label">monitor</label><input v-model="formData.updateMonitor" type="text" placeholder="60s" class="form-input" /></div>
                </div>
                <div class="form-row-2">
                  <div>
                    <label class="sub-label">failure_action</label>
                    <select v-model="formData.updateFailureAction" class="form-input">
                      <option value="">默认</option>
                      <option value="pause">pause</option>
                      <option value="continue">continue</option>
                      <option value="rollback">rollback</option>
                    </select>
                  </div>
                  <div>
                    <label class="sub-label">order</label>
                    <select v-model="formData.updateOrder" class="form-input">
                      <option value="">默认</option>
                      <option value="start-first">start-first</option>
                      <option value="stop-first">stop-first</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>

            <!-- 分组：回滚策略 -->
            <div class="section" :class="{ open: sections.rollback }">
              <div class="section-header" @click="sections.rollback = !sections.rollback">
                <span class="section-title"><span class="section-chevron">›</span>回滚策略</span>
              </div>
              <div class="section-body" v-show="sections.rollback">
                <div class="form-row-3">
                  <div><label class="sub-label">parallelism</label><input v-model="formData.rollbackParallelism" type="text" placeholder="1" class="form-input" /></div>
                  <div><label class="sub-label">delay</label><input v-model="formData.rollbackDelay" type="text" placeholder="10s" class="form-input" /></div>
                  <div><label class="sub-label">monitor</label><input v-model="formData.rollbackMonitor" type="text" placeholder="30s" class="form-input" /></div>
                </div>
                <div class="form-row-2">
                  <div>
                    <label class="sub-label">failure_action</label>
                    <select v-model="formData.rollbackFailureAction" class="form-input">
                      <option value="">默认</option>
                      <option value="pause">pause</option>
                      <option value="continue">continue</option>
                    </select>
                  </div>
                  <div>
                    <label class="sub-label">order</label>
                    <select v-model="formData.rollbackOrder" class="form-input">
                      <option value="">默认</option>
                      <option value="start-first">start-first</option>
                      <option value="stop-first">stop-first</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>

            <!-- 分组：日志驱动 & 容器标签 -->
            <div class="section" :class="{ open: sections.logAndLabel }">
              <div class="section-header" @click="sections.logAndLabel = !sections.logAndLabel">
                <span class="section-title">
                  <span class="section-chevron">›</span>
                  日志与标签
                  <span class="section-count" v-if="formData.labelList.length || formData.logOptsList.length">{{ formData.labelList.length + formData.logOptsList.length }}</span>
                </span>
              </div>
              <div class="section-body" v-show="sections.logAndLabel">
                <div class="form-group">
                  <label class="sub-label">log-driver</label>
                  <input v-model="formData.logDriver" type="text" placeholder="loki / json-file / fluentd" class="form-input" />
                </div>
                <div class="form-group">
                  <div class="label-with-button">
                    <label class="sub-label">log-opts</label>
                    <button type="button" class="btn-add-mini" @click="addItem(formData.logOptsList, { key: '', value: '' })">+ 添加</button>
                  </div>
                  <div v-if="!formData.logOptsList.length" class="empty-tip">暂无 log-opts</div>
                  <div v-for="(item, idx) in formData.logOptsList" :key="idx" class="kv-row">
                    <input v-model="item.key" placeholder="loki-url" class="form-input kv-key" />
                    <span class="kv-sep">=</span>
                    <input v-model="item.value" placeholder="http://loki:3100/..." class="form-input kv-val" />
                    <button type="button" class="btn-del" @click="removeItem(formData.logOptsList, idx)">×</button>
                  </div>
                </div>
                <div class="form-group">
                  <div class="label-with-button">
                    <label class="sub-label">容器标签 (container-labels)</label>
                    <button type="button" class="btn-add-mini" @click="addItem(formData.labelList, '')">+ 添加</button>
                  </div>
                  <div v-if="!formData.labelList.length" class="empty-tip">暂无标签</div>
                  <div v-for="(_, idx) in formData.labelList" :key="idx" class="list-row">
                    <input v-model="formData.labelList[idx]" placeholder="prometheus.scrape=true" class="form-input" />
                    <button type="button" class="btn-del" @click="removeItem(formData.labelList, idx)">×</button>
                  </div>
                </div>
              </div>
            </div>

            <!-- 分组：启动命令 -->
            <div class="section" :class="{ open: sections.command }">
              <div class="section-header" @click="sections.command = !sections.command">
                <span class="section-title"><span class="section-chevron">›</span>启动命令</span>
              </div>
              <div class="section-body" v-show="sections.command">
                <textarea v-model="formData.command" placeholder="镜像后追加的命令与参数，例如：run --server.http.listen-addr=0.0.0.0:12345 --storage.path=/var/lib/alloy/data" class="form-textarea" rows="3" />
                <div class="form-hint">对应 <code>docker service create ... IMAGE [COMMAND] [ARG...]</code> 中 IMAGE 之后的部分</div>
              </div>
            </div>
          </div>

          <!-- 底部按钮 -->
          <div class="dialog-footer">
            <button class="btn btn-secondary" @click="handleClose">取消</button>
            <button class="btn btn-primary" @click="handleConfirm">{{ service ? '确认修改' : '确认新增' }}</button>
          </div>
        </div>

        <!-- 导入 Docker 命令弹窗 -->
        <Transition name="dialog-fade">
          <div v-if="showImportDialog" class="import-overlay" @click.self="showImportDialog = false">
            <div class="import-dialog" @click.stop>
              <div class="import-header">
                <h4>从 Docker 命令导入</h4>
                <button class="btn-close-mini" @click="showImportDialog = false">×</button>
              </div>
              <div class="import-body">
                <div class="form-hint" style="margin-bottom: 0.5rem;">
                  粘贴完整的 <code>docker service create</code> / <code>docker run</code> 命令，将自动识别其中所有 flag 并填入表单。
                </div>
                <textarea v-model="importText" class="form-textarea" rows="12" placeholder="docker service create \&#10;  --name alloy-ysb \&#10;  --mode global \&#10;  --network release-overlay \&#10;  --env APP_ENV=release \&#10;  --publish mode=host,target=12345,published=12345 \&#10;  --mount type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock \&#10;  nexus.dev.ysb/alloy-ysb:latest \&#10;  run --server.http.listen-addr=0.0.0.0:12345" />
              </div>
              <div class="import-footer">
                <button class="btn btn-secondary" @click="showImportDialog = false">取消</button>
                <button class="btn btn-primary" @click="doImport">解析并填入</button>
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>


<style scoped>
.dialog-overlay {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center;
  z-index: 2000; padding: 1rem;
}

.dialog-container {
  background: white;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  width: 100%; max-width: 720px; max-height: 92vh;
  display: flex; flex-direction: column; overflow: hidden;
}

.dialog-header {
  padding: 0.875rem 1.5rem;
  background: var(--primary-gradient);
  color: white;
  display: flex; justify-content: space-between; align-items: center;
  flex-shrink: 0;
  transition: background 0.3s ease;
}

.header-content { display: flex; align-items: center; gap: 0.85rem; }

.header-icon {
  width: 32px; height: 32px; border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  display: flex; align-items: center; justify-content: center;
  backdrop-filter: blur(10px);
}

.dialog-header h3 { margin: 0; font-size: 1.15rem; font-weight: 600; }

.btn-close {
  width: 32px; height: 32px; border-radius: 8px;
  background: rgba(255, 255, 255, 0.2); border: none; color: white;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s; cursor: pointer;
}
.btn-close:hover { background: rgba(255, 255, 255, 0.3); transform: rotate(90deg); }

.dialog-body { padding: 1.5rem 1.75rem; overflow-y: auto; flex: 1; }
.dialog-body::-webkit-scrollbar { width: 6px; }
.dialog-body::-webkit-scrollbar-thumb { background: var(--border-color); border-radius: 3px; }
.dialog-body::-webkit-scrollbar-thumb:hover { background: var(--text-tertiary); }

.env-info {
  padding: 0.75rem 1rem;
  background: var(--primary-light);
  border-radius: 8px;
  margin-bottom: 1.25rem;
  display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap;
}
.env-label { color: var(--text-secondary); font-size: 0.88rem; }
.env-name { color: var(--primary-color); font-weight: 600; font-size: 0.95rem; }
.env-type { color: var(--text-tertiary); font-size: 0.82rem; }

.btn-import {
  margin-left: auto;
  display: inline-flex; align-items: center; gap: 0.4rem;
  padding: 0.4rem 0.8rem;
  border: 1px solid var(--primary-color);
  background: white;
  color: var(--primary-color);
  border-radius: 6px;
  font-size: 0.82rem; font-weight: 500;
  cursor: pointer; transition: all 0.2s;
}
.btn-import:hover { background: var(--primary-color); color: white; transform: translateY(-1px); box-shadow: 0 2px 6px var(--primary-shadow); }

.form-group { margin-bottom: 1.1rem; }
.form-group label {
  display: block; margin-bottom: 0.4rem;
  color: var(--text-primary); font-weight: 500; font-size: 0.88rem;
}
.sub-label { font-size: 0.82rem; color: var(--text-secondary); font-weight: 500; }
.required { color: var(--danger-color); }
.optional { color: var(--text-tertiary); font-weight: 400; font-size: 0.8rem; }

.form-input, .form-textarea {
  width: 100%;
  padding: 0.55rem 0.85rem;
  border: 2px solid var(--border-color);
  border-radius: 7px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 0.9rem;
  transition: all 0.2s;
  font-family: inherit;
}
select.form-input { cursor: pointer; }
.form-input:focus, .form-textarea:focus {
  border-color: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-light);
  outline: none;
}
.form-input:disabled { background: var(--bg-primary); color: var(--text-tertiary); cursor: not-allowed; opacity: 0.6; }
.form-textarea { resize: vertical; min-height: 90px; font-family: 'Consolas', 'Monaco', monospace; font-size: 0.85rem; }

.form-hint { margin-top: 0.35rem; font-size: 0.76rem; color: var(--text-tertiary); line-height: 1.4; }
.form-hint code { padding: 0 4px; background: var(--bg-primary); border-radius: 3px; font-size: 0.85em; }

.form-row-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-row-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 0.75rem; }
.form-row-4 { display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 0.75rem; }

.radio-group { display: flex; gap: 0.75rem; }
.radio-item {
  flex: 1; display: flex; align-items: center; justify-content: center;
  cursor: pointer; padding: 0.55rem 1rem;
  border-radius: 7px; border: 2px solid var(--border-color); background: var(--bg-secondary);
  transition: all 0.2s;
}
.radio-item input[type="radio"] { position: absolute; opacity: 0; pointer-events: none; }
.radio-item:hover { border-color: var(--primary-color); background: var(--bg-hover); }
.radio-item.active { border-color: var(--primary-color); background: var(--primary-light); }
.radio-item.active span { color: var(--primary-color); font-weight: 600; }
.radio-item span { font-size: 0.9rem; color: var(--text-primary); font-weight: 500; transition: all 0.2s; }

/* 分组 */
.section {
  border: 1.5px solid var(--border-color);
  border-radius: 10px;
  margin-bottom: 0.85rem;
  overflow: hidden;
  transition: border-color 0.2s;
  background: white;
}
.section.open { border-color: color-mix(in srgb, var(--primary-color) 40%, var(--border-color)); }
.section-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0.7rem 1rem;
  background: var(--bg-secondary);
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}
.section-header:hover { background: var(--bg-hover); }
.section-title {
  display: inline-flex; align-items: center; gap: 0.4rem;
  font-weight: 600; color: var(--text-primary); font-size: 0.92rem;
}
.section-chevron {
  display: inline-block;
  font-size: 1.15rem; line-height: 1;
  color: var(--text-tertiary);
  transform: rotate(0deg);
  transition: transform 0.2s;
}
.section.open .section-chevron { transform: rotate(90deg); color: var(--primary-color); }
.section-count {
  display: inline-block;
  min-width: 20px; padding: 0 6px; height: 18px; line-height: 18px;
  border-radius: 9px;
  background: var(--primary-color); color: white;
  font-size: 0.72rem; font-weight: 600;
  text-align: center;
}
.section-body { padding: 0.9rem 1rem; background: white; }

/* 添加/删除按钮 */
.btn-add-mini {
  padding: 0.28rem 0.7rem;
  border: 1px dashed var(--primary-color);
  background: transparent;
  color: var(--primary-color);
  border-radius: 5px;
  font-size: 0.78rem; font-weight: 500;
  cursor: pointer; transition: all 0.15s;
}
.btn-add-mini:hover { background: var(--primary-light); border-style: solid; }
.btn-del {
  flex-shrink: 0;
  width: 28px; height: 28px;
  border: none;
  background: transparent;
  color: var(--text-tertiary);
  border-radius: 5px;
  font-size: 1.15rem; line-height: 1;
  cursor: pointer; transition: all 0.15s;
}
.btn-del:hover { background: rgba(239, 68, 68, 0.1); color: var(--danger-color); }

.label-with-button { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.4rem; }
.label-with-button label { margin-bottom: 0; }

.empty-tip { padding: 0.6rem; text-align: center; color: var(--text-tertiary); font-size: 0.82rem; background: var(--bg-secondary); border-radius: 6px; }

.kv-row, .list-row {
  display: flex; align-items: center; gap: 0.5rem;
  margin-bottom: 0.5rem;
}
.kv-row .kv-key { flex: 0 0 38%; }
.kv-row .kv-sep { color: var(--text-tertiary); font-weight: 600; }
.kv-row .kv-val { flex: 1; }
.list-row .form-input { flex: 1; }

.dialog-footer {
  padding: 1rem 1.75rem;
  border-top: 1px solid var(--border-color);
  display: flex; gap: 0.75rem; justify-content: flex-end;
  flex-shrink: 0;
  background: var(--bg-secondary);
}

.btn {
  padding: 0.55rem 1.35rem;
  border-radius: 7px;
  font-weight: 500; font-size: 0.88rem;
  transition: all 0.2s; cursor: pointer; border: none;
}
.btn-secondary { background: white; border: 2px solid var(--border-color); color: var(--text-primary); }
.btn-secondary:hover { border-color: var(--border-hover); background: var(--bg-hover); }
.btn-primary {
  background: var(--primary-gradient);
  color: white;
  box-shadow: 0 2px 8px var(--primary-shadow);
}
.btn-primary:hover { transform: translateY(-1px); box-shadow: 0 4px 12px var(--primary-shadow); }

/* 导入弹窗 */
.import-overlay {
  position: fixed; inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(3px);
  display: flex; align-items: center; justify-content: center;
  z-index: 2100; padding: 1rem;
}
.import-dialog {
  background: white; border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
  width: 100%; max-width: 640px;
  display: flex; flex-direction: column;
  overflow: hidden;
}
.import-header {
  padding: 1rem 1.25rem;
  background: var(--primary-gradient);
  color: white;
  display: flex; justify-content: space-between; align-items: center;
}
.import-header h4 { margin: 0; font-size: 1rem; font-weight: 600; }
.btn-close-mini {
  width: 28px; height: 28px; border-radius: 6px;
  background: rgba(255, 255, 255, 0.2); border: none; color: white;
  font-size: 1.15rem; line-height: 1; cursor: pointer;
  transition: all 0.2s;
}
.btn-close-mini:hover { background: rgba(255, 255, 255, 0.3); }
.import-body { padding: 1rem 1.25rem; }
.import-footer {
  padding: 0.85rem 1.25rem;
  border-top: 1px solid var(--border-color);
  display: flex; gap: 0.65rem; justify-content: flex-end;
  background: var(--bg-secondary);
}

.dialog-fade-enter-active, .dialog-fade-leave-active { transition: opacity 0.25s; }
.dialog-fade-enter-active .dialog-container, .dialog-fade-leave-active .dialog-container,
.dialog-fade-enter-active .import-dialog, .dialog-fade-leave-active .import-dialog {
  transition: transform 0.25s, opacity 0.25s;
}
.dialog-fade-enter-from, .dialog-fade-leave-to { opacity: 0; }
.dialog-fade-enter-from .dialog-container, .dialog-fade-leave-to .dialog-container,
.dialog-fade-enter-from .import-dialog, .dialog-fade-leave-to .import-dialog {
  transform: scale(0.94); opacity: 0;
}
</style>
