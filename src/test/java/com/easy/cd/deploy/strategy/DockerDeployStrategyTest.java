package com.easy.cd.deploy.strategy;

import com.easy.cd.deploy.model.DeployRequest;
import com.easy.cd.deploy.model.DeployResult;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerDeployStrategyTest {

    private static final Long ENVIRONMENT_ID = 1L;
    private static final String IMAGE = "registry.example.com/team/api:missing";
    private static final String SERVICE_NAME = "api";

    @Mock
    private EnvironmentMapper environmentMapper;

    @Mock
    private ServiceMapper serviceMapper;

    @Mock
    private SshExecutor sshExecutor;

    @Mock
    private DockerServiceConvergenceMonitor convergenceMonitor;

    private DockerDeployStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new DockerDeployStrategy(environmentMapper, serviceMapper, sshExecutor, convergenceMonitor);
        ReflectionTestUtils.setField(strategy, "writeSshTimeoutMs", 60_000);
        ReflectionTestUtils.setField(strategy, "imagePullTimeoutMs", 180_000);
        when(environmentMapper.selectById(ENVIRONMENT_ID)).thenReturn(environment());
    }

    @Test
    void createStopsBeforeSwarmMutationWhenImagePullFails() {
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(failure("service not found"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt()))
                .thenReturn(failure("manifest unknown"));

        DeployResult result = strategy.deploy(createRequest());

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("镜像拉取失败"));
        assertTrue(result.getMessage().contains(IMAGE));
        assertTrue(result.getMessage().contains("manifest unknown"));
        verify(sshExecutor).executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt());
        verify(sshExecutor, never()).executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker service create")), anyInt());
    }

    @Test
    void updateStopsBeforeSwarmMutationWhenChangedImagePullFails() {
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(success("service-id"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(inspectCommand())))
                .thenReturn(success(inspectJson()));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(currentImageCommand())))
                .thenReturn(success("registry.example.com/team/api:old@sha256:abc"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt()))
                .thenReturn(failure("manifest unknown"));

        DeployResult result = strategy.deploy(updateRequest());

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("镜像拉取失败"));
        verify(sshExecutor).executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt());
        verify(sshExecutor, never()).executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker service update")), anyInt());
    }

    @Test
    void createContinuesWhenImagePullSucceeds() {
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(failure("service not found"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt()))
                .thenReturn(success("pulled"));
        when(sshExecutor.executeCommandWithFailover(
                anyList(), argThat(command -> command != null && command.startsWith("docker service create")), anyInt()))
                .thenReturn(success("service-id"));
        when(convergenceMonitor.awaitDeployment(anyList(), eq(SERVICE_NAME), eq(IMAGE),
                eq(null), eq(java.util.Collections.emptySet())))
                .thenReturn(DockerServiceConvergenceMonitor.ConvergenceResult.converged("1/1"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(inspectCommand())))
                .thenReturn(success(inspectJson()));

        DeployResult result = strategy.deploy(createRequest());

        assertTrue(result.getSuccess());
        verify(sshExecutor).executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker service create --detach --with-registry-auth")), anyInt());
    }

    @Test
    void updateContinuesWhenChangedImagePullSucceeds() {
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(success("service-id"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(inspectCommand())))
                .thenReturn(success(inspectJson()));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(currentImageCommand())))
                .thenReturn(success("registry.example.com/team/api:old@sha256:abc"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt()))
                .thenReturn(success("pulled"));
        when(convergenceMonitor.captureTaskIds(anyList(), eq(SERVICE_NAME)))
                .thenReturn(java.util.Collections.emptySet());
        when(sshExecutor.executeCommandWithFailover(
                anyList(), argThat(command -> command != null && command.startsWith("docker service update")), anyInt()))
                .thenReturn(success(SERVICE_NAME));
        when(convergenceMonitor.awaitDeployment(anyList(), eq(SERVICE_NAME), eq(IMAGE),
                eq("registry.example.com/team/api:old@sha256:abc"), eq(java.util.Collections.emptySet())))
                .thenReturn(DockerServiceConvergenceMonitor.ConvergenceResult.converged("1/1"));

        DeployResult result = strategy.deploy(updateRequest());

        assertTrue(result.getSuccess());
        verify(sshExecutor).executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt());
        verify(sshExecutor).executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker service update --detach --with-registry-auth")
                        && command.contains("--update-failure-action rollback")), anyInt());
    }

    @Test
    void updateSkipsPullWhenImageIsUnchanged() {
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(success("service-id"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(inspectCommand())))
                .thenReturn(success(inspectJson()));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(currentImageCommand())))
                .thenReturn(success(IMAGE + "@sha256:abc"));
        when(convergenceMonitor.captureTaskIds(anyList(), eq(SERVICE_NAME)))
                .thenReturn(java.util.Collections.emptySet());
        when(sshExecutor.executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker service update")), anyInt()))
                .thenReturn(success(SERVICE_NAME));
        when(convergenceMonitor.awaitDeployment(anyList(), eq(SERVICE_NAME), eq(IMAGE),
                eq(IMAGE + "@sha256:abc"), eq(java.util.Collections.emptySet())))
                .thenReturn(DockerServiceConvergenceMonitor.ConvergenceResult.converged("1/1"));

        DeployResult result = strategy.deploy(updateRequest());

        assertTrue(result.getSuccess());
        verify(sshExecutor, never()).executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker pull ")), anyInt());
        verify(sshExecutor).executeCommandWithFailover(
                anyList(), argThat(command -> command.startsWith("docker service update")), anyInt());
    }

    @Test
    void createRemovesServiceWhenWorkersCannotConverge() {
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(failure("service not found"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt()))
                .thenReturn(success("pulled"));
        when(sshExecutor.executeCommandWithFailover(
                anyList(), argThat(command -> command != null && command.startsWith("docker service create")), anyInt()))
                .thenReturn(success("service-id"));
        when(convergenceMonitor.awaitDeployment(anyList(), eq(SERVICE_NAME), eq(IMAGE),
                eq(null), eq(java.util.Collections.emptySet())))
                .thenReturn(DockerServiceConvergenceMonitor.ConvergenceResult.failed("3 个 task 拉取失败"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker service rm " + SERVICE_NAME), anyInt()))
                .thenReturn(success(SERVICE_NAME));
        when(convergenceMonitor.awaitRemoval(anyList(), eq(SERVICE_NAME))).thenReturn(true);

        DeployResult result = strategy.deploy(createRequest());

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("3 个 task 拉取失败"));
        assertTrue(result.getMessage().contains("已删除失败服务"));
        verify(sshExecutor).executeCommandWithFailover(
                anyList(), eq("docker service rm " + SERVICE_NAME), anyInt());
    }

    @Test
    void updateRollsBackWhenWorkersCannotConverge() {
        String previousImage = "registry.example.com/team/api:old@sha256:abc";
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(serviceExistsCommand())))
                .thenReturn(success("service-id"));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(inspectCommand())))
                .thenReturn(success(inspectJson()));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq(currentImageCommand())))
                .thenReturn(success(previousImage));
        when(sshExecutor.executeCommandWithFailover(anyList(), eq("docker pull " + IMAGE), anyInt()))
                .thenReturn(success("pulled"));
        when(convergenceMonitor.captureTaskIds(anyList(), eq(SERVICE_NAME)))
                .thenReturn(java.util.Collections.emptySet());
        when(sshExecutor.executeCommandWithFailover(
                anyList(), argThat(command -> command != null && command.startsWith("docker service update")), anyInt()))
                .thenReturn(success(SERVICE_NAME));
        when(convergenceMonitor.awaitDeployment(anyList(), eq(SERVICE_NAME), eq(IMAGE),
                eq(previousImage), eq(java.util.Collections.emptySet())))
                .thenReturn(DockerServiceConvergenceMonitor.ConvergenceResult.failed("worker pull access denied"));
        when(sshExecutor.executeCommandWithFailover(anyList(),
                eq("docker service update --rollback --detach " + SERVICE_NAME), anyInt()))
                .thenReturn(success(SERVICE_NAME));
        when(convergenceMonitor.awaitRollback(anyList(), eq(SERVICE_NAME), eq(previousImage)))
                .thenReturn(DockerServiceConvergenceMonitor.ConvergenceResult.converged("1/1"));

        DeployResult result = strategy.deploy(updateRequest());

        assertFalse(result.getSuccess());
        assertTrue(result.getMessage().contains("worker pull access denied"));
        assertTrue(result.getMessage().contains("已回滚到原服务版本"));
        verify(sshExecutor).executeCommandWithFailover(anyList(),
                eq("docker service update --rollback --detach " + SERVICE_NAME), anyInt());
    }

    private DeployRequest createRequest() {
        return DeployRequest.builder()
                .serviceName(SERVICE_NAME)
                .dockerImage("team/api:missing")
                .dockerParams("{\"network\":\"host\"}")
                .replicas(1)
                .serviceMode("replicated")
                .environmentId(ENVIRONMENT_ID)
                .build();
    }

    private DeployRequest updateRequest() {
        return DeployRequest.builder()
                .serviceId(9L)
                .serviceName(SERVICE_NAME)
                .dockerImage("team/api:missing")
                .dockerParams("{}")
                .replicas(1)
                .serviceMode("replicated")
                .environmentId(ENVIRONMENT_ID)
                .build();
    }

    private Environment environment() {
        Environment environment = new Environment();
        environment.setId(ENVIRONMENT_ID);
        environment.setName("test");
        environment.setDeployType("docker");
        environment.setConfig("{\"registryUrl\":\"registry.example.com\","
                + "\"swarmManagerHosts\":[{\"host\":\"manager\",\"port\":22,\"username\":\"root\"}]}");
        return environment;
    }

    private String serviceExistsCommand() {
        return "docker service inspect " + SERVICE_NAME + " --format '{{.ID}}' 2>/dev/null";
    }

    private String inspectCommand() {
        return "docker service inspect " + SERVICE_NAME + " --format '{{json .}}'";
    }

    private String currentImageCommand() {
        return "docker service inspect " + SERVICE_NAME
                + " --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}'";
    }

    private String inspectJson() {
        return "{\"ID\":\"service-id\",\"Spec\":{\"Mode\":{\"Replicated\":{\"Replicas\":1}}}}";
    }

    private SshResult success(String stdout) {
        return new SshResult(0, stdout, "");
    }

    private SshResult failure(String stderr) {
        return new SshResult(1, "", stderr);
    }
}
