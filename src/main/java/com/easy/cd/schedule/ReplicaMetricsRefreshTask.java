package com.easy.cd.schedule;

import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaMetrics;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaMetricsMapper;
import com.easy.cd.monitor.collector.ReplicaMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 副本指标刷新任务。
 *
 * 每个 monitor.collector.interval-ms 周期：
 *   1) 对每个 docker 环境调用 ReplicaMetricsCollector.collect 并发采集全部节点上的 docker stats
 *   2) 通过 container_id_short 关联 replica_status 组装 ReplicaMetrics
 *   3) 批量 INSERT 到 replica_metrics
 *
 * initialDelay 比 host 采集晚 5s，优先复用 host 采集顺带返回的 docker stats。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReplicaMetricsRefreshTask {

    private final EnvironmentMapper environmentMapper;
    private final ReplicaMetricsCollector collector;
    private final ReplicaMetricsMapper replicaMetricsMapper;

    /** 首次成功入库的 env id（仅用于首次日志） */
    private final Set<Long> firstInsertLogged = ConcurrentHashMap.newKeySet();

    /** 统计计数器 */
    private long tickCount = 0;
    private long totalRows = 0;
    private long totalCostMs = 0;
    private long maxCostMs = 0;
    private long emptyCount = 0;

    @Scheduled(fixedRateString = "${monitor.collector.interval-ms:10000}", initialDelay = 12000)
    public void refresh() {
        long start = System.currentTimeMillis();
        AtomicInteger roundInserted = new AtomicInteger(0);
        try {
            List<Environment> environments = environmentMapper.selectList(null);

            // 多环境并行采集 + 入库（单环境内部依旧多节点并发）
            environments.parallelStream()
                    .filter(env -> "docker".equals(env.getDeployType()))
                    .forEach(env -> processEnv(env, roundInserted));

            long cost = System.currentTimeMillis() - start;
            int inserted = roundInserted.get();
            tickCount++;
            totalRows += inserted;
            totalCostMs += cost;
            if (cost > maxCostMs) maxCostMs = cost;
            if (inserted == 0) emptyCount++;

            if (cost > 8000) {
                log.warn("replica 指标采集耗时 {}ms，接近 10s 采集周期", cost);
            }

            // 每 6 tick（默认约 60s）输出一次统计
            if (tickCount >= 6) {
                log.info("replica 指标采集统计: rows={}, avgCost={}ms, maxCost={}ms, emptyTicks={}",
                        totalRows, totalCostMs / tickCount, maxCostMs, emptyCount);
                tickCount = 0;
                totalRows = 0;
                totalCostMs = 0;
                maxCostMs = 0;
                emptyCount = 0;
            }
        } catch (Exception e) {
            log.error("replica 指标定时刷新异常", e);
        }
    }

    /** 单环境采集入库，供并行流调用；内部发生异常不影响其他环境 */
    private void processEnv(Environment env, AtomicInteger roundInserted) {
        try {
            List<ReplicaMetrics> batch = collector.collect(env);
            if (batch.isEmpty()) return;
            replicaMetricsMapper.batchInsert(batch);
            roundInserted.addAndGet(batch.size());

            // 首次入库日志（便于验证）
            if (firstInsertLogged.add(env.getId())) {
                ReplicaMetrics sample = batch.get(0);
                log.info("【首次采集-replica】env={}, replicas={}, sample: name={}, cpu={}, memUsage={}, memLimit={}, memPercent={}",
                        env.getName(), batch.size(), sample.getReplicaName(),
                        sample.getCpuPercent(), sample.getMemoryUsage(),
                        sample.getMemoryLimit(), sample.getMemoryPercent());
            }
        } catch (Exception e) {
            log.error("环境[{}]replica 指标采集失败", env.getName(), e);
        }
    }
}
