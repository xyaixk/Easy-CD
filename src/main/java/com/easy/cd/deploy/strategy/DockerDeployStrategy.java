package com.easy.cd.deploy.strategy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.easy.cd.deploy.DeployStrategy;
import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.deploy.util.DockerParamsExtractor;
import com.easy.cd.dto.ImageVersionDTO;
import com.easy.cd.dto.ServiceStatusInfo;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Docker Swarm 部署策略实现（SSH + Docker CLI 版）
 *
 * 通过 SSH 连接到 Swarm Manager 节点执行 Docker CLI 命令，
 * 替代原有的 docker-java API 调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerDeployStrategy implements DeployStrategy {

    private final EnvironmentMapper environmentMapper;
    private final ServiceMapper serviceMapper;
    private final SshExecutor sshExecutor;

    private RestTemplate restTemplate;
    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(10000);
        restTemplate = new RestTemplate(factory);
    }

    // ======================== 部署操作 ========================

    @Override
    public DeployResult deploy(DeployRequest request) {
        log.info("开始Docker部署: {}", request.getServiceName());
        try {
            Environment environment = environmentMapper.selectById(request.getEnvironmentId());
            if (environment == null || environment.getConfig() == null) {
                return DeployResult.failure("环境配置不存在");
            }

            Map<String, Object> config = parseConfig(environment.getConfig());
            List<SshHost> sshHosts = parseSshHostsFromConfig(config);
            String registryUrl = (String) config.get("registryUrl");

            if (sshHosts.isEmpty()) {
                return DeployResult.failure("Swarm Manager SSH 地址未配置");
            }

            String fullImageName = buildFullImageName(request.getDockerImage(), registryUrl);
            String serviceName = buildServiceName(request.getServiceName(), environment.getName());

            // 解析 Docker 参数
            Map<String, Object> dockerParams = request.getDockerParams() != null
                    ? parseConfig(request.getDockerParams())
                    : Collections.emptyMap();

            // 检查服务是否已存在
            SshResult checkResult = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service inspect " + serviceName + " --format '{{.ID}}' 2>/dev/null");
            boolean serviceExists = checkResult.isSuccess() && checkResult.hasOutput();

            if (serviceExists) {
                updateServiceViaCli(sshHosts, serviceName, fullImageName, request, dockerParams);
                log.info("服务更新成功: {}", serviceName);
            } else {
                createServiceViaCli(sshHosts, serviceName, fullImageName, request, config, dockerParams, environment);
                log.info("服务创建成功: {}", serviceName);
            }

            // 获取服务状态
            SshResult inspectResult = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service inspect " + serviceName + " --format '{{json .}}'");

            String dockerServiceId = serviceName;
            Integer replicas = 0;
            if (inspectResult.isSuccess() && inspectResult.hasOutput()) {
                JSONObject inspectJson = JSON.parseObject(inspectResult.getStdout().trim());
                dockerServiceId = inspectJson.getString("ID");
                JSONObject spec = inspectJson.getJSONObject("Spec");
                if (spec != null) {
                    JSONObject mode = spec.getJSONObject("Mode");
                    if (mode != null && mode.containsKey("Replicated")) {
                        replicas = mode.getJSONObject("Replicated").getInteger("Replicas");
                    }
                }
            }

            // 更新平台操作时间（防抖）
            AppService appService = serviceMapper.selectById(request.getServiceId());
            if (appService != null) {
                appService.setLastPlatformUpdateTime(LocalDateTime.now());
                serviceMapper.updateById(appService);
            }

            return DeployResult.builder()
                    .success(true)
                    .message("部署成功")
                    .status("running")
                    .externalServiceId(dockerServiceId)
                    .externalServiceName(serviceName)
                    .desiredInstances(replicas != null ? replicas : 0)
                    .instances(0)
                    .healthyInstances(0)
                    .build();

        } catch (Exception e) {
            log.error("Docker部署失败", e);
            return DeployResult.failure("部署失败: " + e.getMessage());
        }
    }

    @Override
    public DeployResult stop(Long serviceId) {
        log.info("停止Docker服务: {}", serviceId);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) return DeployResult.failure("服务不存在");

            List<SshHost> sshHosts = getSshHosts(appService.getEnvironmentId());
            if (sshHosts.isEmpty()) return DeployResult.failure("SSH 地址未配置");

            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            String serviceName = resolveServiceName(appService, environment);

            SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service rm " + serviceName);

            if (!result.isSuccess()) {
                log.warn("停止服务返回非零: {}", result.getStderr());
            }

            log.info("Docker服务已停止: {}", serviceName);
            return DeployResult.success("服务已停止");
        } catch (Exception e) {
            log.error("停止Docker服务失败", e);
            return DeployResult.failure("停止失败: " + e.getMessage());
        }
    }

    @Override
    public DeployResult restart(Long serviceId) {
        log.info("重启Docker服务: {}", serviceId);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) return DeployResult.failure("服务不存在");

            List<SshHost> sshHosts = getSshHosts(appService.getEnvironmentId());
            if (sshHosts.isEmpty()) return DeployResult.failure("SSH 地址未配置");

            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            String serviceName = resolveServiceName(appService, environment);

            SshResult checkResult = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service inspect " + serviceName + " --format '{{.ID}}' 2>/dev/null");

            if (!checkResult.isSuccess() || !checkResult.hasOutput()) {
                log.info("服务不存在，重新创建: {}", serviceName);
                Integer replicas = appService.getReplicas() != null && appService.getReplicas() > 0
                        ? appService.getReplicas() : 1;

                DeployRequest deployRequest = new DeployRequest();
                deployRequest.setServiceId(serviceId);
                deployRequest.setServiceName(appService.getName());
                deployRequest.setDockerImage(appService.getDockerImage());
                deployRequest.setDockerParams(appService.getDockerParams());
                deployRequest.setReplicas(replicas);
                deployRequest.setServiceMode(appService.getServiceMode());
                deployRequest.setEnvironmentId(appService.getEnvironmentId());
                return deploy(deployRequest);
            }

            SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service update --force " + serviceName);

            if (!result.isSuccess()) {
                return DeployResult.failure("重启失败: " + result.getStderr());
            }

            log.info("Docker服务已重启: {}", serviceName);
            return DeployResult.success("服务已重启");
        } catch (Exception e) {
            log.error("重启Docker服务失败", e);
            return DeployResult.failure("重启失败: " + e.getMessage());
        }
    }

    @Override
    public DeployResult rollback(Long serviceId, String targetVersion) {
        log.info("回滚Docker服务: {} 到版本: {}", serviceId, targetVersion);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) return DeployResult.failure("服务不存在");

            List<SshHost> sshHosts = getSshHosts(appService.getEnvironmentId());
            if (sshHosts.isEmpty()) return DeployResult.failure("SSH 地址未配置");

            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            String serviceName = resolveServiceName(appService, environment);

            String newImage = replaceImageTag(appService.getDockerImage(), targetVersion);
            log.info("回滚镜像: {} -> {}", appService.getDockerImage(), newImage);

            sshExecutor.executeCommandWithFailover(sshHosts, "docker pull " + newImage);
            SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service update --image " + newImage + " --force " + serviceName);

            if (!result.isSuccess()) {
                return DeployResult.failure("回滚失败: " + result.getStderr());
            }

            appService.setVersion(targetVersion);
            appService.setDockerImage(newImage);
            appService.setLastPlatformUpdateTime(LocalDateTime.now());
            serviceMapper.updateById(appService);

            log.info("Docker服务已回滚: {} -> {}", serviceName, targetVersion);
            return DeployResult.success("服务已回滚到版本: " + targetVersion);
        } catch (Exception e) {
            log.error("回滚Docker服务失败", e);
            return DeployResult.failure("回滚失败: " + e.getMessage());
        }
    }

    @Override
    public DeployResult scale(Long serviceId, Integer replicas) {
        log.info("调整Docker服务副本数: {} -> {}", serviceId, replicas);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) return DeployResult.failure("服务不存在");

            List<SshHost> sshHosts = getSshHosts(appService.getEnvironmentId());
            if (sshHosts.isEmpty()) return DeployResult.failure("SSH 地址未配置");

            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            String serviceName = resolveServiceName(appService, environment);

            SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service update --replicas " + replicas + " " + serviceName);

            if (!result.isSuccess()) {
                return DeployResult.failure("调整副本数失败: " + result.getStderr());
            }

            appService.setLastPlatformUpdateTime(LocalDateTime.now());
            serviceMapper.updateById(appService);

            log.info("Docker服务副本数已调整: {} -> {}", serviceName, replicas);
            return DeployResult.success("副本数已调整");
        } catch (Exception e) {
            log.error("调整Docker服务副本数失败", e);
            return DeployResult.failure("调整失败: " + e.getMessage());
        }
    }

    @Override
    public DeployResult delete(Long serviceId) {
        log.info("删除Docker服务: {}", serviceId);
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) return DeployResult.failure("服务不存在");

            List<SshHost> sshHosts = getSshHosts(appService.getEnvironmentId());
            if (sshHosts.isEmpty()) return DeployResult.failure("SSH 地址未配置");

            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            String serviceName = resolveServiceName(appService, environment);

            SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service rm " + serviceName);

            if (!result.isSuccess()) {
                log.warn("删除服务返回非零: {}", result.getStderr());
            }

            log.info("Docker服务已删除: {}", serviceName);
            return DeployResult.success("服务已删除");
        } catch (Exception e) {
            log.error("删除Docker服务失败", e);
            return DeployResult.failure("删除失败: " + e.getMessage());
        }
    }

    @Override
    public DeployResult getStatus(Long serviceId) {
        try {
            AppService appService = getAppService(serviceId);
            if (appService == null) return DeployResult.failure("服务不存在");

            List<SshHost> sshHosts = getSshHosts(appService.getEnvironmentId());
            if (sshHosts.isEmpty()) return DeployResult.failure("SSH 地址未配置");

            Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
            String serviceName = resolveServiceName(appService, environment);

            int desired = resolveDesiredInstances(sshHosts, appService, serviceName);
            TaskStats stats = countTaskStats(sshHosts, serviceName);
            String status = determineServiceStatus(stats.running, stats.healthy, desired, stats.hasFailed);

            return DeployResult.builder()
                    .success(true)
                    .status(status)
                    .instances(stats.running)
                    .healthyInstances(stats.healthy)
                    .desiredInstances(desired)
                    .build();
        } catch (Exception e) {
            log.error("获取服务状态失败", e);
            return DeployResult.failure("获取状态失败: " + e.getMessage());
        }
    }

    @Override
    public String getDeployType() {
        return "docker";
    }

    // ======================== 状态收集 ========================

    @Override
    public List<ServiceStatusInfo> collectServiceStatus(Environment environment, List<AppService> services) {
        List<ServiceStatusInfo> statusList = new ArrayList<>();
        if (services == null || services.isEmpty()) return statusList;

        try {
            Map<String, Object> config = parseConfig(environment.getConfig());
            List<SshHost> sshHosts = parseSshHostsFromConfig(config);
            if (sshHosts.isEmpty()) return statusList;

            // 一次 service ls 获取环境内全部服务的当前/期望副本数，避免每个服务 inspect + ps。
            SshResult listResult = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service ls --format '{\"Name\":\"{{.Name}}\",\"Replicas\":\"{{.Replicas}}\"}'");
            if (!listResult.isSuccess()) {
                log.warn("收集环境[{}]服务状态失败: {}", environment.getName(), listResult.getStderr());
                return statusList;
            }
            Map<String, ReplicaCount> countsByService = parseServiceReplicaCounts(listResult.getStdout());

            for (AppService appService : services) {
                String serviceName = resolveServiceName(appService, environment);
                ReplicaCount count = countsByService.get(serviceName);
                int running = count == null ? 0 : count.running;
                int desired = count == null
                        ? (appService.getReplicas() != null ? appService.getReplicas() : 1)
                        : count.desired;
                statusList.add(ServiceStatusInfo.builder()
                        .serviceId(appService.getId())
                        .serviceName(serviceName)
                        .status(determineServiceStatus(running, running, desired, false))
                        .healthyInstances(running)
                        .instances(running)
                        .desiredInstances(desired)
                        .build());
            }
        } catch (Exception e) {
            log.error("收集环境[{}]服务状态失败", environment.getName(), e);
        }
        return statusList;
    }

    private Map<String, ReplicaCount> parseServiceReplicaCounts(String output) {
        Map<String, ReplicaCount> result = new HashMap<>();
        if (output == null || output.trim().isEmpty()) return result;
        for (String line : output.split("\\r?\\n")) {
            try {
                JSONObject row = JSON.parseObject(line.trim());
                String name = row.getString("Name");
                String replicas = row.getString("Replicas");
                if (name == null || replicas == null) continue;
                String token = replicas.trim().split("\\s+", 2)[0];
                String[] parts = token.split("/", 2);
                if (parts.length != 2) continue;
                result.put(name, new ReplicaCount(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            } catch (Exception e) {
                log.debug("解析 docker service ls 行失败: {}", line);
            }
        }
        return result;
    }

    private static class ReplicaCount {
        private final int running;
        private final int desired;

        private ReplicaCount(int running, int desired) {
            this.running = running;
            this.desired = desired;
        }
    }

    private ServiceStatusInfo collectSingleServiceStatus(List<SshHost> sshHosts, AppService appService, String serviceName) {
        // 1. 通过 inspect 获取服务模式和期望副本数
        int desiredInstances = resolveDesiredInstances(sshHosts, appService, serviceName);

        // 2. 通过 ps 统计运行状态
        TaskStats stats = countTaskStats(sshHosts, serviceName);

        // 3. 判定最终状态
        String status = determineServiceStatus(stats.running, stats.healthy, desiredInstances, stats.hasFailed);

        return ServiceStatusInfo.builder()
                .serviceId(appService.getId())
                .serviceName(serviceName)
                .status(status)
                .healthyInstances(stats.healthy)
                .instances(stats.running)
                .desiredInstances(desiredInstances)
                .build();
    }

    /**
     * 解析服务期望副本数：Replicated 取 Spec 配置，Global 取集群活跃节点数
     */
    private int resolveDesiredInstances(List<SshHost> sshHosts, AppService appService, String serviceName) {
        SshResult inspectResult = sshExecutor.executeCommandWithFailover(sshHosts,
                "docker service inspect " + serviceName + " --format '{{json .}}'");

        if (!inspectResult.isSuccess() || !inspectResult.hasOutput()) {
            throw new RuntimeException("服务不存在: " + serviceName);
        }

        JSONObject inspectJson = JSON.parseObject(inspectResult.getStdout().trim());
        JSONObject spec = inspectJson.getJSONObject("Spec");
        if (spec == null) return 1;

        JSONObject mode = spec.getJSONObject("Mode");
        if (mode == null) return 1;

        if (mode.containsKey("Replicated")) {
            Integer replicas = mode.getJSONObject("Replicated").getInteger("Replicas");
            return replicas != null ? replicas : 1;
        } else if (mode.containsKey("Global")) {
            return countActiveNodes(sshHosts);
        }
        return 1;
    }

    /**
     * 统计服务 task 运行情况
     */
    private TaskStats countTaskStats(List<SshHost> sshHosts, String serviceName) {
        TaskStats stats = new TaskStats();

        SshResult psResult = sshExecutor.executeCommandWithFailover(sshHosts,
                "docker service ps " + serviceName + " --format '{{json .}}' --no-trunc");

        if (!psResult.isSuccess() || !psResult.hasOutput()) return stats;

        for (String line : psResult.getStdout().trim().split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                JSONObject task = JSON.parseObject(line.trim());
                String desiredState = task.getString("DesiredState");
                String currentState = task.getString("CurrentState");
                if (!"Running".equalsIgnoreCase(desiredState)) continue;

                String lower = currentState != null ? currentState.toLowerCase() : "";
                if (lower.startsWith("running")) {
                    stats.running++;
                    stats.healthy++;
                } else if (lower.startsWith("starting") || lower.startsWith("preparing")) {
                    stats.running++;
                }
            } catch (Exception ignored) {}
        }

        // 检查是否有失败的 task
        for (String line : psResult.getStdout().trim().split("\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                JSONObject task = JSON.parseObject(line.trim());
                String currentState = task.getString("CurrentState");
                if (currentState != null && currentState.toLowerCase().startsWith("failed")) {
                    stats.hasFailed = true;
                    break;
                }
            } catch (Exception ignored) {}
        }
        return stats;
    }

    /**
     * 服务状态判定逻辑：
     * - stopped:  期望=0 且 运行=0
     * - failed:   有失败任务 且 无健康实例
     * - running:  健康数 == 期望数
     * - deploying:运行数 > 0 但 健康数 < 期望数（部分实例还在启动中）
     * - degraded: 运行数 == 期望数 但健康数 < 期望数（不应出现，兆底）
     * - stopped:  运行=0 且 期望>0
     */
    private String determineServiceStatus(int running, int healthy, int desired, boolean hasFailed) {
        if (desired == 0 && running == 0) return "stopped";
        if (running == 0 && desired > 0) return "stopped";
        if (hasFailed && healthy == 0) return "failed";
        if (healthy >= desired && desired > 0) return "running";
        if (running > 0 && healthy < desired) return "deploying";
        return "unknown";
    }

    private static class TaskStats {
        int running;
        int healthy;
        boolean hasFailed;
    }

    /**
     * 获取集群活跃节点数（用于 Global 模式计算期望副本数）
     */
    private int countActiveNodes(List<SshHost> sshHosts) {
        try {
            SshResult result = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker node ls --format '{{.Status}}' | grep -c Ready");
            if (result.isSuccess() && result.hasOutput()) {
                return Integer.parseInt(result.getStdout().trim());
            }
        } catch (Exception e) {
            log.debug("获取集群节点数失败", e);
        }
        return 1;
    }

    // ======================== 日志 ========================

    @Override
    public SseEmitter streamServiceLogs(Environment environment, String serviceName, Integer tail, Boolean follow) {
        log.info("获取服务日志: serviceName={}, tail={}, follow={}", serviceName, tail, follow);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean isFollow = Boolean.TRUE.equals(follow);

        // follow 模式开启 SSE 心跳，避免 docker logs 无输出时中间层判定连接死掉、前端 EventSource 自动重连
        java.util.concurrent.ScheduledExecutorService heartbeat = isFollow
                ? java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "sse-log-heartbeat");
                    t.setDaemon(true);
                    return t;
                })
                : null;

        emitter.onCompletion(() -> {
            stopped.set(true);
            if (heartbeat != null) heartbeat.shutdownNow();
        });
        emitter.onTimeout(() -> {
            stopped.set(true);
            if (heartbeat != null) heartbeat.shutdownNow();
            emitter.complete();
        });
        emitter.onError(e -> {
            stopped.set(true);
            if (heartbeat != null) heartbeat.shutdownNow();
        });

        if (heartbeat != null) {
            heartbeat.scheduleAtFixedRate(() -> {
                if (stopped.get()) return;
                try {
                    // SSE 注释心跳（": ...\n\n"），前端 EventSource 会静默忽略但保持连接活
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (Exception e) {
                    stopped.set(true);
                }
            }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        }

        new Thread(() -> {
            try {
                Map<String, Object> config = parseConfig(environment.getConfig());
                List<SshHost> sshHosts = parseSshHostsFromConfig(config);
                if (sshHosts.isEmpty()) {
                    emitter.completeWithError(new RuntimeException("SSH 地址未配置"));
                    return;
                }

                int effectiveTail = (tail != null && tail > 0) ? Math.min(tail, 1000) : 100;
                String cmd = "docker service logs " + serviceName
                        + " --tail " + effectiveTail
                        + " --since 10m --no-trunc"
                        + (isFollow ? " --follow" : "");

                if (isFollow) {
                    // 生产者-消费者解耦：SSH read 只 offer 队列，独立 sender 线程负责 emitter.send，
                    // 避免 send 阻塞（客户端消费慢、Servlet output buffer 满）反压到 SSH 读取，
                    // 导致日志“卡一会儿→爆刷一屏”的停顿现象
                    java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.LinkedBlockingQueue<>(10000);
                    java.util.concurrent.atomic.AtomicBoolean readerDone = new java.util.concurrent.atomic.AtomicBoolean(false);

                    Thread sender = new Thread(() -> {
                        try {
                            while (!stopped.get()) {
                                String line = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                if (line == null) {
                                    if (readerDone.get() && queue.isEmpty()) break;
                                    continue;
                                }
                                try {
                                    emitter.send(SseEmitter.event().data(line).name("log"));
                                } catch (Exception e) {
                                    log.debug("SSE 发送失败，客户端已断开: {}", e.getMessage());
                                    stopped.set(true);
                                    break;
                                }
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }, "sse-log-sender");
                    sender.setDaemon(true);
                    sender.start();

                    try {
                        // 流式模式：SSH read 只入队，不限时
                        sshExecutor.executeCommandStreamingWithFailover(sshHosts, cmd, (line, isStderr) -> {
                            if (stopped.get()) return false;
                            if (line == null || line.trim().isEmpty()) return true;
                            // 非阻塞入队；队满则丢队首（drop-oldest，保护 SSH read 不被反压）
                            while (!queue.offer(line)) {
                                queue.poll();
                            }
                            return true;
                        }, 0);
                    } finally {
                        readerDone.set(true);
                        // 等 sender 将队尾日志冲刷完毕，避漏推
                        try { sender.join(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    if (!stopped.get()) emitter.complete();
                } else {
                    // 非 follow 模式：一次性拉取
                    SshResult result = sshExecutor.executeCommandWithFailover(sshHosts, cmd);
                    String output = "";
                    if (result.hasOutput()) {
                        output = result.getStdout();
                    } else if (result.getStderr() != null && !result.getStderr().trim().isEmpty()) {
                        output = result.getStderr();
                    }
                    if (!output.isEmpty()) {
                        String[] lines = output.split("\n");
                        for (String line : lines) {
                            if (stopped.get()) break;
                            if (!line.trim().isEmpty()) {
                                emitter.send(SseEmitter.event().data(line).name("log"));
                            }
                        }
                    } else {
                        emitter.send(SseEmitter.event().data("--- 无日志输出 ---").name("log"));
                    }
                    emitter.complete();
                }
            } catch (Exception e) {
                log.warn("获取服务日志失败: {}", e.getMessage());
                try {
                    if (!stopped.get()) {
                        emitter.send(SseEmitter.event().data("获取日志失败: " + e.getMessage()).name("log"));
                    }
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            } finally {
                if (heartbeat != null) heartbeat.shutdownNow();
            }
        }).start();

        return emitter;
    }

    // ======================== 镜像版本 ========================

    @Override
    public String extractVersionFromImage(String dockerImage) {
        if (dockerImage == null || dockerImage.trim().isEmpty()) return "latest";
        if (dockerImage.contains("@sha256:")) {
            int atIndex = dockerImage.indexOf("@");
            String imageWithTag = dockerImage.substring(0, atIndex);
            if (imageWithTag.contains(":")) {
                int lastColon = imageWithTag.lastIndexOf(":");
                String tag = imageWithTag.substring(lastColon + 1);
                if (!tag.contains("/")) return tag;
            }
        }
        if (dockerImage.contains(":")) {
            int lastColon = dockerImage.lastIndexOf(":");
            String tag = dockerImage.substring(lastColon + 1);
            if (!tag.contains("/")) return tag;
        }
        return "latest";
    }

    @Override
    public List<ImageVersionDTO> getAvailableVersions(String dockerImage, String registryUrl) {
        List<ImageVersionDTO> versions = new ArrayList<>();
        try {
            ImageInfo imageInfo = parseImageName(dockerImage);
            if (registryUrl != null && !registryUrl.isEmpty()) {
                imageInfo.registryUrl = registryUrl;
                if (imageInfo.imageName.startsWith("library/")) {
                    imageInfo.imageName = imageInfo.imageName.substring("library/".length());
                }
            }
            versions = fetchRegistryTags(imageInfo.registryUrl, imageInfo.imageName);
        } catch (Exception e) {
            log.error("获取镜像版本列表失败: {}", dockerImage, e);
        }
        return versions;
    }

    // ======================== CLI 核心操作 ========================

    private void createServiceViaCli(List<SshHost> sshHosts, String serviceName, String imageName,
                                     DeployRequest request, Map<String, Object> envConfig,
                                     Map<String, Object> dockerParams, Environment environment) {
        StringBuilder cmd = new StringBuilder("docker service create");
        cmd.append(" --name ").append(serviceName);

        boolean isGlobal = "global".equalsIgnoreCase(request.getServiceMode());
        if (isGlobal) {
            cmd.append(" --mode global");
        } else {
            Integer replicas = (request.getReplicas() != null && request.getReplicas() > 0) ? request.getReplicas() : 1;
            cmd.append(" --replicas ").append(replicas);
        }

        String userNetwork = getNetworkMode(dockerParams, environment);
        if ("host".equalsIgnoreCase(userNetwork)) {
            cmd.append(" --network host");
        } else {
            // 非 host 情况：
            //  - 用户填了自定义名字 → 直接使用（自动建网）
            //  - 未填或仅为默认标记 "overlay" → 默认 ${env-name}-overlay
            String networkName;
            if (userNetwork != null && !userNetwork.trim().isEmpty()
                    && !"overlay".equalsIgnoreCase(userNetwork.trim())) {
                networkName = userNetwork.trim();
            } else {
                networkName = environment.getName().toLowerCase() + "-overlay";
            }
            ensureNetworkExists(sshHosts, networkName);
            cmd.append(" --network ").append(networkName);
        }

        appendDockerParamsFlags(cmd, dockerParams, false);
        cmd.append(" ").append(imageName);
        appendCommandArgs(cmd, dockerParams);

        SshResult result = sshExecutor.executeCommandWithFailover(sshHosts, cmd.toString());
        if (!result.isSuccess()) {
            throw new RuntimeException("创建服务失败: " + result.getStderr());
        }
    }

    private void updateServiceViaCli(List<SshHost> sshHosts, String serviceName, String imageName,
                                     DeployRequest request, Map<String, Object> newParams) {
        // 先 inspect 拿到当前 Swarm 上的实际配置，作为 diff 基准
        Map<String, Object> oldParams = inspectCurrentDockerParams(sshHosts, serviceName);
        String currentImage = inspectCurrentImage(sshHosts, serviceName);

        // 仅在镜像地址真的变化时才 pull，避免仅编辑配置时意外刷新 latest
        if (!sameImage(currentImage, imageName)) {
            log.info("镜像变更[{} → {}]，拉取新镜像", currentImage, imageName);
            sshExecutor.executeCommandWithFailover(sshHosts, "docker pull " + imageName);
        } else {
            log.info("镜像未变更，跳过 docker pull: {}", imageName);
        }

        StringBuilder cmd = new StringBuilder("docker service update");
        cmd.append(" --image ").append(imageName);

        if (request.getReplicas() != null && request.getReplicas() > 0) {
            cmd.append(" --replicas ").append(request.getReplicas());
        }

        // diff 方式拼接 flag：仅对变化项发送 -add/-rm 或覆盖，旧有新无恢复默认
        appendDiffFlags(cmd, oldParams, newParams);

        cmd.append(" --force");
        cmd.append(" ").append(serviceName);

        SshResult result = sshExecutor.executeCommandWithFailover(sshHosts, cmd.toString());
        if (!result.isSuccess()) {
            throw new RuntimeException("更新服务失败: " + result.getStderr());
        }
        if (result.hasOutput()) {
            log.info("docker service update 输出: {}", result.getStdout().trim());
        }
    }

    /** 拉取当前服务的镜像地址（形如 host/repo:tag 或额外带 @sha256:...） */
    private String inspectCurrentImage(List<SshHost> sshHosts, String serviceName) {
        try {
            SshResult r = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service inspect " + serviceName +
                            " --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}'");
            if (r.isSuccess() && r.hasOutput()) {
                return r.getStdout().trim();
            }
        } catch (Exception e) {
            log.debug("拉取当前镜像失败: {}", e.getMessage());
        }
        return null;
    }

    /** 对比两个镜像地址是否为同一个，忽略 @sha256:... 摩要后缀 */
    private boolean sameImage(String a, String b) {
        if (a == null || b == null) return false;
        return stripDigest(a).equals(stripDigest(b));
    }

    private String stripDigest(String image) {
        int at = image.indexOf("@sha256:");
        return at > 0 ? image.substring(0, at) : image;
    }

    /** 拉取当前服务的实际配置并反向提取为 dockerParams map，供 diff 使用 */
    private Map<String, Object> inspectCurrentDockerParams(List<SshHost> sshHosts, String serviceName) {
        try {
            SshResult r = sshExecutor.executeCommandWithFailover(sshHosts,
                    "docker service inspect " + serviceName + " --format '{{json .}}'");
            if (!r.isSuccess() || !r.hasOutput()) return new LinkedHashMap<>();
            String out = r.getStdout().trim();
            JSONObject json;
            if (out.startsWith("[")) {
                JSONArray arr = JSON.parseArray(out);
                json = arr.isEmpty() ? null : arr.getJSONObject(0);
            } else {
                json = JSON.parseObject(out);
            }
            return DockerParamsExtractor.extractFromInspect(json);
        } catch (Exception e) {
            log.warn("拉取当前服务[{}]配置失败，将退化为全量覆盖: {}", serviceName, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void appendDockerParamsFlags(StringBuilder cmd, Map<String, Object> dockerParams, boolean update) {
        if (dockerParams == null || dockerParams.isEmpty()) return;

        // 资源限制
        if (dockerParams.containsKey("cpus")) {
            cmd.append(" --limit-cpu ").append(dockerParams.get("cpus"));
        }
        if (dockerParams.containsKey("memory")) {
            cmd.append(" --limit-memory ").append(dockerParams.get("memory"));
        }
        if (dockerParams.containsKey("memory-reservation")) {
            cmd.append(" --reserve-memory ").append(dockerParams.get("memory-reservation"));
        }

        // 重启策略（直接传原始值: any / none / on-failure）
        if (dockerParams.containsKey("restart")) {
            cmd.append(" --restart-condition ").append(dockerParams.get("restart"));
        }
        if (dockerParams.containsKey("restart-max-attempts")) {
            cmd.append(" --restart-max-attempts ").append(dockerParams.get("restart-max-attempts"));
        }
        if (dockerParams.containsKey("restart-delay")) {
            cmd.append(" --restart-delay ").append(dockerParams.get("restart-delay"));
        }

        // 端口映射（换行或逗号分隔，单条内容支持 kv 完整格式）
        // create 用 --publish；update 用 --publish-add（仅追加，不做 rm）
        if (dockerParams.containsKey("publish")) {
            String publishFlag = update ? " --publish-add " : " --publish ";
            for (String p : splitMultiValue(dockerParams.get("publish"))) {
                cmd.append(publishFlag).append(p);
            }
        }

        // 健康检查
        if (dockerParams.containsKey("healthcheck")) {
            cmd.append(" --health-cmd '").append(dockerParams.get("healthcheck")).append("'");
            cmd.append(" --health-interval ").append(dockerParams.getOrDefault("healthcheck_interval", "10s"));
            cmd.append(" --health-timeout ").append(dockerParams.getOrDefault("healthcheck_timeout", "5s"));
            cmd.append(" --health-retries ").append(dockerParams.getOrDefault("healthcheck_retries", "3"));
            if (dockerParams.containsKey("healthcheck_start_period")) {
                cmd.append(" --health-start-period ").append(dockerParams.get("healthcheck_start_period"));
            }
        }

        // 滚动更新
        if (dockerParams.containsKey("update_parallelism")) {
            cmd.append(" --update-parallelism ").append(dockerParams.get("update_parallelism"));
        }
        if (dockerParams.containsKey("update_delay")) {
            cmd.append(" --update-delay ").append(dockerParams.get("update_delay"));
        }
        if (dockerParams.containsKey("update_failure_action")) {
            cmd.append(" --update-failure-action ").append(dockerParams.get("update_failure_action"));
        }
        if (dockerParams.containsKey("update_order")) {
            cmd.append(" --update-order ").append(dockerParams.get("update_order"));
        }

        // 挂载卷：每条可以是 `src:dst[:ro]` 旧格式，或 `type=bind,src=X,dst=Y[,readonly]` 新格式（直接透传）
        // create 用 --mount；update 用 --mount-add
        if (dockerParams.containsKey("mounts")) {
            String mountFlag = update ? " --mount-add " : " --mount ";
            for (String m : splitMultiValue(dockerParams.get("mounts"))) {
                if (m.contains("=")) {
                    // 完整 kv 格式，直接透传
                    cmd.append(mountFlag).append(m);
                } else if (m.contains(":")) {
                    // 简短 src:dst[:ro] 格式
                    String[] parts = m.split(":");
                    if (parts.length >= 2) {
                        cmd.append(mountFlag).append("type=bind,source=").append(parts[0])
                           .append(",target=").append(parts[1]);
                        if (parts.length >= 3 && "ro".equalsIgnoreCase(parts[2])) {
                            cmd.append(",readonly");
                        }
                    }
                }
            }
        }

        // 日志驱动
        if (dockerParams.containsKey("log-driver")) {
            cmd.append(" --log-driver ").append(dockerParams.get("log-driver"));
        }

        // 容器标签
        appendContainerLabels(cmd, dockerParams, update);

        // 剩余未识别的 key 作为环境变量
        // create 用 --env；update 用 --env-add
        String envFlag = update ? " --env-add " : " --env ";
        for (Map.Entry<String, Object> entry : dockerParams.entrySet()) {
            if (!isDockerBuiltInParam(entry.getKey())) {
                cmd.append(envFlag).append("'").append(entry.getKey()).append("=").append(entry.getValue()).append("'");
            }
        }
    }

    // ======================== update 时的 diff 拼接逻辑 ========================
    // 目的：实现“编辑框即最终状态”语义，只对有变化的项发 flag，
    // 多值字段（publish/mount/env/container-label/network）用 -add/-rm，单值字段直接覆盖。
    // 旧有新无的项：单值字段恢复为 Docker 默认值，多值字段发 -rm。

    private void appendDiffFlags(StringBuilder cmd, Map<String, Object> oldP, Map<String, Object> newP) {
        // 资源限制
        diffSingle(cmd, "--limit-cpu", oldP.get("cpus"), newP.get("cpus"), "0");
        diffSingle(cmd, "--limit-memory", oldP.get("memory"), newP.get("memory"), "0");
        diffSingle(cmd, "--reserve-memory", oldP.get("memory-reservation"), newP.get("memory-reservation"), "0");

        // 重启策略
        diffSingle(cmd, "--restart-condition", oldP.get("restart"), newP.get("restart"), "any");
        diffSingle(cmd, "--restart-max-attempts", oldP.get("restart-max-attempts"), newP.get("restart-max-attempts"), "0");
        diffSingle(cmd, "--restart-delay", oldP.get("restart-delay"), newP.get("restart-delay"), "5s");

        // 滑动更新策略
        diffSingle(cmd, "--update-parallelism", oldP.get("update_parallelism"), newP.get("update_parallelism"), "1");
        diffSingle(cmd, "--update-delay", oldP.get("update_delay"), newP.get("update_delay"), "0s");
        diffSingle(cmd, "--update-failure-action", oldP.get("update_failure_action"), newP.get("update_failure_action"), "pause");
        diffSingle(cmd, "--update-order", oldP.get("update_order"), newP.get("update_order"), "stop-first");

        // 日志驱动（无法真正清空，仅在新值不同时覆盖）
        String oldLd = str(oldP.get("log-driver")), newLd = str(newP.get("log-driver"));
        if (newLd != null && !newLd.equals(oldLd)) {
            cmd.append(" --log-driver ").append(newLd);
        }

        // 健康检查（组合处理）
        diffHealthcheck(cmd, oldP, newP);

        // 启动命令 (--args)
        String oldCmd = str(oldP.get("command")), newCmd = str(newP.get("command"));
        if (!Objects.equals(oldCmd, newCmd)) {
            if (newCmd != null) {
                cmd.append(" --args ").append("'").append(newCmd.replace("'", "'\\''")).append("'");
            } else {
                cmd.append(" --args ''");
            }
        }

        // 多值：端口映射
        diffPublish(cmd, oldP.get("publish"), newP.get("publish"));
        // 多值：挂载卷
        diffMount(cmd, oldP.get("mounts"), newP.get("mounts"));
        // 多值：容器标签
        diffContainerLabel(cmd,
                oldP.get("container-labels") != null ? oldP.get("container-labels") : oldP.get("container-label"),
                newP.get("container-labels") != null ? newP.get("container-labels") : newP.get("container-label"));
        // 单值但需 add/rm：网络
        diffNetwork(cmd, oldP.get("network"), newP.get("network"));

        // 环境变量（非内置 key）
        diffEnv(cmd, oldP, newP);
    }

    /** 单值字段 diff：变化时拼新值；旧有新无拼 defaultValue（为 null 则跳过） */
    private void diffSingle(StringBuilder cmd, String flag, Object oldV, Object newV, String defaultValue) {
        String o = str(oldV), n = str(newV);
        if (Objects.equals(o, n)) return;
        if (n != null) {
            cmd.append(" ").append(flag).append(" ").append(n);
        } else if (defaultValue != null) {
            cmd.append(" ").append(flag).append(" ").append(defaultValue);
        }
    }

    private void diffHealthcheck(StringBuilder cmd, Map<String, Object> oldP, Map<String, Object> newP) {
        String o = str(oldP.get("healthcheck")), n = str(newP.get("healthcheck"));
        if (o == null && n == null) return;
        if (n == null) {
            cmd.append(" --no-healthcheck");
            return;
        }
        if (!Objects.equals(o, n)) {
            cmd.append(" --health-cmd '").append(n.replace("'", "'\\''")).append("'");
        }
        diffSingle(cmd, "--health-interval", oldP.get("healthcheck_interval"), newP.get("healthcheck_interval"), "0s");
        diffSingle(cmd, "--health-timeout", oldP.get("healthcheck_timeout"), newP.get("healthcheck_timeout"), "0s");
        diffSingle(cmd, "--health-retries", oldP.get("healthcheck_retries"), newP.get("healthcheck_retries"), "0");
        diffSingle(cmd, "--health-start-period", oldP.get("healthcheck_start_period"), newP.get("healthcheck_start_period"), "0s");
    }

    /** 端口映射 diff：key = published[/protocol]，rm value = published[/protocol] */
    private void diffPublish(StringBuilder cmd, Object oldVal, Object newVal) {
        Map<String, String> oldMap = toKeyedMap(oldVal, this::publishKey);
        Map<String, String> newMap = toKeyedMap(newVal, this::publishKey);
        for (Map.Entry<String, String> e : newMap.entrySet()) {
            String oldSpec = oldMap.get(e.getKey());
            if (oldSpec == null) {
                cmd.append(" --publish-add ").append(e.getValue());
            } else if (!oldSpec.equals(e.getValue())) {
                cmd.append(" --publish-rm ").append(e.getKey())
                   .append(" --publish-add ").append(e.getValue());
            }
        }
        for (Map.Entry<String, String> e : oldMap.entrySet()) {
            if (!newMap.containsKey(e.getKey())) {
                cmd.append(" --publish-rm ").append(e.getKey());
            }
        }
    }

    /** 挂载卷 diff：key = target 路径，rm value = target 路径，add value = 完整 spec */
    private void diffMount(StringBuilder cmd, Object oldVal, Object newVal) {
        Map<String, String> oldMap = toKeyedMap(oldVal, this::mountKey);
        Map<String, String> newMap = toKeyedMap(newVal, this::mountKey);
        for (Map.Entry<String, String> e : newMap.entrySet()) {
            String oldSpec = oldMap.get(e.getKey());
            String addSpec = mountAddSpec(e.getValue());
            if (addSpec == null) continue;
            if (oldSpec == null) {
                cmd.append(" --mount-add ").append(addSpec);
            } else if (!oldSpec.equals(e.getValue())) {
                cmd.append(" --mount-rm ").append(e.getKey())
                   .append(" --mount-add ").append(addSpec);
            }
        }
        for (Map.Entry<String, String> e : oldMap.entrySet()) {
            if (!newMap.containsKey(e.getKey())) {
                cmd.append(" --mount-rm ").append(e.getKey());
            }
        }
    }

    /** 容器标签 diff：key = label name, rm value = label name, add value = key=value */
    private void diffContainerLabel(StringBuilder cmd, Object oldVal, Object newVal) {
        Map<String, String> oldMap = toKeyedMap(oldVal, this::labelKey);
        Map<String, String> newMap = toKeyedMap(newVal, this::labelKey);
        for (Map.Entry<String, String> e : newMap.entrySet()) {
            String oldSpec = oldMap.get(e.getKey());
            if (oldSpec == null) {
                cmd.append(" --container-label-add ").append("'").append(e.getValue()).append("'");
            } else if (!oldSpec.equals(e.getValue())) {
                cmd.append(" --container-label-rm ").append(e.getKey())
                   .append(" --container-label-add ").append("'").append(e.getValue()).append("'");
            }
        }
        for (Map.Entry<String, String> e : oldMap.entrySet()) {
            if (!newMap.containsKey(e.getKey())) {
                cmd.append(" --container-label-rm ").append(e.getKey());
            }
        }
    }

    /** 网络 diff：变化时先 rm 旧再 add 新；旧有新无只 rm；旧无新有只 add */
    private void diffNetwork(StringBuilder cmd, Object oldVal, Object newVal) {
        String o = str(oldVal), n = str(newVal);
        if (Objects.equals(o, n)) return;
        if (o != null) cmd.append(" --network-rm ").append(o);
        if (n != null) cmd.append(" --network-add ").append(n);
    }

    /** 环境变量 diff：非内置 key 都当作 env */
    private void diffEnv(StringBuilder cmd, Map<String, Object> oldP, Map<String, Object> newP) {
        Map<String, String> oldEnv = collectEnv(oldP);
        Map<String, String> newEnv = collectEnv(newP);
        for (Map.Entry<String, String> e : newEnv.entrySet()) {
            String oldVal = oldEnv.get(e.getKey());
            if (!Objects.equals(oldVal, e.getValue())) {
                if (oldVal != null) {
                    cmd.append(" --env-rm ").append(e.getKey());
                }
                cmd.append(" --env-add ").append("'").append(e.getKey()).append("=").append(e.getValue()).append("'");
            }
        }
        for (Map.Entry<String, String> e : oldEnv.entrySet()) {
            if (!newEnv.containsKey(e.getKey())) {
                cmd.append(" --env-rm ").append(e.getKey());
            }
        }
    }

    private Map<String, String> collectEnv(Map<String, Object> params) {
        Map<String, String> env = new LinkedHashMap<>();
        if (params == null) return env;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (isDockerBuiltInParam(e.getKey())) continue;
            env.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
        }
        return env;
    }

    // ---------- key 提取器 ----------

    private String publishKey(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        if (spec.contains("=")) {
            String published = null, protocol = null;
            for (String kv : spec.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                if ("published".equals(k)) published = v;
                else if ("protocol".equals(k)) protocol = v;
            }
            if (published == null) return null;
            return (protocol != null && !"tcp".equalsIgnoreCase(protocol)) ? published + "/" + protocol : published;
        }
        String first = spec.split(":", 2)[0].trim();
        return first.isEmpty() ? null : first;
    }

    private String mountKey(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        if (spec.contains("=")) {
            for (String kv : spec.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                if ("target".equals(k) || "dst".equals(k) || "destination".equals(k)) return v;
            }
            return null;
        }
        String[] parts = spec.split(":");
        return parts.length >= 2 ? parts[1].trim() : null;
    }

    private String mountAddSpec(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        if (spec.contains("=")) return spec;
        String[] parts = spec.split(":");
        if (parts.length < 2) return null;
        StringBuilder sb = new StringBuilder("type=bind,source=").append(parts[0]).append(",target=").append(parts[1]);
        if (parts.length >= 3 && "ro".equalsIgnoreCase(parts[2])) sb.append(",readonly");
        return sb.toString();
    }

    private String labelKey(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        int eq = spec.indexOf('=');
        return eq > 0 ? spec.substring(0, eq).trim() : spec.trim();
    }

    private Map<String, String> toKeyedMap(Object value, java.util.function.Function<String, String> keyExtractor) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String s : splitMultiValue(value)) {
            String key = keyExtractor.apply(s);
            if (key != null && !key.isEmpty()) map.put(key, s);
        }
        return map;
    }

    private String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ======================== 原全量拼接方法（留给 create 使用） ========================

    private void appendContainerLabels(StringBuilder cmd, Map<String, Object> dockerParams, boolean update) {
        Object labels = dockerParams.get("container-labels");
        if (labels == null) {
            labels = dockerParams.get("container-label");
        }
        if (labels == null) {
            return;
        }
        String flag = update ? " --container-label-add " : " --container-label ";
        for (String label : splitMultiValue(labels)) {
            cmd.append(flag).append("'").append(label).append("'");
        }
    }

    /**
     * 多值字段分割：优先按换行分隔（兼容内部含逗号的 kv 格式），回退按逗号分隔（兼容旧数据）。
     * 兼容传入 List（直接逐项返回）和 String（按分隔符拆分）。
     */
    @SuppressWarnings("unchecked")
    private static List<String> splitMultiValue(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item == null) continue;
                String s = item.toString().trim();
                if (!s.isEmpty()) result.add(s);
            }
            return result;
        }
        String str = value.toString();
        String[] items = str.contains("\n") ? str.split("\\r?\\n") : str.split(",");
        for (String item : items) {
            String s = item == null ? "" : item.trim();
            if (!s.isEmpty()) result.add(s);
        }
        return result;
    }

    private void ensureNetworkExists(List<SshHost> sshHosts, String networkName) {
        SshResult check = sshExecutor.executeCommandWithFailover(sshHosts,
                "docker network inspect " + networkName + " --format '{{.Id}}' 2>/dev/null");
        if (check.isSuccess() && check.hasOutput()) return;
        log.info("创建 overlay 网络: {}", networkName);
        sshExecutor.executeCommandWithFailover(sshHosts,
                "docker network create --driver overlay --attachable " + networkName);
    }

    // ======================== 辅助方法 ========================

    private Map<String, Object> parseConfig(String json) {
        try {
            return JSON.parseObject(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("解析配置失败: {}", json, e);
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private List<SshHost> parseSshHostsFromConfig(Map<String, Object> config) {
        return SshExecutor.parseSshHostsFromConfig(config);
    }

    private List<SshHost> getSshHosts(Long environmentId) {
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null || environment.getConfig() == null) return Collections.emptyList();
        return parseSshHostsFromConfig(parseConfig(environment.getConfig()));
    }

    private AppService getAppService(Long serviceId) {
        return serviceMapper.selectById(serviceId);
    }

    private String resolveServiceName(AppService appService, Environment environment) {
        return appService.getExternalServiceName() != null
                ? appService.getExternalServiceName()
                : buildServiceName(appService.getName(), environment != null ? environment.getName() : "");
    }

    private String buildServiceName(String serviceName, String envName) {
        return serviceName.toLowerCase();
    }

    private String buildFullImageName(String dockerImage, String registryUrl) {
        String imageName = dockerImage;
        if (!imageName.contains(":")) {
            imageName = imageName + ":latest";
        } else {
            int colonIndex = imageName.lastIndexOf(":");
            String afterColon = imageName.substring(colonIndex + 1);
            String beforeColon = imageName.substring(0, colonIndex);
            if ((afterColon.matches("\\d+") || afterColon.contains("/")) && !beforeColon.contains("/")) {
                imageName = imageName + ":latest";
            }
        }
        int firstSlash = imageName.indexOf("/");
        if (firstSlash == -1) {
            imageName = registryUrl + "/" + imageName;
        } else {
            String firstPart = imageName.substring(0, firstSlash);
            if (!firstPart.contains(".") && !firstPart.contains(":")) {
                imageName = registryUrl + "/" + imageName;
            }
        }
        return imageName;
    }

    private String replaceImageTag(String dockerImage, String newTag) {
        String imageWithoutTag = dockerImage;
        if (imageWithoutTag.contains("@sha256:")) {
            imageWithoutTag = imageWithoutTag.substring(0, imageWithoutTag.indexOf("@"));
        }
        if (imageWithoutTag.contains(":")) {
            int lastColon = imageWithoutTag.lastIndexOf(":");
            String afterColon = imageWithoutTag.substring(lastColon + 1);
            if (!afterColon.matches("\\d+") && !afterColon.contains("/")) {
                imageWithoutTag = imageWithoutTag.substring(0, lastColon);
            }
        }
        return imageWithoutTag + ":" + newTag;
    }

    private String getNetworkMode(Map<String, Object> dockerParams, Environment environment) {
        if (dockerParams != null && dockerParams.containsKey("network")) {
            return dockerParams.get("network").toString();
        }
        if (environment != null && environment.getConfig() != null) {
            Map<String, Object> envConfig = parseConfig(environment.getConfig());
            if (envConfig.containsKey("networkMode")) return envConfig.get("networkMode").toString();
        }
        return "overlay";
    }

    private boolean isDockerBuiltInParam(String key) {
        return key.equals("replicas") || key.equals("cpus") || key.equals("memory") ||
                key.equals("memory-reservation") || key.equals("restart") ||
                key.equals("restart-max-attempts") || key.equals("restart-delay") ||
                key.equals("publish") || key.equals("network") ||
                key.equals("healthcheck") || key.equals("healthcheck_interval") ||
                key.equals("healthcheck_timeout") || key.equals("healthcheck_retries") ||
                key.equals("healthcheck_start_period") || key.equals("update_parallelism") ||
                key.equals("update_delay") || key.equals("update_monitor") ||
                key.equals("update_failure_action") || key.equals("update_order") ||
                key.equals("container-label") || key.equals("container-labels") ||
                key.equals("mounts") || key.equals("log-driver") || key.equals("log-opts") ||
                key.equals("command");
    }

    /** 镜像后追加用户自定义启动命令（对应 docker service create ... IMAGE [COMMAND] [ARG...]） */
    private void appendCommandArgs(StringBuilder cmd, Map<String, Object> dockerParams) {
        if (dockerParams == null) return;
        Object cmdVal = dockerParams.get("command");
        if (cmdVal == null) return;
        String cmdStr = cmdVal.toString().trim();
        if (!cmdStr.isEmpty()) {
            cmd.append(" ").append(cmdStr);
        }
    }

    /** update 时使用 --args 设置启动命令 */
    private void appendUpdateCommandArgs(StringBuilder cmd, Map<String, Object> dockerParams) {
        if (dockerParams == null) return;
        Object cmdVal = dockerParams.get("command");
        if (cmdVal == null) return;
        String cmdStr = cmdVal.toString().trim();
        if (!cmdStr.isEmpty()) {
            cmd.append(" --args ").append("'").append(cmdStr.replace("'", "'\\''")).append("'");
        }
    }

    // ======================== Registry API ========================

    private List<ImageVersionDTO> fetchRegistryTags(String registryUrl, String imageName) {
        boolean hasProtocol = registryUrl.startsWith("http://") || registryUrl.startsWith("https://");
        if (hasProtocol) {
            try { return doFetchRegistryTags(String.format("%s/v2/%s/tags/list", registryUrl, imageName)); }
            catch (Exception e) { return new ArrayList<>(); }
        }
        try {
            List<ImageVersionDTO> r = doFetchRegistryTags(String.format("https://%s/v2/%s/tags/list", registryUrl, imageName));
            if (r != null && !r.isEmpty()) return r;
        } catch (Exception ignored) {}
        try {
            return doFetchRegistryTags(String.format("http://%s/v2/%s/tags/list", registryUrl, imageName));
        } catch (Exception e) { return new ArrayList<>(); }
    }

    @SuppressWarnings("unchecked")
    private List<ImageVersionDTO> doFetchRegistryTags(String tagsUrl) {
        List<ImageVersionDTO> versions = new ArrayList<>();
        ResponseEntity<Map> response = restTemplate.exchange(tagsUrl, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<String> tags = (List<String>) response.getBody().get("tags");
            if (tags != null) {
                for (String tag : tags) {
                    versions.add(ImageVersionDTO.builder().version(tag).isCurrent(false).build());
                }
            }
        }
        return versions;
    }

    private ImageInfo parseImageName(String dockerImage) {
        ImageInfo info = new ImageInfo();
        String img = dockerImage;
        if (img.contains("@sha256:")) img = img.substring(0, img.indexOf("@"));
        if (img.contains(":")) {
            int lc = img.lastIndexOf(":");
            String ac = img.substring(lc + 1);
            if (!ac.matches("\\d+") && !ac.contains("/")) img = img.substring(0, lc);
        }
        int fs = img.indexOf("/");
        if (fs == -1) {
            info.registryUrl = "https://registry-1.docker.io";
            info.imageName = "library/" + img;
        } else {
            String before = img.substring(0, fs);
            if (before.contains(".") || before.contains(":")) {
                info.imageName = img.substring(fs + 1);
                info.registryUrl = (before.contains("localhost") || before.matches(".*:\\d+"))
                        ? "http://" + before : before;
            } else {
                info.registryUrl = "https://registry-1.docker.io";
                info.imageName = img;
            }
        }
        return info;
    }

    private static class ImageInfo {
        String registryUrl;
        String imageName;
    }
}
