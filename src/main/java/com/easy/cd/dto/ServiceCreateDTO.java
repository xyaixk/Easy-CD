package com.easy.cd.dto;

import lombok.Data;

/**
 * 服务创建DTO
 */
@Data
public class ServiceCreateDTO {
    
    /**
     * 环境ID
     */
    private Long environmentId;
    
    /**
     * 服务名称
     */
    private String name;
    
    /**
     * 服务描述（最多20个字符）
     */
    private String description;
    
    /**
     * Docker镜像
     */
    private String dockerImage;
    
    /**
     * 副本数量
     */
    private Integer replicas;
    
    /**
     * Docker运行参数(JSON字符串)
     * 不包含 replicas 参数
     */
    private String dockerParams;
}
