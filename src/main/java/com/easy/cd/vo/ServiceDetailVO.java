package com.easy.cd.vo;

import lombok.Data;

/**
 * 服务详情VO
 */
@Data
public class ServiceDetailVO {
    
    /**
     * 服务ID
     */
    private Long id;
    
    /**
     * 环境ID
     */
    private Long environmentId;
    
    /**
     * 服务名称
     */
    private String name;
    
    /**
     * 服务描述
     */
    private String description;
    
    /**
     * 版本号
     */
    private String version;
    
    /**
     * Docker镜像
     */
    private String dockerImage;
    
    /**
     * Docker运行参数(JSON字符串)
     */
    private String dockerParams;
    
    /**
     * 服务状态（来自service_status表）
     */
    private String status;
    
    /**
     * 当前运行实例数
     */
    private Integer instances;
    
    /**
     * 健康实例数
     */
    private Integer healthyInstances;
    
    /**
     * 期望实例数
     */
    private Integer desiredInstances;
    
    /**
     * 部署时间
     */
    private String deployTime;
    
    /**
     * 创建时间
     */
    private String createdTime;
    
    // ========== 监控指标 ==========
    
    /**
     * CPU使用率（百分比）
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
     * 内存使用率（百分比）
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
