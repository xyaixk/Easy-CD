package com.easy.cd.deploy.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部署结果模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployResult {
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 消息
     */
    private String message;
    
    /**
     * 服务状态
     */
    private String status;
    
    /**
     * 运行实例数
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
     * 当前版本
     */
    private String version;
    
    /**
     * 外部服务ID（Docker Service ID 或 K8s Deployment UID）
     */
    private String externalServiceId;
    
    /**
     * 外部服务名称（Docker 服务名或 K8s Deployment Name）
     */
    private String externalServiceName;
    
    /**
     * 部署时间
     */
    private String deployTime;
    
    /**
     * 额外数据
     */
    private Object data;
    
    /**
     * 创建成功结果
     */
    public static DeployResult success(String message) {
        return DeployResult.builder()
                .success(true)
                .message(message)
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static DeployResult failure(String message) {
        return DeployResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}
