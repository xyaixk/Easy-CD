package com.easy.cd.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 副本详情DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicaDetailDTO {
    
    // 基本信息
    private String id;
    private String name;
    private Integer index;
    
    // 状态信息
    private String status;
    
    // 节点信息
    private String node;
    private String nodeIp;
    
    // 容器信息
    private String containerId;
    
    // 运行时信息
    private String uptime;
    private Integer restartCount;
    
    // 错误信息
    private String errorMessage;
}
