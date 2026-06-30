package com.easy.cd.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Task;
import com.github.dockerjava.api.model.TaskState;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 副本状态定时刷新任务
 * 每 3 秒同步一次副本状态信息，不采集监控指标
 */
@Slf4j
@Component
public class ReplicaStatusRefreshTask {

    @Resource
    private EnvironmentMapper environmentMapper;

    @Resource
    private ServiceMapper serviceMapper;

    @Resource
    private ReplicaStatusMapper replicaStatusMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Scheduled(fixedRate = 3000)
    public void refreshReplicaStatus() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);

            if (environments.isEmpty()) {
                return;
            }

            for (Environment environment : environments) {
                try {
                    // 只处理 Docker 环境
                    if (!"docker".equals(environment.getDeployType())) {
                        continue;
                    }

                    LambdaQueryWrapper<AppService> serviceQuery = new LambdaQueryWrapper<>();
                    serviceQuery.eq(AppService::getEnvironmentId, environment.getId());
                    List<AppService> services = serviceMapper.selectList(serviceQuery);

                    if (services.isEmpty()) {
                        continue;
                    }

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
     * 同步副本状态
     */
    private void syncReplicaStatus(Environment environment, List<AppService> services) {
        try {
            Map<String, Object> config = com.alibaba.fastjson.JSON.parseObject(
                environment.getConfig(),
                new com.alibaba.fastjson.TypeReference<Map<String, Object>>() {}
            );
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");

            if (managerHosts == null || managerHosts.isEmpty()) {
                log.debug("环境[{}]的 Swarm Manager 地址未配置", environment.getName());
                return;
            }

            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);

            int successCount = 0;
            int failCount = 0;
            for (AppService appService : services) {
                try {
                    String serviceName = appService.getExternalServiceName() != null
                        ? appService.getExternalServiceName()
                        : buildServiceName(appService.getName(), environment.getName());

                    List<Task> tasks = dockerClient.listTasksCmd()
                        .withServiceFilter(serviceName)
                        .exec();

                    replaceReplicaStatus(dockerClient, appService, serviceName, selectCurrentTasks(tasks));
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    log.debug("同步服务[{}]副本状态失败", appService.getName(), e);
                }
            }

            if (failCount > 0) {
                log.warn("环境[{}]副本状态同步完成：成功{}个，失败{}个",
                    environment.getName(), successCount, failCount);
            }
        } catch (Exception e) {
            log.error("同步环境[{}]副本状态失败", environment.getName(), e);
        }
    }

    /**
     * 用单个事务替换某个服务的全部副本状态，避免删完后中途失败导致数据不完整
     */
    private void replaceReplicaStatus(DockerClient dockerClient, AppService appService, String serviceName, List<Task> tasks) {
        transactionTemplate.executeWithoutResult(status -> {
            LambdaQueryWrapper<ReplicaStatus> deleteQuery = new LambdaQueryWrapper<>();
            deleteQuery.eq(ReplicaStatus::getServiceId, appService.getId());
            int deletedCount = replicaStatusMapper.delete(deleteQuery);
            if (deletedCount > 0) {
                log.debug("清理服务[{}]的 {} 条旧副本记录", appService.getName(), deletedCount);
            }

            for (Task task : tasks) {
                syncSingleReplicaStatus(dockerClient, appService.getId(), serviceName, task);
            }
        });
    }

    /**
     * 同步单个副本的状态
     */
    private void syncSingleReplicaStatus(DockerClient dockerClient, Long serviceId, String serviceName, Task task) {
        String taskId = task.getId();
        TaskState taskState = task.getStatus().getState();
        String nodeId = task.getNodeId();
        Integer slot = task.getSlot();

        String replicaName = serviceName + "." + (slot != null ? slot : "0");

        String containerId = null;
        if (task.getStatus().getContainerStatus() != null) {
            containerId = task.getStatus().getContainerStatus().getContainerID();
        }

        String nodeHostname = nodeId;
        String nodeIp = null;

        if (nodeId != null) {
            try {
                List<com.github.dockerjava.api.model.SwarmNode> nodes = dockerClient.listSwarmNodesCmd().exec();
                for (com.github.dockerjava.api.model.SwarmNode node : nodes) {
                    if (nodeId.equals(node.getId())) {
                        if (node.getDescription() != null && node.getDescription().getHostname() != null) {
                            nodeHostname = node.getDescription().getHostname();
                        }
                        if (node.getStatus() != null && node.getStatus().getAddress() != null) {
                            nodeIp = node.getStatus().getAddress();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("获取节点[{}]信息失败: {}", nodeId, e.getMessage());
            }
        }

        Long uptimeSeconds = null;
        if (taskState == TaskState.RUNNING && task.getStatus().getTimestamp() != null) {
            try {
                String timestamp = task.getStatus().getTimestamp();
                uptimeSeconds = parseDockerTimestamp(timestamp);
                log.debug("Task[{}] status={}, timestamp={}, uptimeSeconds={}",
                    taskId, taskState, timestamp, uptimeSeconds);
            } catch (Exception e) {
                log.debug("计算运行时间失败: {}", e.getMessage());
            }
        }

        ReplicaStatus replicaStatus = new ReplicaStatus();
        replicaStatus.setServiceId(serviceId);
        replicaStatus.setReplicaId(taskId);
        replicaStatus.setReplicaName(replicaName);
        replicaStatus.setReplicaIndex(slot);
        replicaStatus.setPlatform("docker");
        replicaStatus.setNodeName(nodeHostname);
        replicaStatus.setNodeIp(nodeIp);
        replicaStatus.setStatus(taskState.name().toLowerCase());
        replicaStatus.setContainerId(containerId);
        replicaStatus.setContainerIdShort(containerId != null && containerId.length() > 12
            ? containerId.substring(0, 12) : containerId);
        replicaStatus.setTaskId(taskId);
        replicaStatus.setTaskSlot(slot);
        replicaStatus.setUptimeSeconds(uptimeSeconds);
        replicaStatus.setRestartCount(0);
        replicaStatus.setCreatedTime(LocalDateTime.now());
        replicaStatus.setUpdatedTime(LocalDateTime.now());
        replicaStatusMapper.insert(replicaStatus);
    }

    /**
     * Docker Swarm 会返回同一 slot 的历史 task，这里只保留当前应展示的一条。
     */
    private List<Task> selectCurrentTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Task> bestTaskByKey = new LinkedHashMap<>();
        for (Task task : tasks) {
            String key = buildTaskGroupKey(task);
            Task existing = bestTaskByKey.get(key);
            if (existing == null || compareTask(task, existing) > 0) {
                bestTaskByKey.put(key, task);
            }
        }

        return bestTaskByKey.values().stream()
            .sorted(Comparator.comparing(task -> task.getSlot() == null ? Integer.MAX_VALUE : task.getSlot()))
            .collect(Collectors.toList());
    }

    private String buildTaskGroupKey(Task task) {
        Integer slot = task.getSlot();
        return slot != null ? "slot:" + slot : "task:" + task.getId();
    }

    private int compareTask(Task left, Task right) {
        return parseTaskTimestamp(left).compareTo(parseTaskTimestamp(right));
    }

    private Instant parseTaskTimestamp(Task task) {
        try {
            if (task == null || task.getStatus() == null || task.getStatus().getTimestamp() == null) {
                return Instant.EPOCH;
            }

            String timestamp = task.getStatus().getTimestamp();
            if (timestamp.contains(".")) {
                int dotIndex = timestamp.indexOf('.');
                int zIndex = timestamp.indexOf('Z');
                if (zIndex > dotIndex) {
                    String nanos = timestamp.substring(dotIndex + 1, zIndex);
                    if (nanos.length() > 6) {
                        nanos = nanos.substring(0, 6);
                    }
                    timestamp = timestamp.substring(0, dotIndex + 1) + nanos + "Z";
                }
            }
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    /**
     * 创建 Docker 客户端，支持故障转移
     */
    private DockerClient createDockerClientWithFailover(List<String> managerHosts) {
        List<String> shuffledHosts = new ArrayList<>(managerHosts);
        Collections.shuffle(shuffledHosts);

        Exception lastException = null;

        for (String host : shuffledHosts) {
            try {
                DockerClient client = createDockerClient(host);
                client.pingCmd().exec();
                return client;
            } catch (Exception e) {
                log.debug("连接到 {} 失败: {}", host, e.getMessage());
                lastException = e;
            }
        }

        throw new RuntimeException(
            "无法连接到任何 Swarm Manager 节点，已尝试: " + managerHosts,
            lastException
        );
    }

    /**
     * 创建 Docker 客户端
     */
    private DockerClient createDockerClient(String dockerHost) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(dockerHost)
            .build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 构建服务名称，直接使用服务名，不添加环境前缀
     */
    private String buildServiceName(String serviceName, String environmentName) {
        return serviceName.toLowerCase();
    }

    /**
     * 解析 Docker 时间戳并计算运行时间
     */
    private Long parseDockerTimestamp(String timestamp) {
        try {
            if (timestamp == null || timestamp.isEmpty()) {
                return null;
            }

            String processedTimestamp = timestamp;
            if (timestamp.contains(".")) {
                int dotIndex = timestamp.indexOf('.');
                int zIndex = timestamp.indexOf('Z');
                if (zIndex > dotIndex) {
                    String nanos = timestamp.substring(dotIndex + 1, zIndex);
                    if (nanos.length() > 6) {
                        nanos = nanos.substring(0, 6);
                    }
                    processedTimestamp = timestamp.substring(0, dotIndex + 1) + nanos + "Z";
                }
            }

            java.time.Instant startTime = java.time.Instant.parse(processedTimestamp);
            java.time.Instant now = java.time.Instant.now();
            long seconds = java.time.Duration.between(startTime, now).getSeconds();

            return seconds > 0 ? seconds : 0;
        } catch (Exception e) {
            log.debug("解析时间戳[{}]失败: {}", timestamp, e.getMessage());
            return null;
        }
    }
}
