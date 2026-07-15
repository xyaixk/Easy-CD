package com.easy.cd.monitor.vo;

import lombok.Data;

/**
 * 宿主机上的容器（详情弹窗表格行）。
 * 与前端 monitor.js getHostReplicas mock 结构严格对齐。
 */
@Data
public class ReplicaOnHostVO {

    /** 副本唯一标识（Docker: Task ID / K8s: Pod UID） */
    private String replicaId;

    /** 副本显示名（如 service.1） */
    private String replicaName;

    /** 所属服务名 */
    private String serviceName;

    /** 当前状态：running / starting / ... */
    private String status;

    /** CPU 使用率 0-100（可空） */
    private Double cpuPercent;

    /** 内存使用率 0-100（可空） */
    private Double memPercent;
}
