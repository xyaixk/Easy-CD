package com.easy.cd.monitor.vo;

import lombok.Data;

import java.util.List;

/**
 * 服务/副本指标概要（sparkline + 当前值）。
 *
 * 服务级：所有副本在同一时间桶的平均
 * 副本级：该副本自身的时间桶平均
 *
 * 与前端 monitor.js getServiceMetricSummary / getReplicaMetricSummary mock 结构严格对齐。
 * 默认 12 点 × 25s = 5 分钟滚动窗口。
 */
@Data
public class MetricSummaryVO {

    /** 当前 CPU 使用率 0-100（最近一桶的值，可空） */
    private Double cpuPercent;

    /** 当前内存使用率 0-100（最近一桶的值，可空） */
    private Double memPercent;

    /** CPU sparkline（12 点等长，空桶为 null）——服务级为副本间均值 */
    private List<Double> cpuSpark;

    /** 内存 sparkline（12 点等长，空桶为 null）——服务级为副本间均值 */
    private List<Double> memSpark;

    /** 当前 CPU 使用率 MAX（桶内副本间最高值，可空） */
    private Double cpuPercentMax;

    /** 当前内存使用率 MAX（桶内副本间最高值，可空） */
    private Double memPercentMax;

    /** CPU sparkline MAX（每桶取副本间最大值，12 点） */
    private List<Double> cpuSparkMax;

    /** 内存 sparkline MAX（每桶取副本间最大值，12 点） */
    private List<Double> memSparkMax;

    /** 每个桶的起点时间戳（秒） */
    private List<Long> timestamps;

    /** 当前内存占用（字节，仅副本级 summary 提供） */
    private Long memoryUsage;

    /** 当前内存限额（字节，仅副本级 summary 提供） */
    private Long memoryLimit;
}
