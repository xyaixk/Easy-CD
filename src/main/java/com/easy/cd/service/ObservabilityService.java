package com.easy.cd.service;

import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.vo.LogContextVO;
import com.easy.cd.vo.LogHistogramBucketVO;
import com.easy.cd.vo.LogItemVO;
import com.easy.cd.vo.LogQueryResultVO;

import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;

/**
 * 可观测性（日志）服务接口。数据源：Loki。
 */
public interface ObservabilityService {

    /**
     * 游标查询日志：backward 方向取最新一批，beforeNanos 作为向更早翻页的游标。
     */
    LogQueryResultVO queryLogs(LogQueryDTO query);

    /**
     * 日志量直方图：按 detected_level 分桶计数（与 queryLogs 相同筛选条件）。
     */
    List<LogHistogramBucketVO> histogram(LogQueryDTO query);

    /**
     * 上下文查询：同一容器流锚点时间前后各 limit 行（不带级别/关键字过滤）。
     */
    LogContextVO queryContext(Long envId, String container, String tsNanos, Integer limit);

    /**
     * trace 聚合查询：环境内所有服务命中该 traceId 的日志，时间正序。
     */
    List<LogItemVO> queryTrace(Long envId, String traceId, String from, String to);

    /**
     * 实时读取指定环境 Loki 的 service 标签值。
     */
    List<String> listServices(Long envId);

    /**
     * 按筛选条件导出日志文本，受 max-result-window 限制。
     */
    void exportLogsCsv(LogQueryDTO query, HttpServletResponse response);

    /**
     * 构造 Loki tail WebSocket URI（ws/wss），供实时推送代理使用。
     */
    URI buildTailUri(LogQueryDTO query);

    /**
     * 解析 Loki tail 推送帧为日志条目（时间正序）。
     */
    List<LogItemVO> parseTailFrame(String payload);
}
