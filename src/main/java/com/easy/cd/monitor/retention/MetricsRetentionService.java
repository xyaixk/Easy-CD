package com.easy.cd.monitor.retention;

import com.easy.cd.entity.ReplicaMetrics;
import com.easy.cd.mapper.ReplicaMetricsMapper;
import com.easy.cd.monitor.entity.HostMetrics;
import com.easy.cd.monitor.mapper.HostMetricsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标分层降采样与超期删除。
 *
 * 核心思路：
 *  - 对给定时间窗口 [from, to)，把窗口内的数据在内存里按 (归属键, stepSeconds bucket) 聚合，
 *    再事务内 DELETE 原始 + batchInsert 聚合结果。
 *  - 幂等：如窗口数据已经是 stepSeconds 粒度，再走一次聚合还是每桶 1 行，结果不变。
 *  - 聚合函数：数值指标（cpu/load/mem/disk）取 AVG；归属字段（host_id, replica_status_id 等）保持。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsRetentionService {

    private final HostMetricsMapper hostMetricsMapper;
    private final ReplicaMetricsMapper replicaMetricsMapper;

    // ==================== host_metrics ====================

    /**
     * 对 [from, to) 内的 host_metrics 按 stepSeconds 聚合。
     * 返回：本次生成的聚合行数（0 表示窗口为空）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int downsampleHostMetrics(LocalDateTime from, LocalDateTime to, int stepSeconds) {
        List<HostMetrics> raw = hostMetricsMapper.selectByTimeRange(from, to);
        if (raw.isEmpty()) return 0;

        // 已经是 stepSeconds 粒度且行数 == bucket 数 时，可以跳过（额外优化，但不做也正确）
        // 直接聚合
        Map<String, HostAgg> buckets = new LinkedHashMap<>();
        for (HostMetrics m : raw) {
            long bucket = bucketOf(m.getCollectedTime(), stepSeconds);
            String key = m.getHostId() + "#" + bucket;
            buckets.computeIfAbsent(key, k -> new HostAgg(m.getHostId(), m.getEnvironmentId(), bucket))
                    .add(m);
        }

        List<HostMetrics> result = new ArrayList<>(buckets.size());
        for (HostAgg agg : buckets.values()) {
            result.add(agg.finish());
        }

        hostMetricsMapper.deleteByTimeRange(from, to);
        // 分批 insert，防止单条 SQL 过大
        for (int i = 0; i < result.size(); i += 500) {
            int end = Math.min(i + 500, result.size());
            hostMetricsMapper.batchInsert(result.subList(i, end));
        }
        return result.size();
    }

    /** 物理删除早于 cutoff 的 host_metrics */
    public int purgeHostMetricsBefore(LocalDateTime cutoff) {
        return hostMetricsMapper.deleteBefore(cutoff);
    }

    // ==================== replica_metrics ====================

    /**
     * 对 [from, to) 内的 replica_metrics 按 stepSeconds 聚合。
     */
    @Transactional(rollbackFor = Exception.class)
    public int downsampleReplicaMetrics(LocalDateTime from, LocalDateTime to, int stepSeconds) {
        List<ReplicaMetrics> raw = replicaMetricsMapper.selectByTimeRange(from, to);
        if (raw.isEmpty()) return 0;

        Map<String, ReplicaAgg> buckets = new LinkedHashMap<>();
        for (ReplicaMetrics m : raw) {
            long bucket = bucketOf(m.getCollectedTime(), stepSeconds);
            String key = m.getReplicaStatusId() + "#" + bucket;
            buckets.computeIfAbsent(key, k -> new ReplicaAgg(m, bucket)).add(m);
        }

        List<ReplicaMetrics> result = new ArrayList<>(buckets.size());
        for (ReplicaAgg agg : buckets.values()) {
            result.add(agg.finish());
        }

        replicaMetricsMapper.deleteByTimeRange(from, to);
        for (int i = 0; i < result.size(); i += 500) {
            int end = Math.min(i + 500, result.size());
            replicaMetricsMapper.batchInsert(result.subList(i, end));
        }
        return result.size();
    }

    /** 物理删除早于 cutoff 的 replica_metrics */
    public int purgeReplicaMetricsBefore(LocalDateTime cutoff) {
        return replicaMetricsMapper.deleteBefore(cutoff);
    }

    // ==================== 工具方法 ====================

    /** 将 collected_time 归到 stepSeconds bucket 的开始秒（UTC 仅作数轴基点，与时区无关） */
    private long bucketOf(LocalDateTime t, int stepSeconds) {
        long ts = t.toEpochSecond(ZoneOffset.UTC);
        return (ts / stepSeconds) * stepSeconds;
    }

    /** epoch 秒转回 LocalDateTime（保持与 bucketOf 的 UTC 基点一致） */
    private static LocalDateTime bucketToTime(long bucketSec) {
        return LocalDateTime.ofEpochSecond(bucketSec, 0, ZoneOffset.UTC);
    }

    /** BigDecimal 累计求平均 */
    private static BigDecimal avgBD(BigDecimal sum, int count) {
        if (count <= 0 || sum == null) return null;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** Long 累计求平均 */
    private static Long avgLong(long sum, int count) {
        if (count <= 0) return null;
        return sum / count;
    }

    // ==================== host 聚合器 ====================

    private static class HostAgg {
        final Long hostId;
        final Long environmentId;
        final long bucketSec;
        int cnt = 0;

        BigDecimal cpuSum = BigDecimal.ZERO; int cpuN = 0;
        BigDecimal l1Sum = BigDecimal.ZERO; int l1N = 0;
        BigDecimal l5Sum = BigDecimal.ZERO; int l5N = 0;
        BigDecimal l15Sum = BigDecimal.ZERO; int l15N = 0;
        long memUsedSum = 0; int memUsedN = 0;
        long memTotalSum = 0; int memTotalN = 0;
        BigDecimal memPctSum = BigDecimal.ZERO; int memPctN = 0;
        long diskUsedSum = 0; int diskUsedN = 0;
        long diskTotalSum = 0; int diskTotalN = 0;
        BigDecimal diskPctSum = BigDecimal.ZERO; int diskPctN = 0;

        HostAgg(Long hostId, Long envId, long bucketSec) {
            this.hostId = hostId;
            this.environmentId = envId;
            this.bucketSec = bucketSec;
        }

        void add(HostMetrics m) {
            cnt++;
            if (m.getCpuPercent() != null) { cpuSum = cpuSum.add(m.getCpuPercent()); cpuN++; }
            if (m.getLoad1() != null) { l1Sum = l1Sum.add(m.getLoad1()); l1N++; }
            if (m.getLoad5() != null) { l5Sum = l5Sum.add(m.getLoad5()); l5N++; }
            if (m.getLoad15() != null) { l15Sum = l15Sum.add(m.getLoad15()); l15N++; }
            if (m.getMemUsed() != null) { memUsedSum += m.getMemUsed(); memUsedN++; }
            if (m.getMemTotal() != null) { memTotalSum += m.getMemTotal(); memTotalN++; }
            if (m.getMemPercent() != null) { memPctSum = memPctSum.add(m.getMemPercent()); memPctN++; }
            if (m.getDiskUsed() != null) { diskUsedSum += m.getDiskUsed(); diskUsedN++; }
            if (m.getDiskTotal() != null) { diskTotalSum += m.getDiskTotal(); diskTotalN++; }
            if (m.getDiskPercent() != null) { diskPctSum = diskPctSum.add(m.getDiskPercent()); diskPctN++; }
        }

        HostMetrics finish() {
            HostMetrics m = new HostMetrics();
            m.setHostId(hostId);
            m.setEnvironmentId(environmentId);
            m.setCpuPercent(avgBD(cpuSum, cpuN));
            m.setLoad1(avgBD(l1Sum, l1N));
            m.setLoad5(avgBD(l5Sum, l5N));
            m.setLoad15(avgBD(l15Sum, l15N));
            m.setMemUsed(avgLong(memUsedSum, memUsedN));
            m.setMemTotal(avgLong(memTotalSum, memTotalN));
            m.setMemPercent(avgBD(memPctSum, memPctN));
            m.setDiskUsed(avgLong(diskUsedSum, diskUsedN));
            m.setDiskTotal(avgLong(diskTotalSum, diskTotalN));
            m.setDiskPercent(avgBD(diskPctSum, diskPctN));
            m.setCollectedTime(bucketToTime(bucketSec));
            return m;
        }
    }

    // ==================== replica 聚合器 ====================

    private static class ReplicaAgg {
        final long bucketSec;
        // 归属字段：取窗口内第一行（同一 replica_status_id 应保持一致）
        final Long replicaStatusId;
        final Long serviceId;
        final String replicaId;
        final String replicaName;
        final String platform;
        final String nodeName;
        final String status;

        double cpuSum = 0; int cpuN = 0;
        long memUsageSum = 0; int memUsageN = 0;
        long memLimitSum = 0; int memLimitN = 0;
        double memPctSum = 0; int memPctN = 0;

        ReplicaAgg(ReplicaMetrics first, long bucketSec) {
            this.bucketSec = bucketSec;
            this.replicaStatusId = first.getReplicaStatusId();
            this.serviceId = first.getServiceId();
            this.replicaId = first.getReplicaId();
            this.replicaName = first.getReplicaName();
            this.platform = first.getPlatform();
            this.nodeName = first.getNodeName();
            this.status = first.getStatus();
        }

        void add(ReplicaMetrics m) {
            if (m.getCpuPercent() != null) { cpuSum += m.getCpuPercent(); cpuN++; }
            if (m.getMemoryUsage() != null) { memUsageSum += m.getMemoryUsage(); memUsageN++; }
            if (m.getMemoryLimit() != null) { memLimitSum += m.getMemoryLimit(); memLimitN++; }
            if (m.getMemoryPercent() != null) { memPctSum += m.getMemoryPercent(); memPctN++; }
        }

        ReplicaMetrics finish() {
            ReplicaMetrics m = new ReplicaMetrics();
            m.setReplicaStatusId(replicaStatusId);
            m.setServiceId(serviceId);
            m.setReplicaId(replicaId);
            m.setReplicaName(replicaName);
            m.setPlatform(platform);
            m.setNodeName(nodeName);
            m.setStatus(status);
            if (cpuN > 0) m.setCpuPercent(round2(cpuSum / cpuN));
            if (memUsageN > 0) m.setMemoryUsage(memUsageSum / memUsageN);
            if (memLimitN > 0) m.setMemoryLimit(memLimitSum / memLimitN);
            if (memPctN > 0) m.setMemoryPercent(round2(memPctSum / memPctN));
            m.setCollectedTime(bucketToTime(bucketSec));
            return m;
        }

        private static Double round2(double v) {
            return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
    }
}
