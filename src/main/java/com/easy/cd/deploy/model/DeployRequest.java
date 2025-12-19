package com.easy.cd.deploy.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部署请求模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployRequest {
    
    /**
     * 服务ID
     */
    private Long serviceId;
    
    /**
     * 服务名称
     */
    private String serviceName;
    
    /**
     * 版本号
     */
    private String version;
    
    /**
     * Maven坐标 (groupId:artifactId:version)
     */
    private String mavenCoordinate;
    
    /**
     * Docker镜像名称
     */
    private String dockerImage;
    
    /**
     * 副本数量
     */
    private Integer replicas;
    
    /**
     * Java环境变量(JSON字符串)
     * 示例: {"SPRING_PROFILES_ACTIVE":"prod","JAVA_TOOL_OPTIONS":"-Xmx2g"}
     */
    private String envVars;
    
    /**
     * Docker运行参数(JSON字符串)
     * 示例: {"replicas":3,"cpuLimit":"1","memoryLimit":"2G","restart":"always"}
     */
    private String dockerParams;
    
    /**
     * 环境ID
     */
    private Long environmentId;
}
