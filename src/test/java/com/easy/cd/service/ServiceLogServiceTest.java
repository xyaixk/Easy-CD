package com.easy.cd.service;

import com.easy.cd.dto.ServiceLogInstanceDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceLogServiceTest {

    private static final Long SERVICE_ID = 9L;

    @Mock
    private ServiceMapper serviceMapper;

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private SshExecutor sshExecutor;

    private ServiceLogService serviceLogService;

    @BeforeEach
    void setUp() {
        serviceLogService = new ServiceLogService(serviceMapper, environmentMapper, sshExecutor);
        when(serviceMapper.selectById(SERVICE_ID)).thenReturn(appService());
        when(environmentMapper.selectById(2L)).thenReturn(environment());
    }

    @Test
    void listsEveryTraceableTaskWithRunningTasksFirstAndHistoryByLatestStatus() {
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isServicePsCommand)))
                .thenReturn(success(
                        "{\"ID\":\"failednew\",\"Name\":\"api.1\",\"Node\":\"node-a\"}\n" +
                        "{\"ID\":\"runningold\",\"Name\":\"api.1\",\"Node\":\"node-a\"}\n" +
                        "{\"ID\":\"retiring\",\"Name\":\"api.2\",\"Node\":\"node-b\"}\n" +
                        "{\"ID\":\"shutdownold\",\"Name\":\"api.2\",\"Node\":\"node-b\"}"));
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isTaskInspectCommand)))
                .thenReturn(success(
                        taskJson("failednew", 1, "shutdown", "failed", "2026-09-07T10:00:00Z", "boom") + "\n" +
                        taskJson("runningold", 1, "running", "running", "2026-09-07T08:00:00Z", "") + "\n" +
                        taskJson("retiring", 2, "shutdown", "running", "2026-09-07T11:00:00Z", "") + "\n" +
                        taskJson("shutdownold", 2, "shutdown", "shutdown", "2026-09-06T12:00:00Z", "")));

        List<ServiceLogInstanceDTO> result = serviceLogService.listInstances(SERVICE_ID);

        assertEquals(4, result.size());
        assertEquals("runningold", result.get(0).getTaskId());
        assertTrue(result.get(0).isRunning());
        assertEquals("retiring", result.get(1).getTaskId());
        assertFalse(result.get(1).isRunning());
        assertEquals("failednew", result.get(2).getTaskId());
        assertEquals("boom", result.get(2).getErrorMessage());
        assertEquals("shutdownold", result.get(3).getTaskId());
        assertFalse(result.get(3).isRunning());
        assertEquals("node-b", result.get(3).getNode());
    }

    @Test
    void returnsEmptyListWhenSwarmHasNoTraceableTasks() {
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isServicePsCommand)))
                .thenReturn(success(""));

        assertTrue(serviceLogService.listInstances(SERVICE_ID).isEmpty());
    }

    @Test
    void reportsSwarmQueryFailure() {
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isServicePsCommand)))
                .thenReturn(new SshResult(1, "", "manager unavailable"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> serviceLogService.listInstances(SERVICE_ID));

        assertTrue(error.getMessage().contains("manager unavailable"));
    }

    @Test
    void keepsTraceableTasksWhenHistoryIsPrunedDuringInspection() {
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isServicePsCommand)))
                .thenReturn(success(
                        "{\"ID\":\"running1\",\"Name\":\"api.1\",\"Node\":\"node-a\"}\n" +
                        "{\"ID\":\"pruned1\",\"Name\":\"api.1\",\"Node\":\"node-a\"}"));
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isTaskInspectCommand)))
                .thenReturn(new SshResult(
                        1,
                        taskJson("running1", 1, "running", "running", "2026-09-07T10:00:00Z", ""),
                        "Error: No such object: pruned1"));

        List<ServiceLogInstanceDTO> result = serviceLogService.listInstances(SERVICE_ID);

        assertEquals(1, result.size());
        assertEquals("running1", result.get(0).getTaskId());
    }

    @Test
    void rejectsTaskIdsThatDoNotComeFromDocker() {
        when(sshExecutor.executeCommandWithFailover(anyList(), argThat(this::isServicePsCommand)))
                .thenReturn(success("{\"ID\":\"bad;id\",\"Name\":\"api.1\",\"Node\":\"node-a\"}"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> serviceLogService.listInstances(SERVICE_ID));

        assertTrue(error.getMessage().contains("Task ID"));
    }

    private boolean isServicePsCommand(String command) {
        return command != null && command.startsWith("docker service ps api ");
    }

    private boolean isTaskInspectCommand(String command) {
        return command != null && command.startsWith("docker inspect --type task ");
    }

    private AppService appService() {
        AppService service = new AppService();
        service.setId(SERVICE_ID);
        service.setEnvironmentId(2L);
        service.setName("api");
        service.setExternalServiceName("api");
        return service;
    }

    private Environment environment() {
        Environment environment = new Environment();
        environment.setId(2L);
        environment.setDeployType("docker");
        environment.setConfig("{\"swarmManagerHosts\":[{\"host\":\"manager\",\"port\":22,\"username\":\"root\"}]}");
        return environment;
    }

    private String taskJson(String id, int slot, String desiredState, String state,
                            String timestamp, String error) {
        return "{\"ID\":\"" + id + "\",\"Slot\":" + slot +
                ",\"DesiredState\":\"" + desiredState + "\",\"Status\":{" +
                "\"State\":\"" + state + "\",\"Timestamp\":\"" + timestamp +
                "\",\"Err\":\"" + error + "\"}}";
    }

    private SshResult success(String stdout) {
        return new SshResult(0, stdout, "");
    }
}
