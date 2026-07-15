package com.easy.cd.monitor.service;

import com.easy.cd.monitor.vo.HostMetricsRangeVO;
import com.easy.cd.monitor.vo.HostSummaryVO;
import com.easy.cd.monitor.vo.MetricSummaryVO;
import com.easy.cd.monitor.vo.ReplicaOnHostVO;

import java.util.List;
import java.util.Map;

/**
 * 监控查询接口层。
 * 采集侧数据由 ClusterMetricsCollector / ReplicaMetricsCollector 定时写入，
 * 本接口只负责组装 + 降采样 + 输出给前端。
 */
public interface MonitorService {

    /** 环境下所有宿主机 + 最新一行指标 */
    List<HostSummaryVO> listHosts(Long environmentId);

    /** 单台宿主机静态信息 + 最新一行指标 */
    HostSummaryVO getHost(Long hostId);

    /** 单台宿主机时序（大图，range 决定桶数与步长） */
    HostMetricsRangeVO getHostMetrics(Long hostId, String range);

    /** 节点上的容器列表（副本状态 + 副本最新一行指标） */
    List<ReplicaOnHostVO> getHostReplicas(Long hostId);

    /** 服务级指标概要（sparkline + 当前值），12 点 × 25s 窗口 */
    MetricSummaryVO getServiceMetricSummary(Long serviceId);

    /** 批量服务级指标概要。 */
    Map<Long, MetricSummaryVO> getServiceMetricSummaries(List<Long> serviceIds);

    /** 副本级指标概要，12 点固定，range 控制时间窗（5m/30m/2h） */
    MetricSummaryVO getReplicaMetricSummary(String replicaId, String range);

    /** 批量副本级指标概要，key=replicaId，value=时间窗。 */
    Map<String, MetricSummaryVO> getReplicaMetricSummaries(Map<String, String> rangeByReplica);
}
