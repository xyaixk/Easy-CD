package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_status")
public class ServiceStatus {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long serviceId;
    
    private String status;
    
    private Integer healthyInstances;
    
    private Integer instances;
    
    private Integer desiredInstances;
    
    private LocalDateTime lastDeployTime;
    
    private String lastDeployBy;
    
    private LocalDateTime createdTime;
    
    private LocalDateTime updatedTime;
}
