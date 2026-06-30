package com.easy.cd.dto;

import lombok.Data;

/**
 * 服务更新DTO
 * 注意：服务名称不可修改
 */
@Data
public class ServiceUpdateDTO {
    
    /**
     * 服务名称（只读，用于日志）
     */
    private String name;
    
    /**
     * 服务描述（最多20个字符）
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
     * 副本数量
     */
    private Integer replicas;
}
