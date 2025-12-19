package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_metrics")
public class ServiceMetrics {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
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
    
    /**
     * 采集时间
     */
    private LocalDateTime collectedTime;
    
    private LocalDateTime createdTime;
}
