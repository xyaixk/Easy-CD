package com.easy.cd.service;

import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.vo.LogPageVO;

import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * 可观测性（日志）服务接口。
 */
public interface ObservabilityService {

    /**
     * 分页查询日志。
     */
    LogPageVO searchLogs(LogQueryDTO query);

    /**
     * 列出指定环境下的服务名列表（直接读 app_service 表）。
     */
    List<String> listServices(Long envId);

    /**
     * 按筛选条件导出 CSV，受 max-result-window 限制。
     */
    void exportLogsCsv(LogQueryDTO query, HttpServletResponse response);
}
