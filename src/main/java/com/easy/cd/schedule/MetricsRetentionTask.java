package com.easy.cd.schedule;

import com.easy.cd.monitor.config.MonitorProperties;
import com.easy.cd.monitor.config.MonitorProperties.Retention;
import com.easy.cd.monitor.retention.MetricsRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 指标分层降采样 + 超期清理任务。
 *
 * 采集周期默认为 10s，每小时的 :05 分执行一次，一次处理 4 个 tier 的 1 小时窗口：
 *  - Tier1: 25h→24h 段 10s → 1min
 *  - Tier2: 73h→72h 段 1min → 5min
 *  - Tier3: 169h→168h 段 5min → 15min
 *  - Tier4: 721h→720h 段 15min → 1h
 *  - Purge: 删除 >maxHours 的记录
 *
 * 单次任务只处理一个 1 小时的窗口，不做历史积压 catch-up，避免长时扫全表。
 * 如需补历史，未来可加手动接口。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitor.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MetricsRetentionTask {

    private final MetricsRetentionService retentionService;
    private final MonitorProperties monitorProperties;

    /** 每小时 :05 触发，避开整点繁忙时段 */
    @Scheduled(cron = "0 5 * * * *")
    public void run() {
        if (!monitorProperties.isEnabled()) return;
        Retention cfg = monitorProperties.getRetention();
        if (cfg == null || !cfg.isEnabled()) return;

        long start = System.currentTimeMillis();
        // 截到整小时，方便观察对齐
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        int win = Math.max(1, cfg.getWindowHours());

        try {
            // Tier1: 3s -> 1min
            processTier("host-tier1", now, cfg.getTier1Hours(), win, cfg.getTier1StepSeconds(), true, false);
            processTier("replica-tier1", now, cfg.getTier1Hours(), win, cfg.getTier1StepSeconds(), false, true);
            // Tier2: 1min -> 5min
            processTier("host-tier2", now, cfg.getTier2Hours(), win, cfg.getTier2StepSeconds(), true, false);
            processTier("replica-tier2", now, cfg.getTier2Hours(), win, cfg.getTier2StepSeconds(), false, true);
            // Tier3: 5min -> 15min
            processTier("host-tier3", now, cfg.getTier3Hours(), win, cfg.getTier3StepSeconds(), true, false);
            processTier("replica-tier3", now, cfg.getTier3Hours(), win, cfg.getTier3StepSeconds(), false, true);
            // Tier4: 15min -> 1h
            processTier("host-tier4", now, cfg.getTier4Hours(), win, cfg.getTier4StepSeconds(), true, false);
            processTier("replica-tier4", now, cfg.getTier4Hours(), win, cfg.getTier4StepSeconds(), false, true);

            // Purge 超期
            LocalDateTime cutoff = now.minusHours(cfg.getMaxHours());
            int hostPurged = retentionService.purgeHostMetricsBefore(cutoff);
            int replicaPurged = retentionService.purgeReplicaMetricsBefore(cutoff);
            if (hostPurged > 0 || replicaPurged > 0) {
                log.info("超期清理: host_metrics 删除 {} 行, replica_metrics 删除 {} 行, cutoff={}",
                        hostPurged, replicaPurged, cutoff);
            }

            long cost = System.currentTimeMillis() - start;
            log.info("指标保留任务完成: 耗时 {}ms", cost);
        } catch (Exception e) {
            log.error("指标保留任务异常", e);
        }
    }

    /**
     * 处理某档 tier 的一小时窗口。
     * @param tag        日志标签
     * @param now        整小时对齐的当前时间
     * @param tierHours  该档保留原粒度的时长
     * @param winHours   处理窗口小时数
     * @param stepSec    降采样目标步长
     * @param doHost     是否处理 host
     * @param doReplica  是否处理 replica
     */
    private void processTier(String tag, LocalDateTime now, int tierHours, int winHours,
                             int stepSec, boolean doHost, boolean doReplica) {
        LocalDateTime to = now.minusHours(tierHours);
        LocalDateTime from = to.minusHours(winHours);
        try {
            int rows = 0;
            if (doHost) {
                rows = retentionService.downsampleHostMetrics(from, to, stepSec);
            } else if (doReplica) {
                rows = retentionService.downsampleReplicaMetrics(from, to, stepSec);
            }
            if (rows > 0) {
                log.info("降采样[{}]: 窗口 [{}, {}), step={}s, 生成 {} 行", tag, from, to, stepSec, rows);
            } else {
                log.debug("降采样[{}]: 窗口 [{}, {}), step={}s, 无数据", tag, from, to, stepSec);
            }
        } catch (Exception e) {
            log.warn("降采样[{}]失败: 窗口 [{}, {}), err={}", tag, from, to, e.getMessage());
        }
    }
}
