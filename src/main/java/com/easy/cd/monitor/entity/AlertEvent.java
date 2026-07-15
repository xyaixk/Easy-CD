package com.easy.cd.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警事件表
 */
@Data
@TableName("alert_event")
public class AlertEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;

    /** host / service */
    private String targetType;

    /** 目标 ID，如 host_id 或 service_id */
    private String targetKey;

    private String metric;

    /** 触发时观测值 */
    private BigDecimal value;

    /** 触发时阈值 */
    private BigDecimal threshold;

    /** firing / resolved */
    private String status;

    private LocalDateTime firedTime;

    private LocalDateTime resolvedTime;

    private String message;
}
