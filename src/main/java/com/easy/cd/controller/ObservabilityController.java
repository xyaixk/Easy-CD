package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.service.ObservabilityService;
import com.easy.cd.vo.LogContextVO;
import com.easy.cd.vo.LogHistogramBucketVO;
import com.easy.cd.vo.LogItemVO;
import com.easy.cd.vo.LogQueryResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * 可观测性（日志）查询接口。
 * 数据源：Loki。查询范式对齐 Grafana Explore（游标翻页 / 直方图 / 上下文 / trace）。
 * 实时推送走 WebSocket /logs/tail（LogTailWebSocketHandler）。
 */
@Slf4j
@RestController
@RequestMapping("/observability/logs")
@RequiredArgsConstructor
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    /**
     * 游标查询日志：首屏不带 beforeNanos，向更早翻页传上一批最旧一条的 tsNanos。
     */
    @PostMapping("/query")
    public Result<LogQueryResultVO> query(@RequestBody LogQueryDTO query) {
        return Result.success(observabilityService.queryLogs(query));
    }

    /**
     * 日志量直方图（与 query 相同筛选条件，按 detected_level 分桶）。
     */
    @PostMapping("/histogram")
    public Result<List<LogHistogramBucketVO>> histogram(@RequestBody LogQueryDTO query) {
        return Result.success(observabilityService.histogram(query));
    }

    /**
     * 上下文查询：同一容器流锚点时间前后各 limit 行。
     */
    @GetMapping("/context")
    public Result<LogContextVO> context(@RequestParam("envId") Long envId,
                                        @RequestParam("container") String container,
                                        @RequestParam("tsNanos") String tsNanos,
                                        @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(observabilityService.queryContext(envId, container, tsNanos, limit));
    }

    /**
     * trace 聚合查询：环境内所有服务命中该 traceId 的日志，时间正序。
     */
    @GetMapping("/trace")
    public Result<List<LogItemVO>> trace(@RequestParam("envId") Long envId,
                                         @RequestParam("traceId") String traceId,
                                         @RequestParam(value = "from", required = false) String from,
                                         @RequestParam(value = "to", required = false) String to) {
        return Result.success(observabilityService.queryTrace(envId, traceId, from, to));
    }

    /**
     * 实时读取指定环境 Loki 的 service 标签值。
     */
    @GetMapping("/services")
    public Result<List<String>> listServices(@RequestParam("envId") Long envId) {
        return Result.success(observabilityService.listServices(envId));
    }

    /**
     * 按筛选条件导出日志（受 max-result-window 限制）。
     */
    @PostMapping("/export")
    public void export(@RequestBody LogQueryDTO query, HttpServletResponse response) {
        observabilityService.exportLogsCsv(query, response);
    }
}
