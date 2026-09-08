package com.easy.cd.service;

import com.easy.cd.deploy.DeployService;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ServiceStatus;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceStatusSyncServiceTest {

    @Mock
    private ServiceMapper serviceMapper;

    @Mock
    private ServiceStatusMapper serviceStatusMapper;

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private DeployService deployService;

    private ServiceStatusSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new ServiceStatusSyncService(
                serviceMapper, serviceStatusMapper, environmentMapper, deployService);
    }

    @Test
    void refreshServicePersistsActualRuntimeSnapshot() {
        AppService service = service(9L, 7L, 3);
        Environment environment = environment(7L);
        when(serviceMapper.selectById(9L)).thenReturn(service);
        when(environmentMapper.selectById(7L)).thenReturn(environment);
        when(deployService.getStatus("docker", 9L)).thenReturn(DeployResult.builder()
                .success(true)
                .status("deploying")
                .healthyInstances(1)
                .instances(2)
                .desiredInstances(3)
                .build());

        assertTrue(syncService.refreshService(9L));

        ArgumentCaptor<ServiceStatus> captor = ArgumentCaptor.forClass(ServiceStatus.class);
        verify(serviceStatusMapper).insert(captor.capture());
        ServiceStatus inserted = captor.getValue();
        assertEquals("deploying", inserted.getStatus());
        assertEquals(1, inserted.getHealthyInstances());
        assertEquals(2, inserted.getInstances());
        assertEquals(3, inserted.getDesiredInstances());
    }

    @Test
    void refreshServiceUpdatesChangedExistingSnapshot() {
        AppService service = service(9L, 7L, 2);
        ServiceStatus existing = new ServiceStatus();
        existing.setId(4L);
        existing.setServiceId(9L);
        existing.setStatus("deploying");
        existing.setHealthyInstances(1);
        existing.setInstances(1);
        existing.setDesiredInstances(2);
        when(serviceMapper.selectById(9L)).thenReturn(service);
        when(environmentMapper.selectById(7L)).thenReturn(environment(7L));
        when(serviceStatusMapper.selectOne(any())).thenReturn(existing);
        when(deployService.getStatus("docker", 9L)).thenReturn(DeployResult.builder()
                .success(true)
                .status("running")
                .healthyInstances(2)
                .instances(2)
                .desiredInstances(2)
                .build());

        assertTrue(syncService.refreshService(9L));

        verify(serviceStatusMapper).updateById(existing);
        verify(serviceStatusMapper, never()).insert(any());
        assertEquals("running", existing.getStatus());
        assertEquals(2, existing.getHealthyInstances());
        assertEquals(2, existing.getInstances());
    }

    @Test
    void successfulStopWritesStoppedWithoutQueryingRemovedRuntimeService() {
        when(serviceMapper.selectById(9L)).thenReturn(service(9L, 7L, 4));

        syncService.refreshAfterTask("STOP", 9L, true);

        ArgumentCaptor<ServiceStatus> captor = ArgumentCaptor.forClass(ServiceStatus.class);
        verify(serviceStatusMapper).insert(captor.capture());
        ServiceStatus inserted = captor.getValue();
        assertEquals("stopped", inserted.getStatus());
        assertEquals(0, inserted.getHealthyInstances());
        assertEquals(0, inserted.getInstances());
        assertEquals(4, inserted.getDesiredInstances());
        verify(deployService, never()).getStatus(any(), any());
    }

    @Test
    void failedTaskRefreshesActualStateInsteadOfForcingFailed() {
        when(serviceMapper.selectById(9L)).thenReturn(service(9L, 7L, 1));
        when(environmentMapper.selectById(7L)).thenReturn(environment(7L));
        when(deployService.getStatus("docker", 9L)).thenReturn(DeployResult.builder()
                .success(true)
                .status("running")
                .healthyInstances(1)
                .instances(1)
                .desiredInstances(1)
                .build());

        syncService.refreshAfterTask("UPDATE", 9L, false);

        ArgumentCaptor<ServiceStatus> captor = ArgumentCaptor.forClass(ServiceStatus.class);
        verify(serviceStatusMapper).insert(captor.capture());
        assertEquals("running", captor.getValue().getStatus());
    }

    @Test
    void successfulDeleteSkipsStatusWrite() {
        syncService.refreshAfterTask("DELETE", 9L, true);

        verify(serviceMapper, never()).selectById(any());
        verify(serviceStatusMapper, never()).insert(any());
        verify(serviceStatusMapper, never()).updateById(any());
    }

    @Test
    void failedRuntimeQueryKeepsPersistedStatusUntouched() {
        when(serviceMapper.selectById(9L)).thenReturn(service(9L, 7L, 1));
        when(environmentMapper.selectById(7L)).thenReturn(environment(7L));
        when(deployService.getStatus("docker", 9L))
                .thenReturn(DeployResult.failure("manager unavailable"));

        assertFalse(syncService.refreshService(9L));

        verify(serviceStatusMapper, never()).insert(any());
        verify(serviceStatusMapper, never()).updateById(any());
    }

    private AppService service(Long id, Long environmentId, Integer replicas) {
        AppService service = new AppService();
        service.setId(id);
        service.setEnvironmentId(environmentId);
        service.setName("api");
        service.setReplicas(replicas);
        service.setServiceMode("replicated");
        return service;
    }

    private Environment environment(Long id) {
        Environment environment = new Environment();
        environment.setId(id);
        environment.setName("test");
        environment.setDeployType("docker");
        return environment;
    }
}
