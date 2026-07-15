package com.easy.cd.monitor.controller;

import com.easy.cd.common.Result;
import com.easy.cd.monitor.service.MonitorService;
import com.easy.cd.monitor.vo.HostMetricsRangeVO;
import com.easy.cd.monitor.vo.HostSummaryVO;
import com.easy.cd.monitor.vo.MetricSummaryVO;
import com.easy.cd.monitor.vo.ReplicaOnHostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 监控查询接口。
 * 字段与前端 frontend/src/api/monitor.js 严格对齐。
 */
@Slf4j
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    /** 环境下所有宿主机 + 最新指标 */
    @GetMapping("/hosts")
    public Result<List<HostSummaryVO>> listHosts(@RequestParam Long environmentId) {
        return Result.success(monitorService.listHosts(environmentId));
    }

    /** 单节点静态信息 + 最新指标 */
    @GetMapping("/hosts/{id}")
    public Result<HostSummaryVO> getHost(@PathVariable Long id) {
        return Result.success(monitorService.getHost(id));
    }

    /** 节点时序（大图，range=5m/1h/6h/24h/7d） */
    @GetMapping("/hosts/{id}/metrics")
    public Result<HostMetricsRangeVO> getHostMetrics(@PathVariable Long id,
                                                     @RequestParam(defaultValue = "5m") String range) {
        return Result.success(monitorService.getHostMetrics(id, range));
    }

    /** 节点上的容器列表 */
    @GetMapping("/hosts/{id}/replicas")
    public Result<List<ReplicaOnHostVO>> getHostReplicas(@PathVariable Long id) {
        return Result.success(monitorService.getHostReplicas(id));
    }

    /** 服务级指标概要（sparkline + 当前值） */
    @GetMapping("/services/{serviceId}/metrics/summary")
    public Result<MetricSummaryVO> getServiceMetricSummary(@PathVariable Long serviceId) {
        return Result.success(monitorService.getServiceMetricSummary(serviceId));
    }

    /** 首页批量查询服务指标，避免按服务逐个请求。 */
    @PostMapping("/services/metrics/summaries")
    public Result<Map<Long, MetricSummaryVO>> getServiceMetricSummaries(@RequestBody List<Long> serviceIds) {
        return Result.success(monitorService.getServiceMetricSummaries(serviceIds));
    }

    /** 副本级指标概要（sparkline + 当前值），range=5m/30m/2h */
    @GetMapping("/replicas/{replicaId}/metrics/summary")
    public Result<MetricSummaryVO> getReplicaMetricSummary(@PathVariable String replicaId,
                                                           @RequestParam(defaultValue = "5m") String range) {
        return Result.success(monitorService.getReplicaMetricSummary(replicaId, range));
    }

    /** 副本弹窗批量查询指标；请求体 key=replicaId，value=5m/30m/2h。 */
    @PostMapping("/replicas/metrics/summaries")
    public Result<Map<String, MetricSummaryVO>> getReplicaMetricSummaries(
            @RequestBody Map<String, String> rangeByReplica) {
        return Result.success(monitorService.getReplicaMetricSummaries(rangeByReplica));
    }
}
