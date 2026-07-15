package com.easy.cd.monitor.collector;

import com.easy.cd.entity.Environment;
import com.easy.cd.monitor.config.MonitorProperties;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.monitor.entity.HostInfo;
import com.easy.cd.monitor.entity.HostMetrics;
import com.easy.cd.monitor.mapper.HostInfoMapper;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集群指标采集器：并发采一个 environment 下全部节点的 host 指标。
 *
 * 关键设计：
 *  - 单节点一次 SSH 拉四段（/proc/stat + meminfo + loadavg + df），不 sleep
 *  - CPU% 由内存缓存 statSnapshotMap 存上次 idle/total，本次做差值
 *  - 首次采样 cpu_percent = null，第二次起有值
 *  - hostKey 找不到 host_id 时懒创建一条 host_info 记录，保证不阻塞采集
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterMetricsCollector {

    private final NodeDiscoveryService nodeDiscoveryService;
    private final SshExecutor sshExecutor;
    private final HostInfoMapper hostInfoMapper;
    private final MonitorProperties monitorProperties;
    private final NodeMetricsSnapshotCache snapshotCache;

    private final HostSampleParser parser = new HostSampleParser();

    /** (envId, hostKey) -> 上一次 stat 快照，用于计算 cpu% */
    private final Map<String, StatSnapshot> statSnapshotMap = new ConcurrentHashMap<>();

    /** (envId, hostKey) -> host_info.id 缓存，减少 DB 查询 */
    private final Map<String, Long> hostIdCache = new ConcurrentHashMap<>();

    /** 并发采集线程池 */
    private ExecutorService executor;

    /** 内部缓存 key：envId + ":" + hostKey，防止跨环境重名 hostKey 串接 */
    private static String cacheKey(Long envId, String hostKey) {
        return envId + ":" + hostKey;
    }

    /** 单节点采集脚本：一次 SSH 同时返回主机指标和 docker stats。 */
    private static final String COLLECT_SCRIPT =
            "echo '===STAT===' ; head -1 /proc/stat ; " +
            "echo '===MEM===' ; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo ; " +
            "echo '===LOAD===' ; cat /proc/loadavg ; " +
            "echo '===DISK===' ; df -B1 -x tmpfs -x devtmpfs / | tail -1 ; " +
            "echo '===REPLICA_STATS===' ; docker stats --no-stream --format '{{json .}}' || true";

    @PostConstruct
    public void init() {
        int parallelism = monitorProperties.getCollector().getParallelism();
        this.executor = Executors.newFixedThreadPool(parallelism, new NamedThreadFactory("metrics-collect"));
        log.info("ClusterMetricsCollector 初始化: parallelism={}, nodeTimeoutMs={}",
                parallelism, monitorProperties.getCollector().getNodeTimeoutMs());
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 采集一个环境下全部节点的 host 指标（不含入库；由 Task 负责批量落库）。
     */
    public List<HostMetrics> collect(Environment environment) {
        List<NodeInfo> nodes = nodeDiscoveryService.discover(environment);
        if (nodes.isEmpty()) return new ArrayList<>();

        Long envId = environment.getId();

        // 预热 (envId,hostKey) -> hostId 映射
        ensureHostIdCache(envId, nodes);

        // 清理已下线节点的 stat 快照，防止内存泄露
        pruneSnapshots(envId, nodes);

        int nodeTimeoutMs = monitorProperties.getCollector().getNodeTimeoutMs();
        LocalDateTime collectedTime = LocalDateTime.now();
        List<CompletableFuture<HostMetrics>> futures = new ArrayList<>();
        for (NodeInfo node : nodes) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> sampleOne(envId, node, collectedTime, nodeTimeoutMs), executor));
        }

        List<HostMetrics> results = new ArrayList<>();
        for (CompletableFuture<HostMetrics> f : futures) {
            try {
                HostMetrics m = f.get(nodeTimeoutMs + 2000L, TimeUnit.MILLISECONDS);
                if (m != null) results.add(m);
            } catch (Exception e) {
                log.debug("节点采集任务超时/失败: {}", e.getMessage());
            }
        }
        return results;
    }

    private HostMetrics sampleOne(Long envId, NodeInfo node, LocalDateTime collectedTime, int timeoutMs) {
        String key = cacheKey(envId, node.getHostKey());
        try {
            SshResult res = sshExecutor.executeCommand(node.getSshHost(), COLLECT_SCRIPT, timeoutMs);
            if (!res.isSuccess() || !res.hasOutput()) {
                log.debug("节点[{}]采集失败: exit={}, err={}", node.getHostKey(), res.getExitCode(), res.getStderr());
                return null;
            }
            HostSampleParser.HostSampleRaw raw = parser.parse(res.getStdout());
            snapshotCache.putDockerStats(envId, node.getHostKey(),
                    extractSection(res.getStdout(), "REPLICA_STATS"));

            HostMetrics m = new HostMetrics();
            m.setEnvironmentId(envId);
            m.setCollectedTime(collectedTime);
            m.setLoad1(raw.getLoad1());
            m.setLoad5(raw.getLoad5());
            m.setLoad15(raw.getLoad15());

            // 内存
            if (raw.getMemTotal() != null) {
                m.setMemTotal(raw.getMemTotal());
                if (raw.getMemAvailable() != null) {
                    long used = raw.getMemTotal() - raw.getMemAvailable();
                    if (used < 0) used = 0;
                    m.setMemUsed(used);
                    if (raw.getMemTotal() > 0) {
                        m.setMemPercent(BigDecimal.valueOf((double) used / raw.getMemTotal() * 100.0)
                                .setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
            // 磁盘
            if (raw.getDiskTotal() != null && raw.getDiskUsed() != null) {
                m.setDiskTotal(raw.getDiskTotal());
                m.setDiskUsed(raw.getDiskUsed());
                if (raw.getDiskTotal() > 0) {
                    m.setDiskPercent(BigDecimal.valueOf((double) raw.getDiskUsed() / raw.getDiskTotal() * 100.0)
                            .setScale(2, RoundingMode.HALF_UP));
                }
            }

            // CPU 差值
            if (raw.hasCpu()) {
                StatSnapshot prev = statSnapshotMap.get(key);
                if (prev != null) {
                    m.setCpuPercent(HostSampleParser.calcCpuPercent(
                            prev.idle, prev.total, raw.getCpuIdle(), raw.getCpuTotal()));
                }
                statSnapshotMap.put(key, new StatSnapshot(raw.getCpuIdle(), raw.getCpuTotal()));
            }

            // 关联 hostId（懒创建）
            Long hostId = resolveHostId(envId, node);
            if (hostId == null) return null;
            m.setHostId(hostId);
            return m;
        } catch (Exception e) {
            log.debug("节点[{}]采集异常: {}", node.getHostKey(), e.getMessage());
            return null;
        }
    }

    private String extractSection(String output, String section) {
        if (output == null) return "";
        String marker = "===" + section + "===";
        int start = output.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = output.indexOf("===", start);
        return (end < 0 ? output.substring(start) : output.substring(start, end)).trim();
    }

    /**
     * 预热 (envId,hostKey) -> hostId 缓存。
     * 若当前节点都已在缓存，跳过 DB 查询。
     */
    private void ensureHostIdCache(Long envId, List<NodeInfo> nodes) {
        boolean allCached = nodes.stream()
                .allMatch(n -> hostIdCache.containsKey(cacheKey(envId, n.getHostKey())));
        if (allCached) return;

        List<HostInfo> rows = hostInfoMapper.selectByEnvironmentId(envId);
        for (HostInfo h : rows) {
            hostIdCache.put(cacheKey(envId, h.getHostKey()), h.getId());
        }
    }

    /**
     * 节点下线后清理其 stat 快照，避免长期运行下的内存泄露。
     * 只针对当前环境下已失联的 key 清理（不管其他环境）。
     */
    private void pruneSnapshots(Long envId, List<NodeInfo> currentNodes) {
        String prefix = envId + ":";
        Set<String> alive = new HashSet<>();
        for (NodeInfo n : currentNodes) {
            alive.add(cacheKey(envId, n.getHostKey()));
        }
        statSnapshotMap.keySet().removeIf(k -> k.startsWith(prefix) && !alive.contains(k));
    }

    /**
     * 拿 host_id：缓存 -> 懒创建。
     */
    private Long resolveHostId(Long envId, NodeInfo node) {
        String key = cacheKey(envId, node.getHostKey());
        Long id = hostIdCache.get(key);
        if (id != null) return id;

        // 懒创建一条基础 host_info（HostInfoRefreshTask 之后会补齐 diskTotal 等）
        try {
            HostInfo h = new HostInfo();
            h.setEnvironmentId(envId);
            h.setHostKey(node.getHostKey());
            h.setIp(node.getIp());
            h.setHostname(node.getHostname());
            h.setSwarmNodeId(node.getNodeId());
            h.setSwarmRole(node.getRole());
            h.setSwarmStatus(node.getStatus());
            h.setCpuCores(node.getCpuCores());
            h.setMemTotal(node.getMemTotal());
            h.setLastSeenTime(LocalDateTime.now());
            hostInfoMapper.insert(h);
            hostIdCache.put(key, h.getId());
            log.info("懒创建 host_info: envId={}, hostKey={}, id={}", envId, node.getHostKey(), h.getId());
            return h.getId();
        } catch (Exception e) {
            log.warn("懒创建 host_info 失败: {}", node.getHostKey(), e);
            return null;
        }
    }

    /** 手动清空缓存（供后续调试接口使用） */
    public void clearCache() {
        hostIdCache.clear();
        statSnapshotMap.clear();
    }

    // ---------- 内部类 ----------

    private static class StatSnapshot {
        final long idle;
        final long total;

        StatSnapshot(long idle, long total) {
            this.idle = idle;
            this.total = total;
        }
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
