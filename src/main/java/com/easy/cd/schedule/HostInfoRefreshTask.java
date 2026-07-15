package com.easy.cd.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.monitor.entity.HostInfo;
import com.easy.cd.monitor.mapper.HostInfoMapper;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 宿主机静态信息刷新任务
 *
 * 每 5 分钟：
 * 1. 通过 NodeDiscoveryService 从 manager 发现所有节点（含 hostname/ip/role/status/cpu/mem）
 * 2. 对每个节点 SSH 采一次 df 拿根分区总量
 * 3. UPSERT 到 host_info
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class HostInfoRefreshTask {

    private final EnvironmentMapper environmentMapper;
    private final HostInfoMapper hostInfoMapper;
    private final NodeDiscoveryService nodeDiscoveryService;
    private final SshExecutor sshExecutor;

    /** 单节点 SSH 拉 df 的硬超时（毫秒） */
    private static final int DF_TIMEOUT_MS = 5_000;

    /** 5 分钟刷新一次；启动后延迟 10s 首次执行，避免和其他任务扎堆 */
    @Scheduled(fixedRate = 5 * 60 * 1000L, initialDelay = 10_000L)
    public void refresh() {
        try {
            List<Environment> environments = environmentMapper.selectList(null);
            for (Environment env : environments) {
                if (!"docker".equals(env.getDeployType())) continue;
                try {
                    refreshEnvironment(env);
                } catch (Exception e) {
                    log.error("刷新环境[{}]宿主机静态信息失败", env.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("HostInfoRefreshTask 执行失败", e);
        }
    }

    private void refreshEnvironment(Environment env) {
        List<NodeInfo> nodes = nodeDiscoveryService.refresh(env);
        if (nodes.isEmpty()) {
            log.debug("环境[{}]未发现 Swarm 节点", env.getName());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int upserted = 0;

        for (NodeInfo node : nodes) {
            try {
                Long diskTotal = fetchDiskTotal(node);

                LambdaQueryWrapper<HostInfo> qw = new LambdaQueryWrapper<>();
                qw.eq(HostInfo::getEnvironmentId, env.getId())
                        .eq(HostInfo::getHostKey, node.getHostKey());
                HostInfo existing = hostInfoMapper.selectOne(qw);

                if (existing == null) {
                    HostInfo h = new HostInfo();
                    h.setEnvironmentId(env.getId());
                    h.setHostKey(node.getHostKey());
                    h.setIp(node.getIp());
                    h.setHostname(node.getHostname());
                    h.setSwarmNodeId(node.getNodeId());
                    h.setSwarmRole(node.getRole());
                    h.setSwarmStatus(node.getStatus());
                    h.setCpuCores(node.getCpuCores());
                    h.setMemTotal(node.getMemTotal());
                    h.setDiskTotal(diskTotal);
                    // discover 到即视为活着；lastSeenTime 不受 df 采集成败与否影响
                    h.setLastSeenTime(now);
                    h.setCreatedTime(now);
                    h.setUpdatedTime(now);
                    hostInfoMapper.insert(h);
                    upserted++;
                    log.info("发现新宿主机: env={}, host={}, role={}, ip={}",
                            env.getName(), node.getHostname(), node.getRole(), node.getIp());
                } else {
                    existing.setIp(node.getIp());
                    existing.setHostname(node.getHostname());
                    existing.setSwarmNodeId(node.getNodeId());
                    existing.setSwarmRole(node.getRole());
                    existing.setSwarmStatus(node.getStatus());
                    if (node.getCpuCores() != null) existing.setCpuCores(node.getCpuCores());
                    if (node.getMemTotal() != null) existing.setMemTotal(node.getMemTotal());
                    // diskTotal 采集失败时保留旧值
                    if (diskTotal != null) {
                        existing.setDiskTotal(diskTotal);
                    }
                    // discover 到即视为活着
                    existing.setLastSeenTime(now);
                    existing.setUpdatedTime(now);
                    hostInfoMapper.updateById(existing);
                    upserted++;
                }
            } catch (Exception e) {
                log.warn("刷新宿主机[{}]失败", node.getHostKey(), e);
            }
        }

        if (upserted > 0) {
            log.info("环境[{}]宿主机信息刷新完成: 共 {} 个节点", env.getName(), upserted);
        }
    }

    /**
     * 通过 SSH 到目标节点执行 df 拿根分区总量。失败返回 null。
     */
    private Long fetchDiskTotal(NodeInfo node) {
        try {
            SshResult r = sshExecutor.executeCommand(node.getSshHost(),
                    "df -B1 -x tmpfs -x devtmpfs / | tail -1", DF_TIMEOUT_MS);
            if (!r.isSuccess() || !r.hasOutput()) return null;
            // Filesystem  1B-blocks  Used  Available  Use%  Mounted
            String[] parts = r.getStdout().trim().split("\\s+");
            if (parts.length >= 2) {
                return Long.parseLong(parts[1]);
            }
        } catch (Exception e) {
            log.debug("采 df 失败: host={}", node.getHostKey(), e);
        }
        return null;
    }
}
