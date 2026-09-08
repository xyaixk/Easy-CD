package com.easy.cd.terminal;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.easy.cd.entity.Environment;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.util.SshExecutor.SshHost;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.easy.cd.util.SshExecutor.parseSshHostsFromConfig;

/**
 * Resolves the SSH endpoint that owns a Swarm task's container.
 */
@Component
public class TerminalTargetHostResolver {

    private final NodeDiscoveryService nodeDiscoveryService;

    public TerminalTargetHostResolver(NodeDiscoveryService nodeDiscoveryService) {
        this.nodeDiscoveryService = nodeDiscoveryService;
    }

    public SshHost resolve(Environment environment, String nodeName, String nodeIp) {
        List<SshHost> managers = readManagers(environment);
        if (managers.isEmpty()) return null;

        String address = trimToNull(nodeIp);
        if (address != null) {
            return targetForAddress(managers, address);
        }

        String name = trimToNull(nodeName);
        if (name == null) {
            return managers.get(0);
        }

        SshHost configuredHost = findConfiguredHost(managers, name);
        if (configuredHost != null) {
            return configuredHost;
        }

        NodeInfo node = findNode(nodeDiscoveryService.discover(environment), name);
        if (node == null) {
            node = findNode(nodeDiscoveryService.refresh(environment), name);
        }
        if (node == null || trimToNull(node.getIp()) == null) {
            return null;
        }
        return targetForAddress(managers, node.getIp().trim());
    }

    private List<SshHost> readManagers(Environment environment) {
        if (environment == null || trimToNull(environment.getConfig()) == null) {
            return Collections.emptyList();
        }
        try {
            Map<String, Object> config = JSON.parseObject(
                    environment.getConfig(), new TypeReference<Map<String, Object>>() {});
            List<SshHost> parsed = parseSshHostsFromConfig(config);
            List<SshHost> valid = new ArrayList<>();
            for (SshHost host : parsed) {
                if (host != null && trimToNull(host.getHost()) != null) {
                    valid.add(host);
                }
            }
            return valid;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private SshHost targetForAddress(List<SshHost> managers, String address) {
        SshHost configuredHost = findConfiguredHost(managers, address);
        if (configuredHost != null) {
            return configuredHost;
        }

        SshHost credentials = managers.get(0);
        return new SshHost(address, credentials.getPort(), credentials.getUsername(),
                credentials.getPassword(), credentials.getPrivateKey());
    }

    private SshHost findConfiguredHost(List<SshHost> managers, String address) {
        for (SshHost manager : managers) {
            if (address.equalsIgnoreCase(manager.getHost())) {
                return manager;
            }
        }
        return null;
    }

    private NodeInfo findNode(List<NodeInfo> nodes, String name) {
        if (nodes == null) return null;
        for (NodeInfo node : nodes) {
            if (node == null) continue;
            if (name.equalsIgnoreCase(trimToEmpty(node.getHostname()))
                    || name.equalsIgnoreCase(trimToEmpty(node.getNodeId()))) {
                return node;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
