package com.easy.cd.monitor.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宿主机概要（列表页 + 详情弹窗头部共用）。
 *
 * 静态部分来自 host_info，动态部分取自 host_metrics 最新一行。
 * 与前端 monitor.js listHosts / getHost 的 mock 结构严格对齐。
 */
@Data
public class HostSummaryVO {

    // ==================== 静态信息（host_info） ====================
    private Long id;
    private Long environmentId;
    private String ip;
    private String hostname;
    private String swarmRole;
    private String swarmStatus;
    private Integer cpuCores;
    private Long memTotal;
    private Long diskTotal;

    // ==================== 最新指标（host_metrics） ====================
    private Double cpuPercent;
    private Double memPercent;
    private Double diskPercent;
    private Double load1;
    private Double load5;
    private Double load15;
    private Long memUsed;
    private Long diskUsed;
    private LocalDateTime collectedTime;

    // ==================== 附加信息 ====================
    /** 平台观察到的在线时长（秒）：createdTime → lastSeenTime */
    private Long uptimeSeconds;

    /** 当前节点上的副本数（按 hostname == node_name 关联） */
    private Integer replicaCount;
}
