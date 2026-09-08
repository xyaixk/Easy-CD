package com.easy.cd.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通过 SSH + Docker CLI 将实际副本状态同步到 replica_status。
 * 定时刷新和接口实时查询共用此服务，避免接口只返回过期缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicaStatusSyncService {

    private final EnvironmentMapper environmentMapper;

    private final ServiceMapper serviceMapper;

    private final ReplicaStatusMapper replicaStatusMapper;

    private final TransactionTemplate transactionTemplate;

    private final SshExecutor sshExecutor;

    private final NodeDiscoveryService nodeDiscoveryService;

    public void refreshAllReplicaStatus() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);
            if (environments.isEmpty()) return;

            for (Environment environment : environments) {
                try {
                    if (!"docker".equals(environment.getDeployType())) continue;

                    LambdaQueryWrapper<AppService> serviceQuery = new LambdaQueryWrapper<>();
                    serviceQuery.eq(AppService::getEnvironmentId, environment.getId());
                    List<AppService> services = serviceMapper.selectList(serviceQuery);
                    if (services.isEmpty()) continue;

                    syncReplicaStatus(environment, services);
                } catch (Exception e) {
                    log.error("刷新环境[{}]副本状态失败", environment.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("副本状态刷新任务执行失败", e);
        }
    }

    /**
     * 在读取副本接口前同步单个服务；同步失败时保留已有缓存。
     *
     * @return 是否成功获取并写入实际副本状态
     */
    public boolean refreshService(Long serviceId) {
        if (serviceId == null) return false;
        try {
            AppService service = serviceMapper.selectById(serviceId);
            if (service == null) {
                log.debug("跳过副本状态同步，服务不存在: serviceId={}", serviceId);
                return false;
            }

            Environment environment = environmentMapper.selectById(service.getEnvironmentId());
            if (environment == null) {
                log.warn("跳过副本状态同步，环境不存在: serviceId={}, environmentId={}",
                        serviceId, service.getEnvironmentId());
                return false;
            }
            if (!"docker".equals(environment.getDeployType())) {
                return false;
            }

            return syncReplicaStatus(environment, Collections.singletonList(service));
        } catch (Exception e) {
            log.warn("同步服务副本状态失败，保留原状态: serviceId={}", serviceId, e);
            return false;
        }
    }

    private boolean syncReplicaStatus(Environment environment, List<AppService> services) {
        try {
            Map<String, Object> config = JSON.parseObject(
                    environment.getConfig(), new TypeReference<Map<String, Object>>() {});
            List<SshHost> sshHosts = SshExecutor.parseSshHostsFromConfig(config);
            if (sshHosts.isEmpty()) return false;

            Map<String, AppService> serviceByName = new LinkedHashMap<>();
            for (AppService appService : services) {
                String serviceName = appService.getExternalServiceName() != null
                        ? appService.getExternalServiceName()
                        : appService.getName().toLowerCase();
                serviceByName.put(serviceName, appService);
            }
            if (serviceByName.isEmpty()) return false;

            // docker service ps 支持一次传多个服务；整个环境只执行一次。
            String psCmd = "docker service ps " + String.join(" ", serviceByName.keySet()) +
                    " --format '{\"ID\":\"{{.ID}}\",\"Name\":\"{{.Name}}\",\"Node\":\"{{.Node}}\"," +
                    "\"DesiredState\":\"{{.DesiredState}}\",\"CurrentState\":\"{{.CurrentState}}\"," +
                    "\"Error\":\"{{.Error}}\"}' --no-trunc";
            SshResult psResult = sshExecutor.executeCommandWithFailover(sshHosts, psCmd);
            if (!psResult.isSuccess()) {
                log.warn("环境[{}]批量副本状态查询失败: {}", environment.getName(), psResult.getStderr());
                return false;
            }

            List<TaskInfo> allTasks = parseTasks(psResult);
            for (TaskInfo task : allTasks) {
                task.serviceName = resolveTaskServiceName(task.name, serviceByName.keySet());
            }
            fillNodeIps(environment, allTasks);
            fillContainerIds(sshHosts, allTasks);

            Map<String, List<TaskInfo>> tasksByService = allTasks.stream()
                    .filter(t -> t.serviceName != null)
                    .collect(Collectors.groupingBy(t -> t.serviceName));

            for (Map.Entry<String, AppService> entry : serviceByName.entrySet()) {
                List<TaskInfo> tasks = tasksByService.getOrDefault(entry.getKey(), Collections.emptyList());
                List<TaskInfo> currentTasks = selectCurrentTasks(tasks);
                countRestarts(tasks, currentTasks);
                replaceReplicaStatus(entry.getValue(), entry.getKey(), currentTasks);
            }
            return true;
        } catch (Exception e) {
            log.error("同步环境[{}]副本状态失败", environment.getName(), e);
            return false;
        }
    }

    private String resolveTaskServiceName(String taskName, Set<String> serviceNames) {
        if (taskName == null) return null;
        String normalized = taskName;
        while (normalized.startsWith("_")) normalized = normalized.substring(1);
        String matched = null;
        for (String serviceName : serviceNames) {
            if ((normalized.equals(serviceName) || normalized.startsWith(serviceName + ".")) &&
                    (matched == null || serviceName.length() > matched.length())) {
                matched = serviceName;
            }
        }
        return matched;
    }

    private void replaceReplicaStatus(AppService appService, String serviceName, List<TaskInfo> tasks) {
        transactionTemplate.executeWithoutResult(status -> {
            LambdaQueryWrapper<ReplicaStatus> deleteQuery = new LambdaQueryWrapper<>();
            deleteQuery.eq(ReplicaStatus::getServiceId, appService.getId());
            replicaStatusMapper.delete(deleteQuery);

            for (TaskInfo task : tasks) {
                ReplicaStatus rs = new ReplicaStatus();
                rs.setServiceId(appService.getId());
                rs.setReplicaId(task.id);
                rs.setReplicaName(task.name);
                rs.setReplicaIndex(task.slot);
                rs.setPlatform("docker");
                rs.setStatus(extractStatus(task.currentState));
                rs.setNodeName(task.node);
                rs.setNodeIp(task.nodeIp);
                rs.setTaskId(task.id);
                rs.setTaskSlot(task.slot);
                rs.setContainerId(task.containerId);
                rs.setContainerIdShort(task.containerId);
                rs.setUptimeSeconds(parseUptimeFromCurrentState(task.currentState));
                rs.setErrorMessage(task.error != null && !task.error.isEmpty() ? task.error : null);
                rs.setRestartCount(task.restartCount);
                rs.setCreatedTime(LocalDateTime.now());
                rs.setUpdatedTime(LocalDateTime.now());
                replicaStatusMapper.insert(rs);
            }
        });
    }

    private List<TaskInfo> parseTasks(SshResult psResult) {
        List<TaskInfo> tasks = new ArrayList<>();
        if (!psResult.isSuccess() || !psResult.hasOutput()) return tasks;

        for (String line : psResult.getStdout().trim().split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                JSONObject json = JSON.parseObject(line.trim());
                TaskInfo task = new TaskInfo();
                task.id = json.getString("ID");
                task.name = json.getString("Name");
                task.node = json.getString("Node");
                task.desiredState = json.getString("DesiredState");
                task.currentState = json.getString("CurrentState");
                task.error = json.getString("Error");

                if (task.name != null && task.name.contains(".")) {
                    String[] parts = task.name.split("\\.");
                    if (parts.length >= 2) {
                        try { task.slot = Integer.parseInt(parts[1]); }
                        catch (NumberFormatException e) { task.slot = null; }
                    }
                }
                tasks.add(task);
            } catch (Exception e) {
                log.debug("解析 task JSON 失败: {}", line);
            }
        }
        return tasks;
    }

    /**
     * 根据 Swarm 节点名补齐节点 IP，供终端连接到容器实际所在节点。
     */
    private void fillNodeIps(Environment environment, List<TaskInfo> tasks) {
        if (tasks.isEmpty()) return;

        Map<String, String> nodeIps = new HashMap<>();
        for (NodeInfo node : nodeDiscoveryService.discover(environment)) {
            if (node == null || node.getIp() == null || node.getIp().trim().isEmpty()) continue;
            if (node.getHostname() != null && !node.getHostname().trim().isEmpty()) {
                nodeIps.put(node.getHostname().trim().toLowerCase(Locale.ROOT), node.getIp().trim());
            }
            if (node.getNodeId() != null && !node.getNodeId().trim().isEmpty()) {
                nodeIps.put(node.getNodeId().trim().toLowerCase(Locale.ROOT), node.getIp().trim());
            }
        }

        for (TaskInfo task : tasks) {
            if (task.node != null) {
                task.nodeIp = nodeIps.get(task.node.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * 批量获取 task 的容器 ID
     */
    private void fillContainerIds(List<SshHost> sshHosts, List<TaskInfo> tasks) {
        if (tasks.isEmpty()) return;
        // 只查询 Running 状态的 task
        List<TaskInfo> runningTasks = tasks.stream()
                .filter(t -> "Running".equalsIgnoreCase(t.desiredState))
                .collect(Collectors.toList());
        if (runningTasks.isEmpty()) return;

        // 拼接所有 task ID，一次性查询
        String taskIds = runningTasks.stream().map(t -> t.id).collect(Collectors.joining(" "));
        SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                "docker inspect --format '{{.ID}} {{.Status.ContainerStatus.ContainerID}}' " + taskIds + " 2>/dev/null");

        if (result.isSuccess() && result.hasOutput()) {
            Map<String, String> containerMap = new HashMap<>();
            for (String line : result.getStdout().trim().split("\n")) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2 && !parts[1].isEmpty()) {
                    containerMap.put(parts[0], parts[1].length() > 12 ? parts[1].substring(0, 12) : parts[1]);
                }
            }
            for (TaskInfo task : tasks) {
                task.containerId = containerMap.get(task.id);
            }
        }
    }

    private List<TaskInfo> selectCurrentTasks(List<TaskInfo> tasks) {
        if (tasks == null || tasks.isEmpty()) return Collections.emptyList();

        Map<String, TaskInfo> bestBySlot = new LinkedHashMap<>();
        for (TaskInfo task : tasks) {
            // Replicated 模式用 slot 号分组，Global 模式用 node 或 taskId 分组
            String key;
            if (task.slot != null) {
                key = "slot:" + task.slot;
            } else if (task.node != null && !task.node.isEmpty()) {
                key = "node:" + task.node;
            } else {
                key = "task:" + task.id;
            }

            TaskInfo existing = bestBySlot.get(key);
            if (existing == null) {
                bestBySlot.put(key, task);
            } else if ("Running".equalsIgnoreCase(task.desiredState) &&
                    !"Running".equalsIgnoreCase(existing.desiredState)) {
                bestBySlot.put(key, task);
            }
        }

        return bestBySlot.values().stream()
                .sorted(Comparator.comparing(t -> t.slot == null ? Integer.MAX_VALUE : t.slot))
                .collect(Collectors.toList());
    }

    /**
     * 统计重启次数：同一 slot/node 下的历史 task 总数（不含当前自己）
     */
    private void countRestarts(List<TaskInfo> allTasks, List<TaskInfo> currentTasks) {
        // 构建分组 key 到总 task 数的映射
        Map<String, Integer> slotTaskCount = new HashMap<>();
        for (TaskInfo task : allTasks) {
            String key = getGroupKey(task);
            slotTaskCount.merge(key, 1, Integer::sum);
        }
        // 当前 task 的重启次数 = 同组总数 - 1
        for (TaskInfo task : currentTasks) {
            String key = getGroupKey(task);
            int total = slotTaskCount.getOrDefault(key, 1);
            task.restartCount = Math.max(0, total - 1);
        }
    }

    private String getGroupKey(TaskInfo task) {
        if (task.slot != null) {
            return "slot:" + task.slot;
        } else if (task.node != null && !task.node.isEmpty()) {
            return "node:" + task.node;
        } else {
            return "task:" + task.id;
        }
    }

    private String extractStatus(String currentState) {
        if (currentState == null) return "unknown";
        String lower = currentState.toLowerCase().trim();
        if (lower.startsWith("running")) return "running";
        if (lower.startsWith("starting")) return "starting";
        if (lower.startsWith("preparing")) return "preparing";
        if (lower.startsWith("complete")) return "complete";
        if (lower.startsWith("failed")) return "failed";
        if (lower.startsWith("shutdown")) return "shutdown";
        return "unknown";
    }

    private Long parseUptimeFromCurrentState(String currentState) {
        if (currentState == null || !currentState.toLowerCase().startsWith("running")) return null;
        try {
            String timePart = currentState.substring("Running".length()).trim();
            if (timePart.endsWith(" ago")) timePart = timePart.substring(0, timePart.length() - 4).trim();
            if (timePart.contains("second")) return parseDurationNumber(timePart);
            if (timePart.contains("minute")) return parseDurationNumber(timePart) * 60;
            if (timePart.contains("hour")) return parseDurationNumber(timePart) * 3600;
            if (timePart.contains("day")) return parseDurationNumber(timePart) * 86400;
        } catch (Exception ignored) {}
        return null;
    }

    private long parseDurationNumber(String str) {
        if (str.contains("about") || str.contains("an") || str.contains("a ")) return 1;
        try { return Long.parseLong(str.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 1; }
    }

    private static class TaskInfo {
        String id, name, node, nodeIp, desiredState, currentState;
        String serviceName;
        String containerId;
        String error;
        Integer slot;
        int restartCount;
    }
}
