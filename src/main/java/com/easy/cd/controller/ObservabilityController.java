package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.service.ObservabilityService;
import com.easy.cd.vo.LogPageVO;
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
 * 数据源：OpenSearch（索引模式 logs-{env}-*）。
 */
@Slf4j
@RestController
@RequestMapping("/observability/logs")
@RequiredArgsConstructor
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    /**
     * 分页查询日志。
     * GET 用于轻量场景（前端用 query 拼接），复杂筛选可走 POST。
     */
    @PostMapping("/search")
    public Result<LogPageVO> search(@RequestBody LogQueryDTO query) {
        return Result.success(observabilityService.searchLogs(query));
    }

    /**
     * 列出指定环境下的服务名候选（读 app_service 表）。
     */
    @GetMapping("/services")
    public Result<List<String>> listServices(@RequestParam("envId") Long envId) {
        return Result.success(observabilityService.listServices(envId));
    }

    /**
     * 按筛选条件导出 CSV（受 max-result-window 限制）。
     */
    @PostMapping("/export")
    public void export(@RequestBody LogQueryDTO query, HttpServletResponse response) {
        observabilityService.exportLogsCsv(query, response);
    }
}
