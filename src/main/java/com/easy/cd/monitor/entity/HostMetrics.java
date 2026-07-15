package com.easy.cd.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 宿主机时序指标表（每 1s 一行）
 */
@Data
@TableName("host_metrics")
public class HostMetrics {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hostId;

    private Long environmentId;

    /** CPU 使用率 0-100 */
    private BigDecimal cpuPercent;

    private BigDecimal load1;
    private BigDecimal load5;
    private BigDecimal load15;

    /** 字节 */
    private Long memUsed;
    private Long memTotal;
    private BigDecimal memPercent;

    /** 根分区字节 */
    private Long diskUsed;
    private Long diskTotal;
    private BigDecimal diskPercent;

    private LocalDateTime collectedTime;
}
