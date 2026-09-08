package com.easy.cd.deploy.strategy;

import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerServiceConvergenceMonitorTest {

    private static final String SERVICE = "api";
    private static final String IMAGE = "registry.example.com/team/api:new";

    @Mock
    private SshExecutor sshExecutor;

    private DockerServiceConvergenceMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new DockerServiceConvergenceMonitor(sshExecutor);
        ReflectionTestUtils.setField(monitor, "convergenceTimeoutMs", 100);
        ReflectionTestUtils.setField(monitor, "compensationTimeoutMs", 100);
        ReflectionTestUtils.setField(monitor, "pollIntervalMs", 1);
        ReflectionTestUtils.setField(monitor, "maxTaskFailures", 3);
    }

    @Test
    void reportsConvergedWhenExpectedImageAndReplicasAreReady() {
        when(sshExecutor.executeCommandWithFailoverQuiet(anyList(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1);
                    if (command.startsWith("docker service inspect")) {
                        return success(inspect(IMAGE + "@sha256:abc", "completed"));
                    }
                    if (command.startsWith("docker service ls")) {
                        return success(serviceRow("1/1"));
                    }
                    return success("");
                });

        DockerServiceConvergenceMonitor.ConvergenceResult result = monitor.awaitDeployment(
                hosts(), SERVICE, IMAGE, "registry.example.com/team/api:old", Collections.emptySet());

        assertTrue(result.isSuccess());
    }

    @Test
    void failsAfterThreeNewRejectedOrFailedTasks() {
        when(sshExecutor.executeCommandWithFailoverQuiet(anyList(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1);
                    if (command.startsWith("docker service inspect")) {
                        return success(inspect(IMAGE, "updating"));
                    }
                    if (command.startsWith("docker service ls")) {
                        return success(serviceRow("0/1"));
                    }
                    return success(
                            task("task-1", "Rejected 1 second ago", "pull access denied") + "\n"
                                    + task("task-2", "Failed 1 second ago", "manifest unknown") + "\n"
                                    + task("task-3", "Rejected 1 second ago", "no such image"));
                });

        DockerServiceConvergenceMonitor.ConvergenceResult result = monitor.awaitDeployment(
                hosts(), SERVICE, IMAGE, "registry.example.com/team/api:old", Collections.emptySet());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("3"));
        assertTrue(result.getMessage().contains("no such image"));
    }

    @Test
    void recognizesCompletedAutomaticRollbackAsFailedDeploymentWithCompensation() {
        String previousImage = "registry.example.com/team/api:old";
        when(sshExecutor.executeCommandWithFailoverQuiet(anyList(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(1);
                    if (command.startsWith("docker service inspect")) {
                        return success(inspect(previousImage + "@sha256:old", "rollback_completed"));
                    }
                    if (command.startsWith("docker service ls")) {
                        return success(serviceRow("1/1"));
                    }
                    return success("");
                });

        DockerServiceConvergenceMonitor.ConvergenceResult result = monitor.awaitDeployment(
                hosts(), SERVICE, IMAGE, previousImage, Collections.emptySet());

        assertFalse(result.isSuccess());
        assertTrue(result.isRolledBack());
    }

    private java.util.List<SshHost> hosts() {
        return Collections.singletonList(new SshHost("manager", 22, "root", null, null));
    }

    private SshResult success(String stdout) {
        return new SshResult(0, stdout, "");
    }

    private String inspect(String image, String updateState) {
        return "{\"Spec\":{\"TaskTemplate\":{\"ContainerSpec\":{\"Image\":\"" + image
                + "\"}}},\"UpdateStatus\":{\"State\":\"" + updateState + "\"}}";
    }

    private String serviceRow(String replicas) {
        return "{\"Name\":\"" + SERVICE + "\",\"Replicas\":\"" + replicas + "\"}";
    }

    private String task(String id, String currentState, String error) {
        return "{\"ID\":\"" + id + "\",\"CurrentState\":\"" + currentState
                + "\",\"Error\":\"" + error + "\"}";
    }
}
