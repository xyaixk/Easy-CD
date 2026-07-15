package com.easy.cd.monitor.discovery;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.easy.cd.entity.Environment;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Swarm 节点自动发现
 *
 * 从 manager 一处 SSH 执行 docker node ls / inspect，
 * 用 manager 凭据 + 节点 IP 拼出全集群 SshHost 列表。
 *
 * 结果缓存 5min，命中缓存直接返回，避免频繁调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeDiscoveryService {

    private final SshExecutor sshExecutor;

    /** 环境 ID -> (缓存时间, 节点列表) */
    private final Map<Long, CacheEntry> cache = new HashMap<>();

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /**
     * 发现指定环境下所有 Swarm 节点。
     * 前置：环境的 config.swarmManagerHosts 里配好 manager 凭据。
     */
    public synchronized List<NodeInfo> discover(Environment environment) {
        Long envId = environment.getId();
        CacheEntry entry = cache.get(envId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.cachedAt < CACHE_TTL_MS) {
            return entry.nodes;
        }

        List<NodeInfo> nodes = discoverInternal(environment);
        cache.put(envId, new CacheEntry(now, nodes));
        return nodes;
    }

    /** 强制刷新，忽略缓存 */
    public synchronized List<NodeInfo> refresh(Environment environment) {
        List<NodeInfo> nodes = discoverInternal(environment);
        cache.put(environment.getId(), new CacheEntry(System.currentTimeMillis(), nodes));
        return nodes;
    }

    private List<NodeInfo> discoverInternal(Environment environment) {
        List<NodeInfo> result = new ArrayList<>();
        try {
            Map<String, Object> config = JSON.parseObject(
                    environment.getConfig(), new TypeReference<Map<String, Object>>() {});
            List<SshHost> managerHosts = SshExecutor.parseSshHostsFromConfig(config);
            if (managerHosts.isEmpty()) {
                return result;
            }

            // manager 一次 SSH 拿到所有节点 inspect
            SshResult inspectRes = sshExecutor.executeCommandWithFailover(managerHosts,
                    "docker node inspect $(docker node ls -q) --format '{{json .}}'");
            if (!inspectRes.isSuccess() || !inspectRes.hasOutput()) {
                log.warn("环境[{}]节点发现失败: exitCode={}, stderr={}",
                        environment.getName(), inspectRes.getExitCode(), inspectRes.getStderr());
                return result;
            }

            SshHost tmpl = managerHosts.get(0);
            for (String line : inspectRes.getStdout().split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("{")) continue;
                try {
                    NodeInfo n = parseNodeJson(line);
                    if (n == null || n.getIp() == null || n.getIp().isEmpty()) continue;

                    // 拼 SshHost：用 manager 凭据 + 节点 IP
                    SshHost nh = new SshHost();
                    nh.setHost(n.getIp());
                    nh.setPort(tmpl.getPort());
                    nh.setUsername(tmpl.getUsername());
                    nh.setPassword(tmpl.getPassword());
                    nh.setPrivateKey(tmpl.getPrivateKey());
                    n.setSshHost(nh);
                    n.setHostKey(nh.getKey());
                    result.add(n);
                } catch (Exception e) {
                    log.debug("解析节点 JSON 失败: {}", line, e);
                }
            }
        } catch (Exception e) {
            log.error("环境[{}]节点发现异常", environment.getName(), e);
        }
        return result;
    }

    private NodeInfo parseNodeJson(String json) {
        JSONObject o = JSON.parseObject(json);
        if (o == null) return null;

        NodeInfo n = new NodeInfo();
        n.setNodeId(o.getString("ID"));

        JSONObject desc = o.getJSONObject("Description");
        if (desc != null) {
            n.setHostname(desc.getString("Hostname"));
            JSONObject res = desc.getJSONObject("Resources");
            if (res != null) {
                Long nano = res.getLong("NanoCPUs");
                if (nano != null && nano > 0) {
                    n.setCpuCores((int) (nano / 1_000_000_000L));
                }
                Long mem = res.getLong("MemoryBytes");
                if (mem != null && mem > 0) {
                    n.setMemTotal(mem);
                }
            }
        }

        JSONObject spec = o.getJSONObject("Spec");
        if (spec != null) {
            n.setRole(spec.getString("Role")); // manager / worker
        }

        JSONObject status = o.getJSONObject("Status");
        if (status != null) {
            n.setStatus(status.getString("State")); // ready / down
            n.setIp(status.getString("Addr"));      // 通告 IP
        }
        return n;
    }

    @Data
    public static class NodeInfo {
        private String nodeId;
        private String hostname;
        private String ip;
        /** manager / worker */
        private String role;
        /** ready / down */
        private String status;
        private Integer cpuCores;
        private Long memTotal;
        /** user@ip:port */
        private String hostKey;
        private SshHost sshHost;
    }

    private static class CacheEntry {
        final long cachedAt;
        final List<NodeInfo> nodes;

        CacheEntry(long cachedAt, List<NodeInfo> nodes) {
            this.cachedAt = cachedAt;
            this.nodes = nodes;
        }
    }
}
