package com.easy.cd.monitor.service.impl;

import com.easy.cd.entity.ReplicaMetrics;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.mapper.ReplicaMetricsMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.monitor.entity.HostInfo;
import com.easy.cd.monitor.entity.HostMetrics;
import com.easy.cd.monitor.mapper.HostInfoMapper;
import com.easy.cd.monitor.mapper.HostMetricsMapper;
import com.easy.cd.monitor.service.MonitorService;
import com.easy.cd.monitor.vo.HostMetricsRangeVO;
import com.easy.cd.monitor.vo.HostSummaryVO;
import com.easy.cd.monitor.vo.MetricSummaryVO;
import com.easy.cd.monitor.vo.ReplicaOnHostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 监控查询实现。
 * 时间桶聚合规则：
 * - 大图 range → (points, stepSec)：5m=(20,15) / 1h=(60,60) / 6h=(72,300) / 24h=(96,900) / 7d=(168,3600)
 * - Sparkline：固定 12 点 × 25s = 5 分钟滚动窗口
 * - 空桶输出 null（前端 SparkLine 已支持 null 断点）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorServiceImpl implements MonitorService {

    private static final int SPARK_POINTS = 12;
    private static final int SPARK_STEP_SEC = 25;

    /** “最新一行”查询的时间窗：开 5min 足够容错，同时避免扫描全部时序数据。 */
    private static final int LATEST_WINDOW_MIN = 5;

    private final HostInfoMapper hostInfoMapper;
    private final HostMetricsMapper hostMetricsMapper;
    private final ReplicaStatusMapper replicaStatusMapper;
    private final ReplicaMetricsMapper replicaMetricsMapper;

    // ==================== 1. 环境下所有宿主机 ====================
    @Override
    public List<HostSummaryVO> listHosts(Long environmentId) {
        if (environmentId == null) return Collections.emptyList();
        List<HostInfo> hosts = hostInfoMapper.selectByEnvironmentId(environmentId);
        if (hosts.isEmpty()) return Collections.emptyList();

        Map<Long, HostMetrics> latestMap = hostMetricsMapper
                .selectLatestPerHost(environmentId, LocalDateTime.now().minusMinutes(LATEST_WINDOW_MIN))
                .stream().collect(Collectors.toMap(HostMetrics::getHostId, Function.identity(), (a, b) -> a));

        Map<String, Integer> replicaCountByNode = loadReplicaCountByNode();

        return hosts.stream()
                .map(h -> buildHostSummary(h, latestMap.get(h.getId()), replicaCountByNode))
                .collect(Collectors.toList());
    }

    // ==================== 2. 单台宿主机 ====================
    @Override
    public HostSummaryVO getHost(Long hostId) {
        HostInfo host = hostInfoMapper.selectById(hostId);
        if (host == null) return null;
        HostMetrics latest = hostMetricsMapper.selectLatestByHostId(hostId,
                LocalDateTime.now().minusMinutes(LATEST_WINDOW_MIN));
        return buildHostSummary(host, latest, loadReplicaCountByNode());
    }

    // ==================== 3. 宿主机时序（大图） ====================
    @Override
    public HostMetricsRangeVO getHostMetrics(Long hostId, String range) {
        RangeSpec spec = parseRange(range);
        long nowSec = nowEpochSec();
        long startSec = nowSec - (long) spec.points * spec.stepSec;
        LocalDateTime from = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(startSec), ZoneId.systemDefault());
        LocalDateTime to = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(nowSec), ZoneId.systemDefault());

        List<HostMetrics> samples = hostMetricsMapper.selectByHostAndTimeRange(hostId, from, to);

        List<Long> timestamps = new ArrayList<>(spec.points);
        for (int i = 0; i < spec.points; i++) timestamps.add(startSec + (long) i * spec.stepSec);

        double[] cpuSum = new double[spec.points], memSum = new double[spec.points],
                 diskSum = new double[spec.points], load1Sum = new double[spec.points];
        int[] cpuCnt = new int[spec.points], memCnt = new int[spec.points],
              diskCnt = new int[spec.points], load1Cnt = new int[spec.points];

        for (HostMetrics m : samples) {
            long ts = epochSec(m.getCollectedTime());
            int idx = (int) ((ts - startSec) / spec.stepSec);
            if (idx < 0 || idx >= spec.points) continue;
            accumulate(m.getCpuPercent(), cpuSum, cpuCnt, idx);
            accumulate(m.getMemPercent(), memSum, memCnt, idx);
            accumulate(m.getDiskPercent(), diskSum, diskCnt, idx);
            accumulate(m.getLoad1(), load1Sum, load1Cnt, idx);
        }

        HostMetricsRangeVO vo = new HostMetricsRangeVO();
        vo.setRange(spec.range);
        vo.setStepSec(spec.stepSec);
        vo.setTimestamps(timestamps);
        vo.setCpu(toAvgList(cpuSum, cpuCnt));
        vo.setMem(toAvgList(memSum, memCnt));
        vo.setDisk(toAvgList(diskSum, diskCnt));
        vo.setLoad1(toAvgList(load1Sum, load1Cnt));
        return vo;
    }

    // ==================== 4. 节点上的容器 ====================
    @Override
    public List<ReplicaOnHostVO> getHostReplicas(Long hostId) {
        HostInfo host = hostInfoMapper.selectById(hostId);
        if (host == null || host.getHostname() == null) return Collections.emptyList();

        // 副本-主机关联：host_info.hostname == replica_status.node_name（用户确认策略）
        List<ReplicaStatus> replicas = replicaStatusMapper.selectByNodeName(host.getHostname());
        if (replicas.isEmpty()) return Collections.emptyList();

        // 一次性拉该节点所有副本的最新指标
        Map<String, ReplicaMetrics> latestMap = replicaMetricsMapper
                .selectLatestPerReplicaByNode(host.getHostname(),
                        LocalDateTime.now().minusMinutes(LATEST_WINDOW_MIN))
                .stream()
                .collect(Collectors.toMap(ReplicaMetrics::getReplicaId, Function.identity(), (a, b) -> a));

        return replicas.stream().map(rs -> {
            ReplicaOnHostVO vo = new ReplicaOnHostVO();
            vo.setReplicaId(rs.getReplicaId());
            vo.setReplicaName(rs.getReplicaName());
            vo.setServiceName(rs.getServiceName());
            vo.setStatus(rs.getStatus());
            ReplicaMetrics m = latestMap.get(rs.getReplicaId());
            if (m != null) {
                vo.setCpuPercent(m.getCpuPercent());
                vo.setMemPercent(m.getMemoryPercent());
            }
            return vo;
        }).collect(Collectors.toList());
    }

    // ==================== 5. 服务级指标概要 ====================
    @Override
    public MetricSummaryVO getServiceMetricSummary(Long serviceId) {
        long nowSec = nowEpochSec();
        long startSec = nowSec - (long) SPARK_POINTS * SPARK_STEP_SEC;
        LocalDateTime from = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(startSec), ZoneId.systemDefault());

        List<ReplicaMetrics> samples = replicaMetricsMapper.selectRecentByServiceId(serviceId, from);
        return buildSpark(samples, startSec, SPARK_STEP_SEC);
    }

    @Override
    public Map<Long, MetricSummaryVO> getServiceMetricSummaries(List<Long> serviceIds) {
        List<Long> ids = serviceIds == null ? Collections.emptyList() : serviceIds.stream()
                .filter(id -> id != null)
                .distinct()
                .limit(200)
                .collect(Collectors.toList());
        if (ids.isEmpty()) return Collections.emptyMap();

        long nowSec = nowEpochSec();
        long startSec = nowSec - (long) SPARK_POINTS * SPARK_STEP_SEC;
        LocalDateTime from = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(startSec), ZoneId.systemDefault());
        Map<Long, List<ReplicaMetrics>> grouped = replicaMetricsMapper
                .selectRecentByServiceIds(ids, from).stream()
                .collect(Collectors.groupingBy(ReplicaMetrics::getServiceId));

        Map<Long, MetricSummaryVO> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, buildSpark(grouped.getOrDefault(id, Collections.emptyList()),
                    startSec, SPARK_STEP_SEC));
        }
        return result;
    }

    // ==================== 6. 副本级指标概要 ====================
    @Override
    public MetricSummaryVO getReplicaMetricSummary(String replicaId, String range) {
        int stepSec = parseReplicaStepSec(range);
        long nowSec = nowEpochSec();
        long startSec = nowSec - (long) SPARK_POINTS * stepSec;
        LocalDateTime from = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(startSec), ZoneId.systemDefault());

        List<ReplicaMetrics> samples = replicaMetricsMapper.selectRecentByReplicaId(replicaId, from);
        return buildReplicaSummary(samples, startSec, stepSec);
    }

    @Override
    public Map<String, MetricSummaryVO> getReplicaMetricSummaries(Map<String, String> rangeByReplica) {
        if (rangeByReplica == null || rangeByReplica.isEmpty()) return Collections.emptyMap();

        Map<String, String> ranges = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rangeByReplica.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) continue;
            ranges.put(entry.getKey(), entry.getValue());
            if (ranges.size() >= 200) break;
        }
        if (ranges.isEmpty()) return Collections.emptyMap();

        int maxStepSec = SPARK_STEP_SEC;
        for (String range : ranges.values()) {
            maxStepSec = Math.max(maxStepSec, parseReplicaStepSec(range));
        }
        long nowSec = nowEpochSec();
        long earliestStartSec = nowSec - (long) SPARK_POINTS * maxStepSec;
        LocalDateTime earliestFrom = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(earliestStartSec), ZoneId.systemDefault());
        Map<String, List<ReplicaMetrics>> grouped = replicaMetricsMapper
                .selectRecentByReplicaIds(new ArrayList<>(ranges.keySet()), earliestFrom).stream()
                .collect(Collectors.groupingBy(ReplicaMetrics::getReplicaId));

        Map<String, MetricSummaryVO> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ranges.entrySet()) {
            int stepSec = parseReplicaStepSec(entry.getValue());
            long startSec = nowSec - (long) SPARK_POINTS * stepSec;
            List<ReplicaMetrics> samples = grouped.getOrDefault(entry.getKey(), Collections.emptyList())
                    .stream()
                    .filter(m -> m.getCollectedTime() != null && epochSec(m.getCollectedTime()) >= startSec)
                    .collect(Collectors.toList());
            result.put(entry.getKey(), buildReplicaSummary(samples, startSec, stepSec));
        }
        return result;
    }

    private MetricSummaryVO buildReplicaSummary(List<ReplicaMetrics> samples, long startSec, int stepSec) {
        MetricSummaryVO vo = buildSpark(samples, startSec, stepSec);
        if (!samples.isEmpty()) {
            ReplicaMetrics latest = samples.get(samples.size() - 1);
            vo.setMemoryUsage(latest.getMemoryUsage());
            vo.setMemoryLimit(latest.getMemoryLimit());
        }
        return vo;
    }

    /** 副本级 sparkline 时间窗 → stepSec（12 点固定） */
    private int parseReplicaStepSec(String range) {
        if (range == null) return SPARK_STEP_SEC;
        switch (range) {
            case "30m": return 150;   // 12 × 150s = 30 min
            case "2h":  return 600;   // 12 × 600s = 2 h
            case "5m":
            default:    return SPARK_STEP_SEC; // 12 × 25s = 5 min
        }
    }

    // ==================== 内部工具 ====================

    /** 组装 sparkline：把 ReplicaMetrics 采样按 stepSec 分桶，同时输出 AVG（副本间均值）与 MAX（副本间最高），各 12 点 */
    private MetricSummaryVO buildSpark(List<ReplicaMetrics> samples, long startSec, int stepSec) {
        List<Long> timestamps = new ArrayList<>(SPARK_POINTS);
        for (int i = 0; i < SPARK_POINTS; i++) timestamps.add(startSec + (long) i * stepSec);

        double[] cpuSum = new double[SPARK_POINTS], memSum = new double[SPARK_POINTS];
        int[] cpuCnt = new int[SPARK_POINTS], memCnt = new int[SPARK_POINTS];
        Double[] cpuMax = new Double[SPARK_POINTS], memMax = new Double[SPARK_POINTS];

        for (ReplicaMetrics m : samples) {
            long ts = epochSec(m.getCollectedTime());
            int idx = (int) ((ts - startSec) / stepSec);
            if (idx < 0 || idx >= SPARK_POINTS) continue;
            if (m.getCpuPercent() != null) {
                double v = m.getCpuPercent();
                cpuSum[idx] += v; cpuCnt[idx]++;
                if (cpuMax[idx] == null || v > cpuMax[idx]) cpuMax[idx] = v;
            }
            if (m.getMemoryPercent() != null) {
                double v = m.getMemoryPercent();
                memSum[idx] += v; memCnt[idx]++;
                if (memMax[idx] == null || v > memMax[idx]) memMax[idx] = v;
            }
        }

        List<Double> cpuSpark = toAvgListPrim(cpuSum, cpuCnt);
        List<Double> memSpark = toAvgListPrim(memSum, memCnt);
        List<Double> cpuSparkMax = toMaxList(cpuMax);
        List<Double> memSparkMax = toMaxList(memMax);

        MetricSummaryVO vo = new MetricSummaryVO();
        vo.setTimestamps(timestamps);
        vo.setCpuSpark(cpuSpark);
        vo.setMemSpark(memSpark);
        vo.setCpuSparkMax(cpuSparkMax);
        vo.setMemSparkMax(memSparkMax);
        // 当前值 = 最后一个非空桶
        vo.setCpuPercent(lastNonNull(cpuSpark));
        vo.setMemPercent(lastNonNull(memSpark));
        vo.setCpuPercentMax(lastNonNull(cpuSparkMax));
        vo.setMemPercentMax(lastNonNull(memSparkMax));
        return vo;
    }

    /** Double[]（桶内最大值，未命中为 null） → List<Double>（保留 null + round1） */
    private List<Double> toMaxList(Double[] arr) {
        List<Double> out = new ArrayList<>(arr.length);
        for (Double v : arr) out.add(v == null ? null : round1(v));
        return out;
    }

    private HostSummaryVO buildHostSummary(HostInfo host, HostMetrics latest, Map<String, Integer> replicaCountByNode) {
        HostSummaryVO vo = new HostSummaryVO();
        vo.setId(host.getId());
        vo.setEnvironmentId(host.getEnvironmentId());
        vo.setIp(host.getIp());
        vo.setHostname(host.getHostname());
        vo.setSwarmRole(host.getSwarmRole());
        vo.setSwarmStatus(host.getSwarmStatus());
        vo.setCpuCores(host.getCpuCores());
        vo.setMemTotal(host.getMemTotal());
        vo.setDiskTotal(host.getDiskTotal());
        if (latest != null) {
            vo.setCpuPercent(toDouble(latest.getCpuPercent()));
            vo.setMemPercent(toDouble(latest.getMemPercent()));
            vo.setDiskPercent(toDouble(latest.getDiskPercent()));
            vo.setLoad1(toDouble(latest.getLoad1()));
            vo.setLoad5(toDouble(latest.getLoad5()));
            vo.setLoad15(toDouble(latest.getLoad15()));
            vo.setMemUsed(latest.getMemUsed());
            vo.setDiskUsed(latest.getDiskUsed());
            vo.setCollectedTime(latest.getCollectedTime());
        }
        // 在线时长：createdTime → lastSeenTime（若为 null 则取 now）
        if (host.getCreatedTime() != null) {
            LocalDateTime end = host.getLastSeenTime() != null ? host.getLastSeenTime() : LocalDateTime.now();
            long sec = Duration.between(host.getCreatedTime(), end).getSeconds();
            vo.setUptimeSeconds(Math.max(sec, 0L));
        }
        if (host.getHostname() != null && replicaCountByNode != null) {
            vo.setReplicaCount(replicaCountByNode.getOrDefault(host.getHostname(), 0));
        } else {
            vo.setReplicaCount(0);
        }
        return vo;
    }

    /** 一次性拉取每个 node 的副本数，避免循环 N+1 */
    private Map<String, Integer> loadReplicaCountByNode() {
        List<Map<String, Object>> rows = replicaStatusMapper.selectReplicaCountGroupByNode();
        Map<String, Integer> result = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            Object name = row.get("nodeName");
            Object cnt = row.get("cnt");
            if (name != null && cnt != null) {
                result.put(name.toString(), ((Number) cnt).intValue());
            }
        }
        return result;
    }

    /** BigDecimal → 时间桶累加 */
    private void accumulate(BigDecimal val, double[] sum, int[] cnt, int idx) {
        if (val != null) { sum[idx] += val.doubleValue(); cnt[idx]++; }
    }

    /** double[] + int[] → List<Double>（空桶为 null） */
    private List<Double> toAvgList(double[] sum, int[] cnt) {
        List<Double> out = new ArrayList<>(sum.length);
        for (int i = 0; i < sum.length; i++) {
            out.add(cnt[i] > 0 ? round1(sum[i] / cnt[i]) : null);
        }
        return out;
    }

    /** 同上，用于 ReplicaMetrics 累加（Double 直接相加也走这个格式化） */
    private List<Double> toAvgListPrim(double[] sum, int[] cnt) {
        return toAvgList(sum, cnt);
    }

    private Double lastNonNull(List<Double> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Double v = list.get(i);
            if (v != null) return v;
        }
        return null;
    }

    private Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private long epochSec(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private long nowEpochSec() {
        return System.currentTimeMillis() / 1000;
    }

    // range → points, stepSec
    private RangeSpec parseRange(String range) {
        if (range == null) range = "5m";
        switch (range) {
            case "1h":  return new RangeSpec("1h",  60,  60);
            case "6h":  return new RangeSpec("6h",  72,  300);
            case "24h": return new RangeSpec("24h", 96,  900);
            case "7d":  return new RangeSpec("7d",  168, 3600);
            case "5m":
            default:    return new RangeSpec("5m",  20,  15);
        }
    }

    private static class RangeSpec {
        final String range;
        final int points;
        final int stepSec;
        RangeSpec(String range, int points, int stepSec) {
            this.range = range; this.points = points; this.stepSec = stepSec;
        }
    }
}
