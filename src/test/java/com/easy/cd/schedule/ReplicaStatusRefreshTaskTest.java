package com.easy.cd.schedule;

import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.monitor.discovery.NodeDiscoveryService;
import com.easy.cd.monitor.discovery.NodeDiscoveryService.NodeInfo;
import com.easy.cd.service.ReplicaStatusSyncService;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplicaStatusRefreshTaskTest {

    @Test
    void refreshServiceStoresCurrentReplacementAfterContainerRestart() {
        EnvironmentMapper environmentMapper = mock(EnvironmentMapper.class);
        ServiceMapper serviceMapper = mock(ServiceMapper.class);
        ReplicaStatusMapper replicaStatusMapper = mock(ReplicaStatusMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SshExecutor sshExecutor = mock(SshExecutor.class);
        NodeDiscoveryService nodeDiscoveryService = mock(NodeDiscoveryService.class);

        ReplicaStatusSyncService syncService = new ReplicaStatusSyncService(
                environmentMapper,
                serviceMapper,
                replicaStatusMapper,
                transactionTemplate,
                sshExecutor,
                nodeDiscoveryService);

        Environment environment = environment();
        AppService service = service();
        when(serviceMapper.selectById(9L)).thenReturn(service);
        when(environmentMapper.selectById(1L)).thenReturn(environment);
        when(sshExecutor.executeCommandWithFailover(anyList(), startsWith("docker service ps")))
                .thenReturn(new SshResult(0,
                        "{\"ID\":\"oldtask123456\",\"Name\":\"api.1\","
                                + "\"Node\":\"swarm-worker-64\",\"DesiredState\":\"Shutdown\","
                                + "\"CurrentState\":\"Shutdown 2 seconds ago\",\"Error\":\"\"}\n"
                                + "{\"ID\":\"newtask123456\",\"Name\":\"api.1\","
                                + "\"Node\":\"swarm-worker-64\",\"DesiredState\":\"Running\","
                                + "\"CurrentState\":\"Running 8 minutes ago\",\"Error\":\"\"}\n",
                        ""));
        when(sshExecutor.executeCommandWithFailover(anyList(), startsWith("docker inspect")))
                .thenReturn(new SshResult(0, "newtask123456 4a4ef7e01f3b1234567890\n", ""));
        when(nodeDiscoveryService.discover(environment)).thenReturn(
                Collections.singletonList(node("swarm-worker-64", "10.10.0.64")));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        assertTrue(syncService.refreshService(9L));

        ArgumentCaptor<ReplicaStatus> statusCaptor = ArgumentCaptor.forClass(ReplicaStatus.class);
        verify(replicaStatusMapper).insert(statusCaptor.capture());
        ReplicaStatus status = statusCaptor.getValue();
        assertEquals("newtask123456", status.getReplicaId());
        assertEquals("swarm-worker-64", status.getNodeName());
        assertEquals("10.10.0.64", status.getNodeIp());
        assertEquals("4a4ef7e01f3b", status.getContainerId());
        assertEquals(1, status.getRestartCount());
    }

    @Test
    void scheduledRefreshDelegatesToSyncService() {
        ReplicaStatusSyncService syncService = mock(ReplicaStatusSyncService.class);

        new ReplicaStatusRefreshTask(syncService).refreshReplicaStatus();

        verify(syncService).refreshAllReplicaStatus();
    }

    private Environment environment() {
        Environment environment = new Environment();
        environment.setId(1L);
        environment.setName("test");
        environment.setDeployType("docker");
        environment.setConfig("{\"swarmManagerHosts\":[{\"host\":\"10.10.0.63\"}]} ");
        return environment;
    }

    private AppService service() {
        AppService service = new AppService();
        service.setId(9L);
        service.setEnvironmentId(1L);
        service.setName("api");
        service.setExternalServiceName("api");
        return service;
    }

    private NodeInfo node(String hostname, String ip) {
        NodeInfo node = new NodeInfo();
        node.setHostname(hostname);
        node.setIp(ip);
        return node;
    }
}
