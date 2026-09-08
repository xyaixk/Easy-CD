package com.easy.cd.service.impl;

import com.easy.cd.deploy.DeployService;
import com.easy.cd.deploy.queue.DeployTaskQueueService;
import com.easy.cd.dto.ReplicaDetailDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaMetricsMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceStatusMapper;
import com.easy.cd.service.ReplicaStatusSyncService;
import com.easy.cd.service.ServiceGroupService;
import com.easy.cd.service.ServiceStatusSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceManagementServiceReplicaQueryTest {

    @Test
    void refreshesActualReplicaSnapshotBeforeReadingTheList() {
        ServiceMapper serviceMapper = mock(ServiceMapper.class);
        ReplicaStatusMapper replicaStatusMapper = mock(ReplicaStatusMapper.class);
        ReplicaStatusSyncService replicaStatusSyncService = mock(ReplicaStatusSyncService.class);
        ServiceManagementServiceImpl service = new ServiceManagementServiceImpl(
                serviceMapper,
                mock(ServiceStatusMapper.class),
                replicaStatusMapper,
                mock(ReplicaMetricsMapper.class),
                mock(EnvironmentMapper.class),
                mock(DeployService.class),
                mock(DeployTaskQueueService.class),
                mock(ServiceGroupService.class),
                mock(ServiceStatusSyncService.class),
                replicaStatusSyncService);

        AppService appService = new AppService();
        appService.setId(9L);
        ReplicaStatus currentReplica = new ReplicaStatus();
        currentReplica.setReplicaId("new-task");
        currentReplica.setReplicaName("api.1");
        currentReplica.setStatus("running");
        when(serviceMapper.selectById(9L)).thenReturn(appService);
        when(replicaStatusMapper.selectList(any())).thenReturn(Collections.singletonList(currentReplica));

        List<?> replicas = service.getReplicas(9L);

        InOrder inOrder = inOrder(replicaStatusSyncService, replicaStatusMapper);
        inOrder.verify(replicaStatusSyncService).refreshService(9L);
        inOrder.verify(replicaStatusMapper).selectList(any());
        assertEquals("new-task", ((ReplicaDetailDTO) replicas.get(0)).getId());
    }
}
