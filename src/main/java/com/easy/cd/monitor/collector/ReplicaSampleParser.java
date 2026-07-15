package com.easy.cd.monitor.collector;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 解析单个节点 `docker stats --no-stream --format '{{json .}}'` 输出。
 *
 * 每行一个 JSON，字段：
 *  - ID:       容器短 ID（12 位）
 *  - Name:     容器名（Swarm 通常 svc.slot.taskId）
 *  - CPUPerc:  "0.15%" 或 "--"
 *  - MemPerc:  "1.23%" 或 "--"
 *  - MemUsage: "12.3MiB / 1GiB" 或 "--"
 *
 * 只输出 4 个核心指标：cpu_percent / memory_usage / memory_limit / memory_percent。
 */
public class ReplicaSampleParser {

    /**
     * 解析一行 docker stats JSON。
     * 解析失败或字段全空时返回 null。
     */
    public ReplicaSampleRaw parseLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) return null;
        String s = jsonLine.trim();
        if (s.isEmpty() || !s.startsWith("{")) return null;

        JSONObject o;
        try {
            o = JSON.parseObject(s);
        } catch (Exception e) {
            return null;
        }
        if (o == null) return null;

        ReplicaSampleRaw raw = new ReplicaSampleRaw();
        raw.setContainerIdShort(trimTo12(o.getString("ID")));
        raw.setName(o.getString("Name"));
        raw.setCpuPercent(parsePercent(o.getString("CPUPerc")));
        raw.setMemPercent(parsePercent(o.getString("MemPerc")));

        // MemUsage: "12.3MiB / 1GiB"
        String memUsage = o.getString("MemUsage");
        if (memUsage != null && memUsage.contains("/")) {
            String[] parts = memUsage.split("/");
            if (parts.length == 2) {
                raw.setMemoryUsage(parseSize(parts[0]));
                raw.setMemoryLimit(parseSize(parts[1]));
            }
        }
        return raw;
    }

    /** "0.15%" -> 0.15；"--" 或 null 返回 null */
    private BigDecimal parsePercent(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "--".equals(t)) return null;
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1);
        try {
            return new BigDecimal(t.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 单位换算："12.3MiB" -> 字节数
     * 支持：B / K / M / G / T / P（K=千进制 1000）
     *      KiB / MiB / GiB / TiB / PiB（二进制 1024）
     *      kB / MB / GB / TB / PB（Docker 也可能这样输出）
     */
    private Long parseSize(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "--".equals(t)) return null;

        // 拆数字与单位
        int i = 0;
        while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.' || t.charAt(i) == '-')) {
            i++;
        }
        if (i == 0) return null;
        double num;
        try {
            num = Double.parseDouble(t.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = t.substring(i).trim().toUpperCase();
        double mul = 1.0;
        switch (unit) {
            case "": case "B": mul = 1.0; break;
            case "KIB": mul = 1024.0; break;
            case "MIB": mul = 1024.0 * 1024; break;
            case "GIB": mul = 1024.0 * 1024 * 1024; break;
            case "TIB": mul = 1024.0 * 1024 * 1024 * 1024; break;
            case "PIB": mul = 1024.0 * 1024 * 1024 * 1024 * 1024; break;
            case "K": case "KB": mul = 1000.0; break;
            case "M": case "MB": mul = 1000.0 * 1000; break;
            case "G": case "GB": mul = 1000.0 * 1000 * 1000; break;
            case "T": case "TB": mul = 1000.0 * 1000 * 1000 * 1000; break;
            case "P": case "PB": mul = 1000.0 * 1000 * 1000 * 1000 * 1000; break;
            default: return null;
        }
        double bytes = num * mul;
        if (bytes < 0) return null;
        return (long) bytes;
    }

    /** ID 保留前 12 位（docker stats 默认已经是 12 位，防守取一下） */
    private String trimTo12(String id) {
        if (id == null) return null;
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    @Data
    public static class ReplicaSampleRaw {
        private String containerIdShort;
        private String name;
        private BigDecimal cpuPercent;
        private BigDecimal memPercent;
        private Long memoryUsage;
        private Long memoryLimit;
    }
}
