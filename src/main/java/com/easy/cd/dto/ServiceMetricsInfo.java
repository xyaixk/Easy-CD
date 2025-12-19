package com.easy.cd.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 服务监控指标信息
 */
@Data
@Builder
public class ServiceMetricsInfo {
    
    /**
     * 服务ID
     */
    private Long serviceId;
    
    /**
     * CPU使用率（百分比，0-100）
     */
    private Double cpuPercent;
    
    /**
     * 内存使用量（字节）
     */
    private Long memoryUsage;
    
    /**
     * 内存限制（字节）
     */
    private Long memoryLimit;
    
    /**
     * 内存使用率（百分比，0-100）
     */
    private Double memoryPercent;
    
    /**
     * 网络接收速率（字节/秒）
     */
    private Long networkRxRate;
    
    /**
     * 网络发送速率（字节/秒）
     */
    private Long networkTxRate;
    
    /**
     * 磁盘读取速率（字节/秒）
     */
    private Long diskReadRate;
    
    /**
     * 磁盘写入速率（字节/秒）
     */
    private Long diskWriteRate;
}
