package com.easy.cd.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 告警规则表
 */
@Data
@TableName("alert_rule")
public class AlertRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Boolean enabled;

    /** host / service */
    private String targetType;

    /** 指标 key，如 cpu_percent / mem_percent / disk_percent / load1 */
    private String metric;

    /** > < >= <= */
    private String comparator;

    private BigDecimal threshold;

    /** 持续多少秒满足条件才触发 */
    private Integer durationSeconds;

    /** info / warning / critical */
    private String severity;

    private String description;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
