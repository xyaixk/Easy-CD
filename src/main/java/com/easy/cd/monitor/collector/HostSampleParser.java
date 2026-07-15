package com.easy.cd.monitor.collector;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 解析单节点 SSH 一次性采集脚本的输出。
 *
 * 采集脚本约定按 "===SECTION===" 分段：
 *   ===STAT===   /proc/stat 第一行（cpu ticks）
 *   ===MEM===    /proc/meminfo 关键行
 *   ===LOAD===   /proc/loadavg
 *   ===DISK===   df -B1 -x tmpfs -x devtmpfs / | tail -1
 *
 * CPU 使用率不在此处计算（需要两次采样差值），由采集器持有内存快照计算。
 */
@Slf4j
public class HostSampleParser {

    /** 解析后的原始数据（未算 cpu%，等外层用上次快照做差值） */
    @Data
    public static class HostSampleRaw {
        /** cpu ticks 累计值（idle） */
        private Long cpuIdle;
        /** cpu ticks 累计值（total = user+nice+system+idle+iowait+irq+softirq+steal） */
        private Long cpuTotal;

        private BigDecimal load1;
        private BigDecimal load5;
        private BigDecimal load15;

        private Long memTotal;
        private Long memAvailable;

        private Long diskUsed;
        private Long diskTotal;

        public boolean hasCpu() {
            return cpuIdle != null && cpuTotal != null;
        }
    }

    public HostSampleRaw parse(String stdout) {
        HostSampleRaw raw = new HostSampleRaw();
        if (stdout == null || stdout.isEmpty()) return raw;

        // 按段切分
        Map<String, String> sections = splitSections(stdout);

        parseCpuStat(sections.get("STAT"), raw);
        parseMem(sections.get("MEM"), raw);
        parseLoad(sections.get("LOAD"), raw);
        parseDisk(sections.get("DISK"), raw);
        return raw;
    }

    private Map<String, String> splitSections(String stdout) {
        Map<String, String> map = new HashMap<>();
        String[] lines = stdout.split("\\r?\\n");
        String currentKey = null;
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("===") && line.endsWith("===") && line.length() > 6) {
                if (currentKey != null) {
                    map.put(currentKey, buf.toString());
                }
                currentKey = line.substring(3, line.length() - 3);
                buf.setLength(0);
            } else if (currentKey != null) {
                if (buf.length() > 0) buf.append('\n');
                buf.append(line);
            }
        }
        if (currentKey != null) {
            map.put(currentKey, buf.toString());
        }
        return map;
    }

    /**
     * /proc/stat 第一行示例：
     *   cpu  12345 100 6789 999999 500 0 200 0 0 0
     * 字段：user nice system idle iowait irq softirq steal guest guest_nice
     */
    private void parseCpuStat(String content, HostSampleRaw raw) {
        if (content == null || content.isEmpty()) return;
        String line = content.trim();
        try {
            String[] parts = line.split("\\s+");
            if (parts.length < 5 || !parts[0].startsWith("cpu")) return;
            long user = parseLong(parts[1]);
            long nice = parseLong(parts[2]);
            long system = parseLong(parts[3]);
            long idle = parseLong(parts[4]);
            long iowait = parts.length > 5 ? parseLong(parts[5]) : 0;
            long irq = parts.length > 6 ? parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? parseLong(parts[7]) : 0;
            long steal = parts.length > 8 ? parseLong(parts[8]) : 0;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;
            raw.setCpuIdle(idle + iowait);   // idle-like 部分
            raw.setCpuTotal(total);
        } catch (Exception e) {
            log.debug("解析 /proc/stat 失败: {}", line, e);
        }
    }

    /**
     * /proc/meminfo 关键行：
     *   MemTotal:       16265744 kB
     *   MemAvailable:   12345678 kB
     */
    private void parseMem(String content, HostSampleRaw raw) {
        if (content == null || content.isEmpty()) return;
        for (String line : content.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("MemTotal:")) {
                raw.setMemTotal(parseMemKb(line));
            } else if (line.startsWith("MemAvailable:")) {
                raw.setMemAvailable(parseMemKb(line));
            }
        }
    }

    /** "MemTotal:  16265744 kB" -> 16265744 * 1024 */
    private Long parseMemKb(String line) {
        try {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                return Long.parseLong(parts[1]) * 1024L;
            }
        } catch (Exception e) {
            log.debug("解析 mem 行失败: {}", line);
        }
        return null;
    }

    /** /proc/loadavg 示例: "0.32 0.28 0.19 2/301 12345" */
    private void parseLoad(String content, HostSampleRaw raw) {
        if (content == null || content.isEmpty()) return;
        try {
            String[] parts = content.trim().split("\\s+");
            if (parts.length >= 3) {
                raw.setLoad1(new BigDecimal(parts[0]));
                raw.setLoad5(new BigDecimal(parts[1]));
                raw.setLoad15(new BigDecimal(parts[2]));
            }
        } catch (Exception e) {
            log.debug("解析 loadavg 失败: {}", content, e);
        }
    }

    /**
     * df -B1 -x tmpfs -x devtmpfs / | tail -1 示例：
     *   /dev/sda1  105089286144  50000000000  55089286144  48% /
     * 列：Filesystem 1B-blocks Used Available Use% Mounted-on
     */
    private void parseDisk(String content, HostSampleRaw raw) {
        if (content == null || content.isEmpty()) return;
        try {
            String[] parts = content.trim().split("\\s+");
            if (parts.length >= 4) {
                raw.setDiskTotal(parseLong(parts[1]));
                raw.setDiskUsed(parseLong(parts[2]));
            }
        } catch (Exception e) {
            log.debug("解析 df 失败: {}", content, e);
        }
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 用两次快照算 cpu 使用率。
     * @return 百分比（0-100）；若差值无效返回 null
     */
    public static BigDecimal calcCpuPercent(long prevIdle, long prevTotal, long curIdle, long curTotal) {
        long idleDelta = curIdle - prevIdle;
        long totalDelta = curTotal - prevTotal;
        if (totalDelta <= 0) return null;
        double busy = (double) (totalDelta - idleDelta) / (double) totalDelta * 100.0;
        if (busy < 0) busy = 0;
        if (busy > 100) busy = 100;
        return BigDecimal.valueOf(busy).setScale(2, RoundingMode.HALF_UP);
    }
}
