package com.easy.cd.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 服务状态信息
 * 用于传递服务状态数据
 */
@Data
@Builder
public class ServiceStatusInfo {
    
    /**
     * 服务ID（数据库中的服务ID）
     */
    private Long serviceId;
    
    /**
     * 服务名称
     */
    private String serviceName;
    
    /**
     * 服务状态: running, stopped, deploying, failed
     */
    private String status;
    
    /**
     * 健康的实例数
     */
    private Integer healthyInstances;
    
    /**
     * 当前运行的实例数
     */
    private Integer instances;
    
    /**
     * 期望的实例数
     */
    private Integer desiredInstances;
}
