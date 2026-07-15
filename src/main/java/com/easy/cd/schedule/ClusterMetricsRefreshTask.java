package com.easy.cd.schedule;

import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.monitor.collector.ClusterMetricsCollector;
import com.easy.cd.monitor.entity.HostMetrics;
import com.easy.cd.monitor.mapper.HostMetricsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 集群 host 指标刷新任务。
 *
 * 每个 monitor.collector.interval-ms 周期：
 *   1) 对每个环境调用 ClusterMetricsCollector.collect 并发采集全部节点
 *   2) 批量 INSERT 到 host_metrics
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ClusterMetricsRefreshTask {

    private final EnvironmentMapper environmentMapper;
    private final ClusterMetricsCollector collector;
    private final HostMetricsMapper hostMetricsMapper;

    /** 首次成功入库的 env id（仅用于首次日志） */
    private final java.util.Set<Long> firstInsertLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 统计计数器 */
    private long tickCount = 0;
    private long totalRows = 0;
    private long totalCostMs = 0;
    private long maxCostMs = 0;
    private long emptyCount = 0;

    @Scheduled(fixedRateString = "${monitor.collector.interval-ms:10000}", initialDelay = 7000)
    public void refresh() {
        long start = System.currentTimeMillis();
        int roundInserted = 0;
        try {
            List<Environment> environments = environmentMapper.selectList(null);
            for (Environment env : environments) {
                if (!"docker".equals(env.getDeployType())) continue;

                try {
                    List<HostMetrics> batch = collector.collect(env);
                    if (batch.isEmpty()) continue;
                    hostMetricsMapper.batchInsert(batch);
                    roundInserted += batch.size();

                    // 首次入库日志（便于验证）
                    if (firstInsertLogged.add(env.getId())) {
                        HostMetrics sample = batch.get(0);
                        log.info("【首次采集】env={}, nodes={}, sample: hostId={}, cpu={}, mem={}%, disk={}%, load1={}",
                                env.getName(), batch.size(), sample.getHostId(),
                                sample.getCpuPercent(), sample.getMemPercent(),
                                sample.getDiskPercent(), sample.getLoad1());
                    }
                } catch (Exception e) {
                    log.error("环境[{}]host 指标采集失败", env.getName(), e);
                }
            }

            long cost = System.currentTimeMillis() - start;
            tickCount++;
            totalRows += roundInserted;
            totalCostMs += cost;
            if (cost > maxCostMs) maxCostMs = cost;
            if (roundInserted == 0) emptyCount++;

            if (cost > 8000) {
                log.warn("host 指标采集耗时 {}ms，接近 10s 采集周期", cost);
            }

            // 每 6 tick（默认约 60s）输出一次统计
            if (tickCount >= 6) {
                log.info("host 指标采集统计: rows={}, avgCost={}ms, maxCost={}ms, emptyTicks={}",
                        totalRows, totalCostMs / tickCount, maxCostMs, emptyCount);
                tickCount = 0;
                totalRows = 0;
                totalCostMs = 0;
                maxCostMs = 0;
                emptyCount = 0;
            }
        } catch (Exception e) {
            log.error("host 指标定时刷新异常", e);
        }
    }
}
