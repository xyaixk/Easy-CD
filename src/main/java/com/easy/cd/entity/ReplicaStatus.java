package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 副本状态表（兼容Docker和K8s）
 */
@Data
@TableName("replica_status")
public class ReplicaStatus {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long serviceId;
    
    // 通用标识字段
    private String replicaId;
    private String replicaName;
    private Integer replicaIndex;
    
    // 部署平台相关
    private String platform;
    private String namespace;
    
    // 节点信息
    private String nodeName;
    private String nodeIp;
    
    // 状态信息
    private String status;
    private String phase;
    
    // 容器信息
    private String containerId;
    private String containerIdShort;
    private String image;
    private String imageId;
    
    // Docker Swarm 特有字段
    private String taskId;
    private Integer taskSlot;
    private String serviceName;
    
    // Kubernetes 特有字段
    private String podName;
    private String podUid;
    private String deploymentName;
    private String replicasetName;
    private String labels;
    private String annotations;
    
    // IP信息
    private String podIp;
    private String hostIp;
    
    // 运行时信息
    private Long uptimeSeconds;
    private Integer restartCount;
    private LocalDateTime startTime;
    
    // 错误和事件信息
    private String errorMessage;
    private Integer exitCode;
    private String terminationReason;
    private LocalDateTime lastStateChange;
    
    // 资源配置
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
    
    // 时间戳
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
