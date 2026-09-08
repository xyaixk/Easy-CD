package com.easy.cd.terminal;

import com.easy.cd.entity.Environment;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.util.SshExecutor.SshHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalTargetHostResolverTest {

    private NodeDiscoveryService nodeDiscoveryService;
    private TerminalTargetHostResolver resolver;

    @BeforeEach
    void setUp() {
        nodeDiscoveryService = mock(NodeDiscoveryService.class);
        resolver = new TerminalTargetHostResolver(nodeDiscoveryService);
    }

    @Test
    void usesReplicaNodeIpWithoutRunningDiscovery() {
        Environment environment = environmentWithManager("10.10.0.63");

        SshHost target = resolver.resolve(environment, "swarm-worker-64", "10.10.0.64");

        assertEquals("10.10.0.64", target.getHost());
        assertEquals("deploy", target.getUsername());
        assertEquals("secret", target.getPassword());
        verify(nodeDiscoveryService, never()).discover(environment);
    }

    @Test
    void resolvesWorkerIpFromSwarmNodeName() {
        Environment environment = environmentWithManager("10.10.0.63");
        when(nodeDiscoveryService.discover(environment)).thenReturn(
                Collections.singletonList(node("node-id-64", "swarm-worker-64", "10.10.0.64")));

        SshHost target = resolver.resolve(environment, "swarm-worker-64", null);

        assertEquals("10.10.0.64", target.getHost());
        assertEquals("deploy", target.getUsername());
        verify(nodeDiscoveryService, never()).refresh(environment);
    }

    @Test
    void refreshesNodeDiscoveryAfterCachedMiss() {
        Environment environment = environmentWithManager("10.10.0.63");
        when(nodeDiscoveryService.discover(environment)).thenReturn(Collections.emptyList());
        when(nodeDiscoveryService.refresh(environment)).thenReturn(
                Collections.singletonList(node("node-id-65", "swarm-worker-65", "10.10.0.65")));

        SshHost target = resolver.resolve(environment, "node-id-65", null);

        assertEquals("10.10.0.65", target.getHost());
        verify(nodeDiscoveryService).refresh(environment);
    }

    @Test
    void preservesCredentialsConfiguredForManagerReplica() {
        Environment environment = new Environment();
        environment.setConfig("{\"swarmManagerHosts\":["
                + "{\"host\":\"10.10.0.63\",\"port\":22,\"username\":\"first\",\"password\":\"one\"},"
                + "{\"host\":\"10.10.0.66\",\"port\":2202,\"username\":\"second\",\"password\":\"two\"}]}"
        );

        SshHost target = resolver.resolve(environment, "swarm-manager-66", "10.10.0.66");

        assertEquals("10.10.0.66", target.getHost());
        assertEquals(2202, target.getPort());
        assertEquals("second", target.getUsername());
        assertEquals("two", target.getPassword());
    }

    @Test
    void doesNotSilentlyFallbackToManagerForUnknownNamedNode() {
        Environment environment = environmentWithManager("10.10.0.63");
        when(nodeDiscoveryService.discover(environment)).thenReturn(Collections.emptyList());
        when(nodeDiscoveryService.refresh(environment)).thenReturn(Collections.emptyList());

        assertNull(resolver.resolve(environment, "swarm-worker-99", null));
    }

    @Test
    void fallsBackToConfiguredManagerOnlyWhenReplicaHasNoNodeIdentity() {
        Environment environment = environmentWithManager("10.10.0.63");

        SshHost target = resolver.resolve(environment, " ", null);

        assertEquals("10.10.0.63", target.getHost());
    }

    @Test
    void returnsNullWhenEnvironmentHasNoManagerConfiguration() {
        Environment environment = new Environment();
        environment.setConfig("{}");

        assertNull(resolver.resolve(environment, "swarm-worker-64", null));
    }

    private Environment environmentWithManager(String host) {
        Environment environment = new Environment();
        environment.setConfig("{\"swarmManagerHosts\":[{"
                + "\"host\":\"" + host + "\","
                + "\"port\":22,"
                + "\"username\":\"deploy\","
                + "\"password\":\"secret\"}]}"
        );
        return environment;
    }

    private NodeInfo node(String nodeId, String hostname, String ip) {
        NodeInfo node = new NodeInfo();
        node.setNodeId(nodeId);
        node.setHostname(hostname);
        node.setIp(ip);
        return node;
    }
}
