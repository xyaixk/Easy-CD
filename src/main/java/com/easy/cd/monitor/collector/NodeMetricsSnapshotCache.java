package com.easy.cd.monitor.collector;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点采集快照缓存。
 *
 * 宿主机采集命令会顺带执行 docker stats；副本采集任务复用同一轮输出，
 * 避免每个节点在一个采集周期内重复打开 SSH exec channel。
 */
@Component
public class NodeMetricsSnapshotCache {

    private final Map<String, Snapshot> dockerStats = new ConcurrentHashMap<>();

    public void putDockerStats(Long environmentId, String hostKey, String output) {
        if (environmentId == null || hostKey == null || output == null || output.trim().isEmpty()) return;
        dockerStats.put(key(environmentId, hostKey), new Snapshot(System.currentTimeMillis(), output));
    }

    public String getDockerStats(Long environmentId, String hostKey, long maxAgeMs) {
        Snapshot snapshot = dockerStats.get(key(environmentId, hostKey));
        if (snapshot == null) return null;
        if (System.currentTimeMillis() - snapshot.createdAt > maxAgeMs) {
            dockerStats.remove(key(environmentId, hostKey), snapshot);
            return null;
        }
        return snapshot.output;
    }

    private String key(Long environmentId, String hostKey) {
        return environmentId + ":" + hostKey;
    }

    private static class Snapshot {
        private final long createdAt;
        private final String output;

        private Snapshot(long createdAt, String output) {
            this.createdAt = createdAt;
            this.output = output;
        }
    }
}
