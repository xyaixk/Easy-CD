package com.easy.cd.deploy.queue;

import com.easy.cd.entity.DeployTask;
import com.easy.cd.mapper.DeployTaskMapper;
import com.easy.cd.service.ServiceStatusSyncService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeployTaskQueueServiceTest {

    @Test
    void refreshesServiceBeforePublishingSuccessfulTerminalState() {
        DeployTaskMapper mapper = mock(DeployTaskMapper.class);
        ServiceStatusSyncService statusSyncService = mock(ServiceStatusSyncService.class);
        DeployTask task = task("UPDATE");
        List<String> events = new ArrayList<>();
        when(mapper.selectById(41L)).thenReturn(task);
        doAnswer(invocation -> {
            String status = ((DeployTask) invocation.getArgument(0)).getStatus();
            if (status != null) {
                events.add("db:" + status);
            }
            return 1;
        }).when(mapper).updateById(any(DeployTask.class));
        doAnswer(invocation -> {
            events.add("sync:" + invocation.getArgument(2));
            return null;
        }).when(statusSyncService).refreshAfterTask("UPDATE", 9L, true);

        DeployTaskQueueService queueService = new DeployTaskQueueService(mapper, statusSyncService);
        queueService.runTask(41L, () -> events.add("action"));

        assertEquals(Arrays.asList("db:RUNNING", "action", "sync:true", "db:SUCCESS"), events);
    }

    @Test
    void refreshesActualServiceStateBeforePublishingFailure() {
        DeployTaskMapper mapper = mock(DeployTaskMapper.class);
        ServiceStatusSyncService statusSyncService = mock(ServiceStatusSyncService.class);
        DeployTask task = task("ROLLBACK");
        List<String> events = new ArrayList<>();
        when(mapper.selectById(41L)).thenReturn(task);
        doAnswer(invocation -> {
            String status = ((DeployTask) invocation.getArgument(0)).getStatus();
            if (status != null) {
                events.add("db:" + status);
            }
            return 1;
        }).when(mapper).updateById(any(DeployTask.class));
        doAnswer(invocation -> {
            events.add("sync:" + invocation.getArgument(2));
            return null;
        }).when(statusSyncService).refreshAfterTask("ROLLBACK", 9L, false);

        DeployTaskQueueService queueService = new DeployTaskQueueService(mapper, statusSyncService);
        queueService.runTask(41L, () -> {
            events.add("action");
            throw new IllegalStateException("deployment failed");
        });

        assertEquals(
                Arrays.asList("db:RUNNING", "action", "sync:false", "db:FAILED"),
                events);
    }

    @Test
    void statusRefreshFailureDoesNotChangeSuccessfulTaskResult() {
        DeployTaskMapper mapper = mock(DeployTaskMapper.class);
        ServiceStatusSyncService statusSyncService = mock(ServiceStatusSyncService.class);
        DeployTask task = task("UPDATE");
        when(mapper.selectById(41L)).thenReturn(task);
        doAnswer(invocation -> {
            throw new IllegalStateException("status unavailable");
        }).when(statusSyncService).refreshAfterTask("UPDATE", 9L, true);

        DeployTaskQueueService queueService = new DeployTaskQueueService(mapper, statusSyncService);
        queueService.runTask(41L, () -> { });

        assertEquals(DeployTaskQueueService.STATUS_SUCCESS, task.getStatus());
    }

    private DeployTask task(String taskType) {
        DeployTask task = new DeployTask();
        task.setId(41L);
        task.setEnvironmentId(7L);
        task.setServiceId(9L);
        task.setServiceName("api");
        task.setTaskType(taskType);
        task.setStatus(DeployTaskQueueService.STATUS_PENDING);
        return task;
    }
}
