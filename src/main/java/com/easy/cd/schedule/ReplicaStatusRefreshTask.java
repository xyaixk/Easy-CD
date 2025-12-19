package com.easy.cd.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.deploy.DeployService;
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

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 副本状态定时刷新任务
 * 每5秒同步一次副本的状态信息（不收集监控指标）
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
    private DeployService deployService;
    
    @Scheduled(fixedRate = 3000)
    public void refreshReplicaStatus() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);
            
            if (environments.isEmpty()) {
                return;
            }
            
            for (Environment environment : environments) {
                try {
                    // 只处理Docker环境
                    if (!"docker".equals(environment.getDeployType())) {
                        continue;
                    }
                    
                    LambdaQueryWrapper<AppService> serviceQuery = new LambdaQueryWrapper<>();
                    serviceQuery.eq(AppService::getEnvironmentId, environment.getId());
                    List<AppService> services = serviceMapper.selectList(serviceQuery);
                    
                    if (services.isEmpty()) {
                        continue;
                    }
                    
                    // 同步副本状态
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
            // 解析环境配置
            Map<String, Object> config = com.alibaba.fastjson.JSON.parseObject(
                environment.getConfig(), 
                new com.alibaba.fastjson.TypeReference<Map<String, Object>>() {}
            );
            List<String> managerHosts = (List<String>) config.get("swarmManagerHosts");
            
            if (managerHosts == null || managerHosts.isEmpty()) {
                log.debug("环境[{}]的Swarm Manager地址未配置", environment.getName());
                return;
            }
            
            // 连接到Swarm Manager
            DockerClient dockerClient = createDockerClientWithFailover(managerHosts);
            
            // 遍历每个服务，同步其副本状态
            for (AppService appService : services) {
                try {
                    String serviceName = appService.getExternalServiceName() != null 
                        ? appService.getExternalServiceName()
                        : buildServiceName(appService.getName(), environment.getName());
                    
                    // 查询服务的所有任务（Task）
                    List<Task> tasks = dockerClient.listTasksCmd()
                        .withServiceFilter(serviceName)
                        .exec();
                    
                    // 同步每个Task的状态（包括running和shutdown）
                    for (Task task : tasks) {
                        syncSingleReplicaStatus(dockerClient, appService.getId(), serviceName, task);
                    }
                    
                } catch (Exception e) {
                    log.error("同步服务[{}]副本状态失败", appService.getName(), e);
                }
            }
            
        } catch (Exception e) {
            log.error("同步环境[{}]副本状态失败", environment.getName(), e);
        }
    }
    
    /**
     * 同步单个副本的状态
     */
    private void syncSingleReplicaStatus(DockerClient dockerClient, Long serviceId, String serviceName, Task task) {
        try {
            String taskId = task.getId();
            TaskState taskState = task.getStatus().getState();
            String nodeId = task.getNodeId();
            Integer slot = task.getSlot();
            
            // 构建副本名称
            String replicaName = serviceName + "." + (slot != null ? slot : "0");
            
            // 获取容器ID
            String containerId = null;
            if (task.getStatus().getContainerStatus() != null) {
                containerId = task.getStatus().getContainerStatus().getContainerID();
            }
            
            // 获取节点信息
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
            
            // 计算运行时间
            Long uptimeSeconds = null;
            if (task.getStatus().getTimestamp() != null) {
                try {
                    String timestamp = task.getStatus().getTimestamp();
                    uptimeSeconds = parseDockerTimestamp(timestamp);
                } catch (Exception e) {
                    log.debug("计算运行时间失败: {}", e.getMessage());
                }
            }
            
            // 查询或创建副本状态记录
            LambdaQueryWrapper<ReplicaStatus> query = new LambdaQueryWrapper<>();
            query.eq(ReplicaStatus::getServiceId, serviceId)
                .eq(ReplicaStatus::getReplicaId, taskId);
            
            ReplicaStatus existing = replicaStatusMapper.selectOne(query);
            
            if (existing != null) {
                // 更新现有记录
                existing.setStatus(taskState.name().toLowerCase());
                existing.setNodeName(nodeHostname);
                existing.setNodeIp(nodeIp);
                existing.setContainerId(containerId);
                existing.setContainerIdShort(containerId != null && containerId.length() > 12 
                    ? containerId.substring(0, 12) : containerId);
                existing.setUptimeSeconds(uptimeSeconds);
                existing.setUpdatedTime(LocalDateTime.now());
                replicaStatusMapper.updateById(existing);
            } else {
                // 创建新记录
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
            
        } catch (Exception e) {
            log.error("同步Task[{}]状态失败", task.getId(), e);
        }
    }
    
    /**
     * 创建Docker客户端（带故障转移）
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
            "无法连接到任何Swarm Manager节点，已尝试: " + managerHosts,
            lastException
        );
    }
    
    /**
     * 创建Docker客户端
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
     * 构建服务名称
     */
    private String buildServiceName(String serviceName, String environmentName) {
        return serviceName + "_" + environmentName;
    }
    
    /**
     * 解析Docker时间戳并计算运行时间（秒）
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
