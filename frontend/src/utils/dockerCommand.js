/**
 * Docker CLI 命令解析工具
 * 支持 `docker service create/update`、`docker run` / `docker container run` 完整命令解析
 * 将命令解析为 { serviceName, dockerImage, serviceMode, replicas, dockerParams, command }
 */

// 布尔型 flag（不需要跟值）
const BOOL_FLAGS = new Set([
  '-d', '--detach',
  '-i', '--interactive',
  '-t', '--tty',
  '--rm',
  '--init',
  '--read-only',
  '--no-healthcheck',
  '--privileged',
  '--with-registry-auth',
  '--detach-keys',
  '--replace',
  '--force'
])

/**
 * 按 shell 规则做 tokenize：
 * - 支持单/双引号包裹
 * - 支持行尾续行 `\` + 换行
 * - 支持 `\ ` 转义空格
 */
function tokenize(input) {
  const tokens = []
  let cur = ''
  let quote = null
  let hasContent = false
  const s = input || ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (quote) {
      if (c === quote) { quote = null; continue }
      if (c === '\\' && i + 1 < s.length && quote === '"') {
        const nxt = s[i + 1]
        if (nxt === '"' || nxt === '\\' || nxt === '$' || nxt === '`') { cur += nxt; i++; continue }
      }
      cur += c
      hasContent = true
      continue
    }
    if (c === "'" || c === '"') { quote = c; hasContent = true; continue }
    if (c === '\\' && i + 1 < s.length) {
      const nxt = s[i + 1]
      if (nxt === '\n' || nxt === '\r') {
        // 行尾续行，吃掉 \ 和后面的换行（可能 \r\n）
        i++
        if (nxt === '\r' && s[i + 1] === '\n') i++
        continue
      }
      if (nxt === ' ' || nxt === '\t' || nxt === '"' || nxt === "'" || nxt === '\\') {
        cur += nxt
        hasContent = true
        i++
        continue
      }
      cur += c
      hasContent = true
      continue
    }
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r') {
      if (hasContent) { tokens.push(cur); cur = ''; hasContent = false }
      continue
    }
    cur += c
    hasContent = true
  }
  if (hasContent) tokens.push(cur)
  return tokens
}

/**
 * 剥掉 docker 命令前缀，返回剩余 tokens
 * 支持：
 *   docker service create ...
 *   docker service update ...
 *   docker run ...
 *   docker container run ...
 */
function stripDockerPrefix(tokens) {
  let idx = 0
  if (tokens[idx] === 'docker') idx++
  if (tokens[idx] === 'sudo' || tokens[idx] === '-i') idx++ // 兜底
  if (tokens[idx] === 'service') {
    idx++
    if (tokens[idx] === 'create' || tokens[idx] === 'update') idx++
  } else if (tokens[idx] === 'container') {
    idx++
    if (tokens[idx] === 'run') idx++
  } else if (tokens[idx] === 'run') {
    idx++
  } else if (tokens[idx] === 'create') {
    idx++
  }
  return tokens.slice(idx)
}

/**
 * 解析 docker 命令
 * @param {string} cmdText 完整命令文本（可含 \ 续行）
 * @returns {{
 *   serviceName?: string,
 *   dockerImage?: string,
 *   serviceMode?: 'replicated'|'global',
 *   replicas?: number,
 *   dockerParams: Record<string, string>,
 *   command?: string,
 *   unknownFlags: Array<{flag: string, value?: string}>
 * }}
 */
export function parseDockerCommand(cmdText) {
  const tokens = stripDockerPrefix(tokenize(cmdText))
  const result = {
    dockerParams: {},
    unknownFlags: []
  }

  // 多值累加器（用换行分隔存储）
  const multiValues = {
    publish: [],
    mounts: [],
    'container-labels': [],
    constraints: [],
    envs: []
  }

  let i = 0
  // 阶段 1：解析 flag，直到遇到第一个非 flag（作为 image）
  while (i < tokens.length) {
    const tok = tokens[i]
    if (!tok.startsWith('-')) break // image 边界

    let flag = tok
    let value

    // 支持 --flag=value 形式
    const eq = tok.indexOf('=')
    if (tok.startsWith('--') && eq > 0) {
      flag = tok.substring(0, eq)
      value = tok.substring(eq + 1)
    }

    if (BOOL_FLAGS.has(flag) && value === undefined) {
      i++
      continue
    }

    if (value === undefined) {
      // 下一个 token 作为值
      value = tokens[i + 1]
      if (value === undefined || (value.startsWith('-') && !isLikelyValue(flag, value))) {
        // 没有值也没有合法值，可能是布尔 flag
        i++
        continue
      }
      i += 2
    } else {
      i++
    }

    applyFlag(flag, value, result, multiValues)
  }

  // 阶段 2：剩余第一个 token 作为 image
  if (i < tokens.length) {
    result.dockerImage = tokens[i]
    i++
  }

  // 阶段 3：剩余作为 command
  if (i < tokens.length) {
    result.command = tokens.slice(i).map(t => needsQuote(t) ? JSON.stringify(t) : t).join(' ')
  }

  // 合并多值到 dockerParams
  if (multiValues.publish.length) {
    result.dockerParams.publish = multiValues.publish.join('\n')
  }
  if (multiValues.mounts.length) {
    result.dockerParams.mounts = multiValues.mounts.join('\n')
  }
  if (multiValues['container-labels'].length) {
    result.dockerParams['container-labels'] = multiValues['container-labels'].join('\n')
  }
  if (multiValues.constraints.length) {
    result.dockerParams.constraints = multiValues.constraints.join('\n')
  }
  // 环境变量直接展开到 dockerParams
  for (const kv of multiValues.envs) {
    const eq = kv.indexOf('=')
    if (eq > 0) {
      const k = kv.substring(0, eq).trim()
      const v = kv.substring(eq + 1)
      if (k) result.dockerParams[k] = v
    } else if (kv.trim()) {
      result.dockerParams[kv.trim()] = ''
    }
  }

  return result
}

function needsQuote(t) {
  return /\s/.test(t) || t.includes('"') || t.includes("'")
}

// 特殊情况：某些 flag 的值本身可能以 `-` 开头（很少见，一般不会出现）
function isLikelyValue(flag, next) {
  // health-cmd 可能是 `sh -c "..."` 但通常被引号包住
  return false
}

function applyFlag(flag, value, result, multiValues) {
  switch (flag) {
    case '--name':
      result.serviceName = value
      break
    case '--mode':
      result.serviceMode = value === 'global' ? 'global' : 'replicated'
      break
    case '--replicas':
      result.replicas = parseInt(value, 10) || 1
      break

    // 环境变量
    case '-e':
    case '--env':
      multiValues.envs.push(value)
      break

    // 端口
    case '-p':
    case '--publish':
      multiValues.publish.push(value)
      break

    // 挂载 / 卷
    case '--mount':
      multiValues.mounts.push(value)
      break
    case '-v':
    case '--volume':
      multiValues.mounts.push(value) // volume 也走 mounts（透传 src:dst[:ro] 格式）
      break

    // 网络
    case '--network':
    case '--net':
      result.dockerParams.network = value
      break

    // 资源限制（docker service create / docker run 两种字段名兼容）
    case '--limit-cpu':
    case '--cpus':
      result.dockerParams.cpus = value
      break
    case '--limit-memory':
    case '--memory':
    case '-m':
      result.dockerParams.memory = value
      break
    case '--reserve-memory':
    case '--memory-reservation':
      result.dockerParams['memory-reservation'] = value
      break
    case '--reserve-cpu':
      result.dockerParams['cpu-reservation'] = value
      break

    // 重启策略
    case '--restart-condition':
    case '--restart':
      result.dockerParams.restart = value
      break
    case '--restart-max-attempts':
      result.dockerParams['restart-max-attempts'] = value
      break
    case '--restart-delay':
      result.dockerParams['restart-delay'] = value
      break

    // 健康检查
    case '--health-cmd':
      result.dockerParams.healthcheck = value
      break
    case '--health-interval':
      result.dockerParams.healthcheck_interval = value
      break
    case '--health-timeout':
      result.dockerParams.healthcheck_timeout = value
      break
    case '--health-retries':
      result.dockerParams.healthcheck_retries = value
      break
    case '--health-start-period':
      result.dockerParams.healthcheck_start_period = value
      break

    // 更新策略
    case '--update-parallelism':
      result.dockerParams.update_parallelism = value
      break
    case '--update-delay':
      result.dockerParams.update_delay = value
      break
    case '--update-monitor':
      result.dockerParams.update_monitor = value
      break
    case '--update-failure-action':
      result.dockerParams.update_failure_action = value
      break
    case '--update-order':
      result.dockerParams.update_order = value
      break

    // 回滚策略
    case '--rollback-parallelism':
      result.dockerParams.rollback_parallelism = value
      break
    case '--rollback-delay':
      result.dockerParams.rollback_delay = value
      break
    case '--rollback-monitor':
      result.dockerParams.rollback_monitor = value
      break
    case '--rollback-failure-action':
      result.dockerParams.rollback_failure_action = value
      break
    case '--rollback-order':
      result.dockerParams.rollback_order = value
      break

    // 停止优雅期
    case '--stop-grace-period':
      result.dockerParams['stop-grace-period'] = value
      break

    // 端点模式
    case '--endpoint-mode':
      result.dockerParams['endpoint-mode'] = value
      break

    // 每节点最大副本数
    case '--replicas-max-per-node':
      result.dockerParams['replicas-max-per-node'] = value
      break

    // 日志驱动
    case '--log-driver':
      result.dockerParams['log-driver'] = value
      break
    case '--log-opt':
      // 累加为对象序列化 JSON
      try {
        const cur = result.dockerParams['log-opts'] ? JSON.parse(result.dockerParams['log-opts']) : {}
        const eq = value.indexOf('=')
        if (eq > 0) {
          cur[value.substring(0, eq)] = value.substring(eq + 1)
        }
        result.dockerParams['log-opts'] = JSON.stringify(cur)
      } catch (_) {
        result.dockerParams['log-opts'] = value
      }
      break

    // 容器标签
    case '-l':
    case '--label':
    case '--container-label':
      multiValues['container-labels'].push(value)
      break

    // 节点约束
    case '--constraint':
      multiValues.constraints.push(value)
      break

    default:
      result.unknownFlags.push({ flag, value })
      break
  }
}
