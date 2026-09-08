package com.easy.cd.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Docker Swarm 中仍可追溯的服务日志实例。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceLogInstanceDTO {

    private String taskId;
    private String name;
    private Integer slot;
    private String node;
    private String state;
    private String desiredState;
    private String statusTimestamp;
    private String errorMessage;
    private boolean running;
}
