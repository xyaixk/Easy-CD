package com.easy.cd.dto;

import lombok.Data;

import java.util.List;

/**
 * 日志查询请求参数。
 * 与前端 LogSearchDialog.vue 的字段一一对应。
 */
@Data
public class LogQueryDTO {

    /** 环境 ID（优先，与 envName 二选一） */
    private Long envId;

    /** 环境名（用于拼接索引 logs-{envName}-*） */
    private String envName;

    /** 服务列表（terms on service.keyword），多选 */
    private List<String> services;

    /** 级别列表（terms on level_text.keyword），多选，如 INFO/WARN/ERROR/DEBUG */
    private List<String> levels;

    /** 关键字（match on message + biz_message） */
    private String keyword;

    /** 链路 ID（term on traceId.keyword） */
    private String traceId;

    /** Logger 类（prefix on logger.keyword） */
    private String logger;

    /** 容器名（wildcard on container_name.keyword） */
    private String containerName;

    /** 线程（prefix on thread.keyword） */
    private String thread;

    /** 时间范围预设：15m / 1h / 6h / 24h / custom */
    private String timeRange;

    /** 自定义起始时间（timeRange=custom 时必填）：ISO-8601 或 yyyy-MM-dd HH:mm */
    private String from;

    /** 自定义结束时间 */
    private String to;

    /** 页码，从 1 开始 */
    private Integer page = 1;

    /** 每页条数；-1 表示全部（受 max-result-window 限制） */
    private Integer size = 100;
}
