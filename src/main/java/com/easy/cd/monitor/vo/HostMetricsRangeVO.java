package com.easy.cd.monitor.vo;

import lombok.Data;

import java.util.List;

/**
 * 宿主机时序指标（大图 · 5m/1h/6h/24h/7d）。
 * 应用层已完成降采样，数组等长 = points。
 * 与前端 monitor.js getHostMetrics mock 结构严格对齐。
 */
@Data
public class HostMetricsRangeVO {

    /** 时间范围：5m / 1h / 6h / 24h / 7d */
    private String range;

    /** 每桶秒数（降采样步长） */
    private Integer stepSec;

    /** 每个桶的起点时间戳（秒）。长度 = points */
    private List<Long> timestamps;

    /** CPU 使用率 0-100，空桶为 null */
    private List<Double> cpu;

    /** 内存使用率 0-100，空桶为 null */
    private List<Double> mem;

    /** 磁盘使用率 0-100，空桶为 null */
    private List<Double> disk;

    /** 1 分钟负载，空桶为 null */
    private List<Double> load1;
}
