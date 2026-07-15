package com.easy.cd.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 监控采集侧统一配置。
 * 对应 application.yml 中 monitor.* 节点。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties {

    /** 总开关：false 时所有采集/同步 Task 不启动 */
    private boolean enabled = true;

    /** 采集器参数 */
    private Collector collector = new Collector();

    /** 调试相关（仅采指定环境等） */
    private Debug debug = new Debug();

    /** 分层降采样保留策略 */
    private Retention retention = new Retention();

    @Data
    public static class Collector {
        /** 高频状态/指标采集周期（毫秒） */
        private long intervalMs = 10000;
        /** 并发采集线程数 */
        private int parallelism = 16;
        /** 单节点采集超时（毫秒） */
        private int nodeTimeoutMs = 3000;
    }

    @Data
    public static class Debug {
        /** 调试过滤：为空时采集所有环境；配置后只采指定环境（按 environment.name 匹配） */
        private String envFilter;
    }

    /**
     * 分层降采样保留配置。
     * 采集周期默认为 10s，分层路径：
     *   0-tier1Hours  保留原始采样
     *   -> 1-3d       降到 60s（1min）
     *   -> 3-7d       降到 300s（5min）
     *   -> 7-30d      降到 900s（15min）
     *   -> 30-365d    降到 3600s（1h）
     *   -> >365d      删除
     */
    @Data
    public static class Retention {
        /** 总开关：false 时保留任务不启动 */
        private boolean enabled = true;

        /** Tier1：前 N 小时保留 3s 原始；过后降到 tier1Step 秒（1min） */
        private int tier1Hours = 24;
        private int tier1StepSeconds = 60;

        /** Tier2：前 N 小时内为 1min；过后降到 5min */
        private int tier2Hours = 72;
        private int tier2StepSeconds = 300;

        /** Tier3：前 N 小时内为 5min；过后降到 15min */
        private int tier3Hours = 168;
        private int tier3StepSeconds = 900;

        /** Tier4：前 N 小时内为 15min；过后降到 1h */
        private int tier4Hours = 720;
        private int tier4StepSeconds = 3600;

        /** 最大保留时长（小时），超过则物理删除。默认 1 年 */
        private int maxHours = 8760;

        /** 处理窗口小时数：每次任务处理“刚过 tier 边界后 N 小时”的数据 */
        private int windowHours = 1;
    }

    /**
     * 判断某环境是否被调试过滤器允许通过。
     * envFilter 为空即视为放行全部。
     */
    public boolean isEnvAllowed(String envName) {
        String filter = debug == null ? null : debug.getEnvFilter();
        if (filter == null || filter.isEmpty()) return true;
        return filter.equals(envName);
    }
}
