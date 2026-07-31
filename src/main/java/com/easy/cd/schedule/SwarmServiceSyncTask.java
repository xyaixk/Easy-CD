package com.easy.cd.schedule;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.service.ServiceGroupService;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Swarm 服务双向同步任务
 * 
 * 每 30 秒将 Swarm 实际状态同步到 app_service 表：
 * - 只加不删：Swarm 中有但 DB 中没有的服务自动新增
 * - 漂移检测：Swarm 中服务属性与 DB 不一致时更新 DB
 * - dockerParams 反向提取：从 docker service inspect 提取运行参数写入 DB
 * - 防抖：平台操作后 30 秒内跳过同步
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SwarmServiceSyncTask {

    @Resource
    private EnvironmentMapper environmentMapper;

    @Resource
    private ServiceMapper serviceMapper;

    @Resource
    private ServiceGroupService serviceGroupService;

    @Resource
    private SshExecutor sshExecutor;

    /** 平台操作后跳过同步的时间窗口（秒） */
    private static final int DEBOUNCE_SECONDS = 30;

    /** Docker 内置参数 key，用于反向提取时区分环境变量 */
    private static final Set<String> BUILT_IN_KEYS = new HashSet<>(Arrays.asList(
            "replicas", "cpus", "memory", "memory-reservation", "restart",
            "restart-max-attempts", "restart-delay", "publish", "network",
            "healthcheck", "healthcheck_interval", "healthcheck_timeout",
            "healthcheck_retries", "healthcheck_start_period", "update_parallelism",
            "update_delay", "update_monitor", "update_failure_action", "update_order",
            "mounts", "log-driver", "log-opts", "container-labels", "container-label"
    ));

    @Scheduled(fixedRate = 30000)
    public void syncSwarmServices() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);
            for (Environment environment : environments) {
                try {
                    if (!"docker".equals(environment.getDeployType())) continue;
                    syncEnvironment(environment);
                } catch (Exception e) {
                    log.error("同步环境[{}]Swarm服务失败", environment.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("Swarm服务同步任务执行失败", e);
        }
    }

    private void syncEnvironment(Environment environment) {
        Map<String, Object> config = JSON.parseObject(
                environment.getConfig(), new TypeReference<Map<String, Object>>() {});
        List<SshHost> sshHosts = SshExecutor.parseSshHostsFromConfig(config);
        if (sshHosts.isEmpty()) return;

        // Step 1: 获取 Swarm 中所有服务列表
        SshResult lsResult = sshExecutor.executeCommandWithFailover(sshHosts,
                "docker service ls --format '{{json .}}'");
        if (!lsResult.isSuccess() || !lsResult.hasOutput()) return;

        List<SwarmServiceInfo> swarmServices = parseServiceList(lsResult);
        if (swarmServices.isEmpty()) return;

        // Step 2: 获取 DB 中该环境的所有服务
        LambdaQueryWrapper<AppService> query = new LambdaQueryWrapper<>();
        query.eq(AppService::getEnvironmentId, environment.getId());
        List<AppService> dbServices = serviceMapper.selectList(query);

        Map<String, AppService> dbServiceMap = dbServices.stream()
                .filter(s -> s.getExternalServiceName() != null)
                .collect(Collectors.toMap(AppService::getExternalServiceName, s -> s, (a, b) -> a));

        // Step 3: 遍历 Swarm 服务，只加不删
        int addedCount = 0;
        int updatedCount = 0;

        for (SwarmServiceInfo swarmSvc : swarmServices) {
            try {
                // 获取详细 inspect 信息
                SshResult inspectResult = sshExecutor.executeCommandWithFailover(sshHosts,
                        "docker service inspect " + swarmSvc.name + " --format '{{json .}}'");

                JSONObject inspectJson = null;
                if (inspectResult.isSuccess() && inspectResult.hasOutput()) {
                    String output = inspectResult.getStdout().trim();
                    // docker service inspect 可能返回数组
                    if (output.startsWith("[")) {
                        JSONArray arr = JSON.parseArray(output);
                        if (!arr.isEmpty()) inspectJson = arr.getJSONObject(0);
                    } else {
                        inspectJson = JSON.parseObject(output);
                    }
                }

                AppService dbSvc = dbServiceMap.get(swarmSvc.name);

                if (dbSvc == null) {
                    // === 新增 ===
                    AppService newSvc = new AppService();
                    newSvc.setName(swarmSvc.name);
                    newSvc.setEnvironmentId(environment.getId());
                    newSvc.setGroupId(null);
                    newSvc.setSortOrder(serviceGroupService.nextUngroupedSortOrder(environment.getId()));
                    newSvc.setExternalServiceName(swarmSvc.name);
                    newSvc.setExternalServiceId(swarmSvc.id);
                    newSvc.setDockerImage(swarmSvc.image);
                    newSvc.setVersion(extractTag(swarmSvc.image));
                    newSvc.setReplicas(swarmSvc.replicas);
                    newSvc.setServiceMode(swarmSvc.mode);
                    newSvc.setDockerParams(buildDockerParamsFromInspect(inspectJson));
                    newSvc.setCreatedTime(LocalDateTime.now());
                    newSvc.setUpdatedTime(LocalDateTime.now());
                    serviceMapper.insert(newSvc);
                    addedCount++;
                    log.info("自动发现新服务并注册: {}", swarmSvc.name);
                } else {
                    // === 检查防抖 ===
                    if (dbSvc.getLastPlatformUpdateTime() != null) {
                        long secondsSinceUpdate = java.time.Duration.between(
                                dbSvc.getLastPlatformUpdateTime(), LocalDateTime.now()).getSeconds();
                        if (secondsSinceUpdate < DEBOUNCE_SECONDS) {
                            log.debug("服务[{}]平台刚操作过({}秒前)，跳过同步", dbSvc.getName(), secondsSinceUpdate);
                            continue;
                        }
                    }

                    // === 更新漂移字段 ===
                    boolean changed = false;
                    List<String> changes = new ArrayList<>();

                    if (swarmSvc.replicas != null && !swarmSvc.replicas.equals(dbSvc.getReplicas())) {
                        changes.add("replicas: " + dbSvc.getReplicas() + " → " + swarmSvc.replicas);
                        dbSvc.setReplicas(swarmSvc.replicas);
                        changed = true;
                    }
                    if (swarmSvc.image != null && !swarmSvc.image.equals(dbSvc.getDockerImage())) {
                        changes.add("dockerImage: " + dbSvc.getDockerImage() + " → " + swarmSvc.image);
                        dbSvc.setDockerImage(swarmSvc.image);
                        dbSvc.setVersion(extractTag(swarmSvc.image));
                        changed = true;
                    }
                    if (swarmSvc.mode != null && !swarmSvc.mode.equals(dbSvc.getServiceMode())) {
                        changes.add("serviceMode: " + dbSvc.getServiceMode() + " → " + swarmSvc.mode);
                        dbSvc.setServiceMode(swarmSvc.mode);
                        changed = true;
                    }

                    // dockerParams 反向提取对比
                    String swarmParams = buildDockerParamsFromInspect(inspectJson);
                    if (!Objects.equals(dbSvc.getDockerParams(), swarmParams)) {
                        changes.addAll(diffParamsForLog(dbSvc.getDockerParams(), swarmParams));
                        dbSvc.setDockerParams(swarmParams);
                        changed = true;
                    }

                    if (changed) {
                        dbSvc.setUpdatedTime(LocalDateTime.now());
                        serviceMapper.updateById(dbSvc);
                        updatedCount++;
                        log.info("检测到服务配置漂移并已同步: {} | 变更({}项):\n  - {}",
                                dbSvc.getName(), changes.size(), String.join("\n  - ", changes));
                    }
                }
            } catch (Exception e) {
                log.debug("同步服务[{}]失败", swarmSvc.name, e);
            }
        }

        if (addedCount > 0 || updatedCount > 0) {
            log.info("环境[{}] Swarm同步完成: 新增{}个, 更新{}个",
                    environment.getName(), addedCount, updatedCount);
        }
    }

    // ======================== dockerParams 反向提取 ========================

    /**
     * 从 docker service inspect 结果反向构建 dockerParams JSON
     * 原则：尽量保留 Docker CLI 参数原始值，不做不必要的语义映射
     */
    private String buildDockerParamsFromInspect(JSONObject inspectJson) {
        if (inspectJson == null) return null;

        Map<String, Object> params = new LinkedHashMap<>();

        try {
            JSONObject spec = inspectJson.getJSONObject("Spec");
            if (spec == null) return null;

            JSONObject taskTemplate = spec.getJSONObject("TaskTemplate");
            if (taskTemplate == null) return null;

            JSONObject containerSpec = taskTemplate.getJSONObject("ContainerSpec");

            // 1. 资源限制
            extractResources(taskTemplate, params);

            // 2. 重启策略（直存原始值）
            extractRestartPolicy(taskTemplate, params);

            // 3. 端口映射（支持多端口）
            extractPorts(spec, params);

            // 4. 健康检查
            extractHealthcheck(containerSpec, params);

            // 5. 更新策略
            extractUpdateConfig(spec, params);

            // 6. 挂载卷
            extractMounts(containerSpec, params);

            // 7. 日志驱动
            extractLogDriver(taskTemplate, params);

            // 8. 容器标签
            extractContainerLabels(containerSpec, params);

            // 9. 网络
            extractNetworks(taskTemplate, params);

            // 10. 环境变量（放最后，过滤内置 key）
            extractEnvVars(containerSpec, params);

            // 11. 启动命令（镜像后追加的 args）
            extractCommand(containerSpec, params);

        } catch (Exception e) {
            log.debug("反向提取 dockerParams 失败", e);
        }

        return params.isEmpty() ? null : JSON.toJSONString(params);
    }

    private void extractResources(JSONObject taskTemplate, Map<String, Object> params) {
        JSONObject resources = taskTemplate.getJSONObject("Resources");
        if (resources == null) return;

        JSONObject limits = resources.getJSONObject("Limits");
        if (limits != null) {
            Long nanoCpus = limits.getLong("NanoCPUs");
            if (nanoCpus != null && nanoCpus > 0) {
                params.put("cpus", String.format("%.2f", nanoCpus / 1_000_000_000.0));
            }
            Long memBytes = limits.getLong("MemoryBytes");
            if (memBytes != null && memBytes > 0) {
                params.put("memory", formatMemory(memBytes));
            }
        }
        JSONObject reservations = resources.getJSONObject("Reservations");
        if (reservations != null) {
            Long memReserv = reservations.getLong("MemoryBytes");
            if (memReserv != null && memReserv > 0) {
                params.put("memory-reservation", formatMemory(memReserv));
            }
        }
    }

    private void extractRestartPolicy(JSONObject taskTemplate, Map<String, Object> params) {
        JSONObject restartPolicy = taskTemplate.getJSONObject("RestartPolicy");
        if (restartPolicy == null) return;

        String condition = restartPolicy.getString("Condition");
        if (condition != null) {
            params.put("restart", condition); // 直存原始值: any / none / on-failure
        }
        Long maxAttempts = restartPolicy.getLong("MaxAttempts");
        if (maxAttempts != null && maxAttempts > 0) {
            params.put("restart-max-attempts", maxAttempts);
        }
        Long restartDelay = restartPolicy.getLong("Delay");
        if (restartDelay != null && restartDelay > 0) {
            params.put("restart-delay", nanoToSeconds(restartDelay) + "s");
        }
    }

    private void extractPorts(JSONObject spec, Map<String, Object> params) {
        JSONObject endpointSpec = spec.getJSONObject("EndpointSpec");
        if (endpointSpec == null) return;

        JSONArray ports = endpointSpec.getJSONArray("Ports");
        if (ports == null || ports.isEmpty()) return;

        List<String> portMappings = new ArrayList<>();
        for (int i = 0; i < ports.size(); i++) {
            JSONObject port = ports.getJSONObject(i);
            Integer published = port.getInteger("PublishedPort");
            Integer target = port.getInteger("TargetPort");
            String protocol = port.getString("Protocol");
            if (published != null && target != null) {
                String mapping = published + ":" + target;
                if (protocol != null && !"tcp".equalsIgnoreCase(protocol)) {
                    mapping += "/" + protocol;
                }
                portMappings.add(mapping);
            }
        }
        if (!portMappings.isEmpty()) {
            params.put("publish", String.join("\n", portMappings));
        }
    }

    private void extractHealthcheck(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONObject healthcheck = containerSpec.getJSONObject("Healthcheck");
        if (healthcheck == null) return;

        JSONArray test = healthcheck.getJSONArray("Test");
        if (test != null && test.size() > 1) {
            params.put("healthcheck", test.getString(1));
        }
        Long interval = healthcheck.getLong("Interval");
        if (interval != null && interval > 0) {
            params.put("healthcheck_interval", nanoToSeconds(interval) + "s");
        }
        Long timeout = healthcheck.getLong("Timeout");
        if (timeout != null && timeout > 0) {
            params.put("healthcheck_timeout", nanoToSeconds(timeout) + "s");
        }
        Integer retries = healthcheck.getInteger("Retries");
        if (retries != null) {
            params.put("healthcheck_retries", retries);
        }
        Long startPeriod = healthcheck.getLong("StartPeriod");
        if (startPeriod != null && startPeriod > 0) {
            params.put("healthcheck_start_period", nanoToSeconds(startPeriod) + "s");
        }
    }

    private void extractUpdateConfig(JSONObject spec, Map<String, Object> params) {
        JSONObject updateConfig = spec.getJSONObject("UpdateConfig");
        if (updateConfig == null) return;

        Integer parallelism = updateConfig.getInteger("Parallelism");
        if (parallelism != null) {
            params.put("update_parallelism", parallelism);
        }
        Long delay = updateConfig.getLong("Delay");
        if (delay != null && delay > 0) {
            params.put("update_delay", nanoToSeconds(delay) + "s");
        }
        String failureAction = updateConfig.getString("FailureAction");
        if (failureAction != null) {
            params.put("update_failure_action", failureAction);
        }
        String order = updateConfig.getString("Order");
        if (order != null) {
            params.put("update_order", order);
        }
    }

    private void extractMounts(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONArray mounts = containerSpec.getJSONArray("Mounts");
        if (mounts == null || mounts.isEmpty()) return;

        List<String> mountList = new ArrayList<>();
        for (int i = 0; i < mounts.size(); i++) {
            JSONObject mount = mounts.getJSONObject(i);
            String type = mount.getString("Type");       // bind / volume
            String source = mount.getString("Source");
            String target = mount.getString("Target");
            Boolean readOnly = mount.getBoolean("ReadOnly");

            if (source != null && target != null) {
                String entry = source + ":" + target;
                if (Boolean.TRUE.equals(readOnly)) entry += ":ro";
                mountList.add(entry);
            }
        }
        if (!mountList.isEmpty()) {
            params.put("mounts", String.join("\n", mountList));
        }
    }

    private void extractLogDriver(JSONObject taskTemplate, Map<String, Object> params) {
        JSONObject logDriver = taskTemplate.getJSONObject("LogDriver");
        if (logDriver == null) return;

        String name = logDriver.getString("Name");
        if (name != null) {
            params.put("log-driver", name);
        }
        JSONObject options = logDriver.getJSONObject("Options");
        if (options != null && !options.isEmpty()) {
            params.put("log-opts", options.toJSONString());
        }
    }

    private void extractContainerLabels(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONObject labels = containerSpec.getJSONObject("Labels");
        if (labels == null || labels.isEmpty()) return;

        List<String> labelList = new ArrayList<>();
        for (Map.Entry<String, Object> entry : labels.entrySet()) {
            labelList.add(entry.getKey() + "=" + entry.getValue());
        }
        if (!labelList.isEmpty()) {
            params.put("container-labels", String.join("\n", labelList));
        }
    }

    private void extractNetworks(JSONObject taskTemplate, Map<String, Object> params) {
        JSONArray networks = taskTemplate.getJSONArray("Networks");
        if (networks == null || networks.isEmpty()) return;

        // 取第一个网络的 Target（网络 ID）或别名
        for (int i = 0; i < networks.size(); i++) {
            JSONObject net = networks.getJSONObject(i);
            String target = net.getString("Target");
            JSONArray aliases = net.getJSONArray("Aliases");
            if (target != null) {
                // 网络信息存在参数中，不做覆盖
                if (!params.containsKey("network")) {
                    params.put("network", target);
                }
            }
        }
    }

    private void extractEnvVars(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONArray envArr = containerSpec.getJSONArray("Env");
        if (envArr == null) return;

        for (int i = 0; i < envArr.size(); i++) {
            String env = envArr.getString(i);
            int eqIdx = env.indexOf('=');
            if (eqIdx > 0) {
                String key = env.substring(0, eqIdx);
                String val = env.substring(eqIdx + 1);
                if (!BUILT_IN_KEYS.contains(key.toLowerCase()) && !params.containsKey(key)) {
                    params.put(key, val);
                }
            }
        }
    }

    /** 提取镜像后的启动命令参数 (ContainerSpec.Args)，可选拼接 ContainerSpec.Command 作为前缀 */
    private void extractCommand(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONArray args = containerSpec.getJSONArray("Args");
        if (args == null || args.isEmpty()) return;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.getString(i);
            if (a != null) tokens.add(a);
        }
        if (!tokens.isEmpty()) {
            params.put("command", String.join(" ", tokens));
        }
    }

    // ======================== 辅助方法 ========================

    /**
     * 对比两份 dockerParams JSON，返回可读化的变更行。
     * 新增："[dockerParams] key: (none) → value"
     * 删除："[dockerParams] key: value → (removed)"
     * 修改："[dockerParams] key: old → new"
     * 长值自动截断，避免日志行过长。
     */
    private List<String> diffParamsForLog(String oldJson, String newJson) {
        List<String> lines = new ArrayList<>();
        Map<String, Object> oldMap = parseParams(oldJson);
        Map<String, Object> newMap = parseParams(newJson);

        // 保持新配置的顺序；旧有新无的 key 追加在后
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(newMap.keySet());
        allKeys.addAll(oldMap.keySet());

        for (String key : allKeys) {
            Object oldVal = oldMap.get(key);
            Object newVal = newMap.get(key);
            if (Objects.equals(oldVal, newVal)) continue;
            String oldStr = oldVal == null ? "(none)" : truncate(oldVal.toString());
            String newStr = newVal == null ? "(removed)" : truncate(newVal.toString());
            lines.add("[dockerParams] " + key + ": " + oldStr + " → " + newStr);
        }
        return lines;
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        String oneLine = s.replace("\n", "\\n").replace("\r", "");
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }

    private List<SwarmServiceInfo> parseServiceList(SshResult lsResult) {
        List<SwarmServiceInfo> services = new ArrayList<>();
        for (String line : lsResult.getStdout().trim().split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                JSONObject json = JSON.parseObject(line.trim());
                SwarmServiceInfo info = new SwarmServiceInfo();
                info.id = json.getString("ID");
                info.name = json.getString("Name");
                info.image = json.getString("Image");
                info.mode = json.getString("Mode");

                // Replicas 格式: "3/3" 或 "global" 或 "1/1 (3/3 completed)"
                String replicasStr = json.getString("Replicas");
                if (replicasStr != null && replicasStr.contains("/")) {
                    String[] parts = replicasStr.split("/");
                    // 取“/”前的部分作为实际副本数（global 模式也适用）
                    String desiredPart = parts[1].trim().split("\\s")[0];
                    info.replicas = Integer.parseInt(desiredPart);
                } else if ("replicated".equalsIgnoreCase(info.mode)) {
                    info.replicas = 1;
                } else {
                    // global 模式且无副本信息，设为 0 表示由节点数决定
                    info.replicas = 0;
                }

                services.add(info);
            } catch (Exception e) {
                log.debug("解析服务列表 JSON 失败: {}", line);
            }
        }
        return services;
    }

    private String extractTag(String image) {
        if (image == null) return "latest";
        if (image.contains("@sha256:")) {
            image = image.substring(0, image.indexOf("@"));
        }
        if (image.contains(":")) {
            int lastColon = image.lastIndexOf(":");
            String tag = image.substring(lastColon + 1);
            if (!tag.contains("/")) return tag;
        }
        return "latest";
    }

    private String formatMemory(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            double gb = bytes / (1024.0 * 1024 * 1024);
            return gb == (long) gb ? (long) gb + "G" : String.format("%.1fG", gb);
        } else if (bytes >= 1024L * 1024) {
            double mb = bytes / (1024.0 * 1024);
            return mb == (long) mb ? (long) mb + "M" : String.format("%.1fM", mb);
        }
        return bytes + "B";
    }

    private long nanoToSeconds(long nanos) {
        return nanos / 1_000_000_000L;
    }

    // 内部数据结构
    private static class SwarmServiceInfo {
        String id;
        String name;
        String image;
        String mode;
        Integer replicas;
    }
}
