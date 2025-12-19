package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("app_service")
public class AppService {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    
    private String description;
    
    private String version;
    
    private Long environmentId;
    
    /**
     * Docker镜像名称
     */
    private String dockerImage;
    
    /**
     * Docker运行参数(JSON格式)
     * 包含Docker部署参数:
     * 示例: {"replicas":3,"cpuLimit":"1","memoryLimit":"2G","restart":"always"}
     */
    private String dockerParams;
    
    /**
     * 外部服务ID（部署平台返回的服务标识）
     * Docker Swarm: Service ID
     * Kubernetes: Deployment UID 或 Deployment Name
     */
    private String externalServiceId;
    
    /**
     * 外部服务名称（部署时使用的服务名称）
     * Docker Swarm: 环境-服务名 (如: dev-user-service)
     * Kubernetes: Deployment Name
     */
    private String externalServiceName;
    
    private LocalDateTime createdTime;
    
    private LocalDateTime updatedTime;
}
