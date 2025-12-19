package com.easy.cd.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务列表返回DTO
 * 聚合 service + service_status + service_metrics 表数据
 */
@Data
public class ServiceListDTO {
    
    // 来自 service 表
    private Long id;
    private String name;
    private String description;
    
    // 来自 service_status 表
    private String status;
    private String version;
    private Integer healthyInstances;
    private Integer instances;
    private Integer desiredInstances;
    private String lastDeploy;  // 格式化后的 lastDeployTime
    private String branch;  // 从 version 或其他地方获取，前端需要
    
    // 来自 service_metrics 表 (最新一条)
    private String cpu;
    private String memory;
}
