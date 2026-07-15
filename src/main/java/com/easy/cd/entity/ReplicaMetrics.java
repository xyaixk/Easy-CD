package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 副本监控指标表（每 1s 一行时序数据）
 *
 * 极简策略：仅保留 4 个核心数值指标 + 归属键 + 时间戳。
 * 网络/磁盘 IO / 状态变更追踪 / K8s 特有字段已从表中移除。
 */
@Data
@TableName("replica_metrics")
public class ReplicaMetrics {

    @TableId(type = IdType.AUTO)
    private Long id;

    // ==================== 归属键 ====================
    private Long replicaStatusId;
    private Long serviceId;
    private String replicaId;
    private String replicaName;

    /** 部署平台：docker / k8s */
    private String platform;
    /** 所在节点名称 */
    private String nodeName;
    /** 当前状态（running / exited / ...） */
    private String status;

    // ==================== 核心指标 ====================
    /** CPU 使用率（0-100） */
    private Double cpuPercent;
    /** 内存使用量（字节） */
    private Long memoryUsage;
    /** 内存限制（字节） */
    private Long memoryLimit;
    /** 内存使用率（0-100） */
    private Double memoryPercent;

    // ==================== 时间戳 ====================
    private LocalDateTime collectedTime;
}
