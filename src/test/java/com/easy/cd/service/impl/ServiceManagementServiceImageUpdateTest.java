package com.easy.cd.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.easy.cd.deploy.DeployService;
import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.deploy.queue.DeployTaskQueueService;
import com.easy.cd.dto.ServiceUpdateDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaMetricsMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.mapper.ServiceStatusMapper;
import com.easy.cd.service.ServiceGroupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceManagementServiceImageUpdateTest {

    @Test
    void imageUpdatePersistsAutomaticRollbackPolicy() {
        ServiceMapper serviceMapper = mock(ServiceMapper.class);
        ServiceStatusMapper statusMapper = mock(ServiceStatusMapper.class);
        EnvironmentMapper environmentMapper = mock(EnvironmentMapper.class);
        DeployService deployService = mock(DeployService.class);
        ServiceManagementServiceImpl service = new ServiceManagementServiceImpl(
                serviceMapper,
                statusMapper,
                mock(ReplicaStatusMapper.class),
                mock(ReplicaMetricsMapper.class),
                environmentMapper,
                deployService,
                mock(DeployTaskQueueService.class),
                mock(ServiceGroupService.class));

        AppService appService = appService();
        Environment environment = new Environment();
        environment.setId(7L);
        environment.setName("test");
        environment.setDeployType("docker");
        when(serviceMapper.selectById(9L)).thenReturn(appService);
        when(environmentMapper.selectById(7L)).thenReturn(environment);
        when(deployService.deploy(eq("docker"), org.mockito.ArgumentMatchers.any(DeployRequest.class)))
                .thenReturn(DeployResult.builder()
                        .success(true)
                        .status("running")
                        .instances(1)
                        .healthyInstances(1)
                        .desiredInstances(1)
                        .build());

        ServiceUpdateDTO update = new ServiceUpdateDTO();
        update.setName("api");
        update.setDockerImage("registry.example.com/team/api:new");
        update.setDockerParams("{\"restart\":\"any\",\"update_failure_action\":\"continue\"}");

        service.doUpdate(9L, update);

        ArgumentCaptor<DeployRequest> requestCaptor = ArgumentCaptor.forClass(DeployRequest.class);
        verify(deployService).deploy(eq("docker"), requestCaptor.capture());
        JSONObject requestParams = JSON.parseObject(requestCaptor.getValue().getDockerParams());
        JSONObject savedParams = JSON.parseObject(appService.getDockerParams());
        assertEquals("rollback", requestParams.getString("update_failure_action"));
        assertEquals("rollback", savedParams.getString("update_failure_action"));
        assertEquals("continue", JSON.parseObject(update.getDockerParams())
                .getString("update_failure_action"));
    }

    private AppService appService() {
        AppService service = new AppService();
        service.setId(9L);
        service.setName("api");
        service.setDescription("API");
        service.setVersion("old");
        service.setEnvironmentId(7L);
        service.setDockerImage("registry.example.com/team/api:old");
        service.setDockerParams("{\"restart\":\"any\"}");
        service.setReplicas(1);
        service.setServiceMode("replicated");
        service.setCreatedTime(LocalDateTime.now());
        return service;
    }
}
