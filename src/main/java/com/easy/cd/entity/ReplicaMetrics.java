package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 副本监控指标表（包含状态变更历史）
 */
@Data
@TableName("replica_metrics")
public class ReplicaMetrics {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long replicaStatusId;
    private Long serviceId;
    private String replicaId;
    private String replicaName;
    
    // 平台和节点信息
    private String platform;
    private String nodeName;
    private String namespace;
    
    // 状态信息（用于追踪状态变更）
    private String status;
    private String phase;
    private String previousStatus;
    private String previousPhase;
    private Boolean isStatusChanged;
    
    // CPU指标
    private Double cpuPercent;
    
    // 内存指标
    private Long memoryUsage;
    private Long memoryLimit;
    private Double memoryPercent;
    
    // 网络指标
    private Long networkRxBytes;
    private Long networkTxBytes;
    private Long networkRxRate;
    private Long networkTxRate;
    
    // 磁盘I/O指标
    private Long diskReadBytes;
    private Long diskWriteBytes;
    private Long diskReadRate;
    private Long diskWriteRate;
    
    // 运行时信息
    private Long uptimeSeconds;
    private Integer restartCount;
    
    // 错误和事件信息
    private String errorMessage;
    private Integer exitCode;
    private String terminationReason;
    private String eventType;
    private String eventReason;
    private String eventMessage;
    
    // 时间戳
    private LocalDateTime collectedTime;
}
