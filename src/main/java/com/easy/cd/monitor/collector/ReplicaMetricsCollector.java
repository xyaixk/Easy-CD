package com.easy.cd.monitor.collector;

import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaMetrics;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.monitor.config.MonitorProperties;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 副本指标采集器：并发采一个 environment 下全部节点的 docker stats。
 *
 * 关键设计：
 *  - 单节点一次 SSH 执行 `docker stats --no-stream --format '{{json .}}'`
 *  - 通过 (container_id_short) 关联 replica_status，无匹配的容器直接丢弃
 *  - 极简策略仅填 4 个核心字段：cpu_percent / memory_usage / memory_limit / memory_percent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicaMetricsCollector {

    private final NodeDiscoveryService nodeDiscoveryService;
    private final SshExecutor sshExecutor;
    private final ReplicaStatusMapper replicaStatusMapper;
    private final MonitorProperties monitorProperties;
    private final NodeMetricsSnapshotCache snapshotCache;

    private final ReplicaSampleParser parser = new ReplicaSampleParser();

    private ExecutorService executor;

    /** 单节点采集命令：无 stream、只输出 JSON */
    private static final String COLLECT_CMD =
            "docker stats --no-stream --format '{{json .}}'";

    @PostConstruct
    public void init() {
        int parallelism = monitorProperties.getCollector().getParallelism();
        this.executor = Executors.newFixedThreadPool(parallelism, new NamedThreadFactory("replica-collect"));
        log.info("ReplicaMetricsCollector 初始化: parallelism={}, nodeTimeoutMs={}",
                parallelism, monitorProperties.getCollector().getNodeTimeoutMs());
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 采集一个环境下全部节点的副本指标（不含入库；由 Task 负责批量落库）。
     * 无 platform=docker 的副本记录时返回空列表，不进行 SSH 采集。
     */
    public List<ReplicaMetrics> collect(Environment environment) {
        // 1) 先取 replica_status 关联表；空则跳过 SSH
        List<ReplicaStatus> replicas = replicaStatusMapper
                .selectDockerReplicasByEnvironmentId(environment.getId());
        if (replicas.isEmpty()) return new ArrayList<>();

        Map<String, ReplicaStatus> replicaMap = new HashMap<>();
        for (ReplicaStatus rs : replicas) {
            String key = rs.getContainerIdShort();
            if (key != null && !key.isEmpty()) {
                replicaMap.put(key, rs);
            }
        }
        if (replicaMap.isEmpty()) return new ArrayList<>();

        // 2) 节点发现
        List<NodeInfo> nodes = nodeDiscoveryService.discover(environment);
        if (nodes.isEmpty()) return new ArrayList<>();

        int nodeTimeoutMs = monitorProperties.getCollector().getNodeTimeoutMs();
        LocalDateTime collectedTime = LocalDateTime.now();

        // 3) 并发采每个节点，各自返回该节点上匹配到的 ReplicaMetrics 列表
        List<CompletableFuture<List<ReplicaMetrics>>> futures = new ArrayList<>();
        for (NodeInfo node : nodes) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> sampleNode(environment.getId(), node, replicaMap, collectedTime, nodeTimeoutMs), executor));
        }

        // 4) 汇总
        List<ReplicaMetrics> results = new ArrayList<>();
        for (CompletableFuture<List<ReplicaMetrics>> f : futures) {
            try {
                List<ReplicaMetrics> part = f.get(nodeTimeoutMs + 2000L, TimeUnit.MILLISECONDS);
                if (part != null && !part.isEmpty()) results.addAll(part);
            } catch (Exception e) {
                log.debug("replica 节点采集任务超时/失败: {}", e.getMessage());
            }
        }
        return results;
    }

    private List<ReplicaMetrics> sampleNode(Long environmentId,
                                            NodeInfo node,
                                            Map<String, ReplicaStatus> replicaMap,
                                            LocalDateTime collectedTime,
                                            int timeoutMs) {
        List<ReplicaMetrics> out = new ArrayList<>();
        try {
            long maxAgeMs = monitorProperties.getCollector().getIntervalMs() + timeoutMs;
            String statsOutput = snapshotCache.getDockerStats(environmentId, node.getHostKey(), maxAgeMs);
            if (statsOutput == null) {
                SshResult res = sshExecutor.executeCommand(node.getSshHost(), COLLECT_CMD, timeoutMs);
                if (!res.isSuccess() || !res.hasOutput()) {
                    log.debug("replica 节点[{}]采集失败: exit={}, err={}",
                            node.getHostKey(), res.getExitCode(), res.getStderr());
                    return out;
                }
                statsOutput = res.getStdout();
            }
            for (String line : statsOutput.split("\\r?\\n")) {
                ReplicaSampleParser.ReplicaSampleRaw raw = parser.parseLine(line);
                if (raw == null || raw.getContainerIdShort() == null) continue;

                ReplicaStatus rs = replicaMap.get(raw.getContainerIdShort());
                if (rs == null) continue; // 非 Swarm 管理或尚未同步进 replica_status

                ReplicaMetrics m = new ReplicaMetrics();
                m.setReplicaStatusId(rs.getId());
                m.setServiceId(rs.getServiceId());
                m.setReplicaId(rs.getReplicaId());
                m.setReplicaName(rs.getReplicaName());
                m.setPlatform("docker");
                m.setNodeName(rs.getNodeName());
                m.setStatus(rs.getStatus() != null ? rs.getStatus() : "running");

                if (raw.getCpuPercent() != null) {
                    m.setCpuPercent(raw.getCpuPercent().doubleValue());
                }
                m.setMemoryUsage(raw.getMemoryUsage());
                m.setMemoryLimit(raw.getMemoryLimit());
                if (raw.getMemPercent() != null) {
                    m.setMemoryPercent(raw.getMemPercent().doubleValue());
                }
                m.setCollectedTime(collectedTime);
                out.add(m);
            }
        } catch (Exception e) {
            log.debug("replica 节点[{}]采集异常: {}", node.getHostKey(), e.getMessage());
        }
        return out;
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
