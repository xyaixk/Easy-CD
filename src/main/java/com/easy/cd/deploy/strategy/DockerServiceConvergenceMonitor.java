package com.easy.cd.deploy.strategy;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.easy.cd.deploy.queue.TaskLogContext;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对已提交的 Swarm 服务变更做有界收敛检查，并提取 Worker task 的失败原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerServiceConvergenceMonitor {

    private static final int POLL_COMMAND_TIMEOUT_MS = 30_000;

    private final SshExecutor sshExecutor;

    @Value("${deploy.convergence-timeout-ms:300000}")
    private int convergenceTimeoutMs;

    @Value("${deploy.compensation-timeout-ms:300000}")
    private int compensationTimeoutMs;

    @Value("${deploy.status-poll-interval-ms:2000}")
    private int pollIntervalMs;

    @Value("${deploy.max-task-failures:3}")
    private int maxTaskFailures;

    public Set<String> captureTaskIds(List<SshHost> hosts, String serviceName) {
        SshResult result = executeQuiet(hosts, taskListCommand(serviceName));
        if (!result.isSuccess()) {
            throw new RuntimeException("读取服务现有 task 失败[" + serviceName + "]: " + commandError(result));
        }
        return parseTaskIds(result.getStdout());
    }

    public ConvergenceResult awaitDeployment(List<SshHost> hosts, String serviceName,
                                             String expectedImage, String rollbackImage,
                                             Set<String> baselineTaskIds) {
        Set<String> baseline = baselineTaskIds == null
                ? Collections.<String>emptySet()
                : new HashSet<>(baselineTaskIds);
        return awaitState(hosts, serviceName, expectedImage, rollbackImage, baseline,
                convergenceTimeoutMs, false);
    }

    public ConvergenceResult awaitRollback(List<SshHost> hosts, String serviceName, String expectedImage) {
        return awaitState(hosts, serviceName, expectedImage, null, Collections.<String>emptySet(),
                compensationTimeoutMs, true);
    }

    public boolean awaitRemoval(List<SshHost> hosts, String serviceName) {
        long deadline = deadlineAfter(compensationTimeoutMs);
        while (true) {
            SshResult result = executeQuiet(hosts, inspectCommand(serviceName));
            if (!result.isSuccess() && !result.isConnectionFailure() && !result.isTimeout()) {
                TaskLogContext.append("[补偿状态] 失败服务已删除: " + serviceName);
                return true;
            }
            if (System.currentTimeMillis() >= deadline || !sleepUntilNextPoll(deadline)) {
                return false;
            }
        }
    }

    private ConvergenceResult awaitState(List<SshHost> hosts, String serviceName,
                                         String expectedImage, String rollbackImage,
                                         Set<String> baselineTaskIds, int timeoutMs,
                                         boolean rollbackPhase) {
        long deadline = deadlineAfter(timeoutMs);
        Set<String> failedTaskIds = new HashSet<>();
        String lastTaskError = null;
        String lastProgress = null;

        while (true) {
            SnapshotRead snapshotRead = readSnapshot(hosts, serviceName);
            ServiceSnapshot snapshot = snapshotRead.snapshot;
            if (snapshot != null) {
                String updateState = lower(snapshot.updateState);
                String progress = progressText(rollbackPhase, snapshot, failedTaskIds.size());
                if (!progress.equals(lastProgress)) {
                    TaskLogContext.append(progress);
                    lastProgress = progress;
                }

                if (!rollbackPhase && isRollbackState(updateState)) {
                    if (rollbackImage == null) {
                        return ConvergenceResult.failed("服务进入回滚状态，但缺少原镜像信息");
                    }
                    ConvergenceResult rollbackResult = awaitRollback(hosts, serviceName, rollbackImage);
                    if (rollbackResult.isSuccess()) {
                        return ConvergenceResult.rolledBack("新版本部署失败，Swarm 已自动回滚: "
                                + rollbackResult.getMessage());
                    }
                    return ConvergenceResult.failed("新版本部署失败，自动回滚未完成: "
                            + rollbackResult.getMessage());
                }

                if (rollbackPhase && "rollback_paused".equals(updateState)) {
                    return ConvergenceResult.failed("服务回滚已暂停" + errorSuffix(lastTaskError));
                }
                if (!rollbackPhase && "paused".equals(updateState)) {
                    return ConvergenceResult.failed("服务更新已暂停" + errorSuffix(lastTaskError));
                }

                boolean expectedImageActive = sameImage(snapshot.image, expectedImage);
                boolean replicasReady = snapshot.desiredReplicas > 0
                        && snapshot.runningReplicas == snapshot.desiredReplicas;
                if (expectedImageActive && replicasReady
                        && updateStateAllowsSuccess(updateState, rollbackPhase)) {
                    return ConvergenceResult.converged(
                            snapshot.runningReplicas + "/" + snapshot.desiredReplicas);
                }
            }

            if (!rollbackPhase) {
                TaskFailureRead taskRead = readNewTaskFailures(hosts, serviceName, baselineTaskIds,
                        failedTaskIds);
                if (taskRead.lastError != null) {
                    lastTaskError = taskRead.lastError;
                }
                if (failedTaskIds.size() >= Math.max(1, maxTaskFailures)) {
                    return ConvergenceResult.failed("检测到 " + failedTaskIds.size()
                            + " 个新 task 进入 Rejected/Failed" + errorSuffix(lastTaskError));
                }
            }

            if (System.currentTimeMillis() >= deadline) {
                String detail = lastTaskError != null ? lastTaskError : snapshotRead.error;
                return ConvergenceResult.failed((rollbackPhase ? "服务回滚" : "服务部署")
                        + "等待超时(" + timeoutMs + "ms)" + errorSuffix(detail));
            }
            if (!sleepUntilNextPoll(deadline)) {
                return ConvergenceResult.failed((rollbackPhase ? "服务回滚" : "服务部署")
                        + "等待被中断");
            }
        }
    }

    private SnapshotRead readSnapshot(List<SshHost> hosts, String serviceName) {
        SshResult inspect = executeQuiet(hosts, inspectCommand(serviceName));
        if (!inspect.isSuccess() || !inspect.hasOutput()) {
            return SnapshotRead.error(commandError(inspect));
        }

        try {
            JSONObject service = JSON.parseObject(inspect.getStdout().trim());
            JSONObject spec = service.getJSONObject("Spec");
            JSONObject taskTemplate = spec == null ? null : spec.getJSONObject("TaskTemplate");
            JSONObject containerSpec = taskTemplate == null ? null : taskTemplate.getJSONObject("ContainerSpec");
            String image = containerSpec == null ? null : containerSpec.getString("Image");
            JSONObject updateStatus = service.getJSONObject("UpdateStatus");
            String updateState = updateStatus == null ? null : updateStatus.getString("State");

            SshResult list = executeQuiet(hosts, serviceListCommand(serviceName));
            if (!list.isSuccess() || !list.hasOutput()) {
                return SnapshotRead.error(commandError(list));
            }
            ReplicaCount replicas = parseExactReplicaCount(list.getStdout(), serviceName);
            if (replicas == null) {
                return SnapshotRead.error("未找到精确服务副本信息: " + serviceName);
            }
            return SnapshotRead.success(new ServiceSnapshot(
                    image, updateState, replicas.running, replicas.desired));
        } catch (Exception e) {
            return SnapshotRead.error("解析服务状态失败: " + e.getMessage());
        }
    }

    private TaskFailureRead readNewTaskFailures(List<SshHost> hosts, String serviceName,
                                                Set<String> baselineTaskIds,
                                                Set<String> failedTaskIds) {
        SshResult result = executeQuiet(hosts, taskListCommand(serviceName));
        if (!result.isSuccess() || !result.hasOutput()) {
            return TaskFailureRead.empty();
        }

        String lastError = null;
        for (String line : result.getStdout().split("\\r?\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JSONObject task = JSON.parseObject(line.trim());
                String id = task.getString("ID");
                String currentState = lower(task.getString("CurrentState"));
                if (currentState == null) {
                    currentState = "";
                }
                if (id == null || baselineTaskIds.contains(id)
                        || (!currentState.startsWith("rejected") && !currentState.startsWith("failed"))) {
                    continue;
                }
                failedTaskIds.add(id);
                String error = trimToNull(task.getString("Error"));
                lastError = error != null ? error : task.getString("CurrentState");
            } catch (Exception e) {
                log.debug("解析 Swarm task 状态失败: {}", line);
            }
        }
        return new TaskFailureRead(lastError);
    }

    private Set<String> parseTaskIds(String output) {
        Set<String> ids = new HashSet<>();
        if (output == null || output.trim().isEmpty()) {
            return ids;
        }
        for (String line : output.split("\\r?\\n")) {
            try {
                JSONObject task = JSON.parseObject(line.trim());
                String id = task.getString("ID");
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id);
                }
            } catch (Exception e) {
                log.debug("解析 Swarm task ID 失败: {}", line);
            }
        }
        return ids;
    }

    private ReplicaCount parseExactReplicaCount(String output, String serviceName) {
        for (String line : output.split("\\r?\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JSONObject row = JSON.parseObject(line.trim());
            if (!serviceName.equals(row.getString("Name"))) {
                continue;
            }
            String value = trimToNull(row.getString("Replicas"));
            if (value == null) {
                return null;
            }
            String token = value.split("\\s+", 2)[0];
            String[] parts = token.split("/", 2);
            if (parts.length != 2) {
                return null;
            }
            return new ReplicaCount(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        return null;
    }

    private SshResult executeQuiet(List<SshHost> hosts, String command) {
        return sshExecutor.executeCommandWithFailoverQuiet(hosts, command, POLL_COMMAND_TIMEOUT_MS);
    }

    private boolean sleepUntilNextPoll(long deadline) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return true;
        }
        try {
            Thread.sleep(Math.min(Math.max(1, pollIntervalMs), remaining));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long deadlineAfter(int timeoutMs) {
        return System.currentTimeMillis() + Math.max(1, timeoutMs);
    }

    private boolean updateStateAllowsSuccess(String updateState, boolean rollbackPhase) {
        if (rollbackPhase) {
            return updateState == null || "completed".equals(updateState)
                    || "rollback_completed".equals(updateState);
        }
        return updateState == null || "completed".equals(updateState);
    }

    private boolean isRollbackState(String updateState) {
        return updateState != null && updateState.startsWith("rollback_");
    }

    private boolean sameImage(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return stripDigest(actual.trim()).equals(stripDigest(expected.trim()));
    }

    private String stripDigest(String image) {
        int at = image.indexOf("@sha256:");
        return at > 0 ? image.substring(0, at) : image;
    }

    private String progressText(boolean rollbackPhase, ServiceSnapshot snapshot, int failures) {
        return "[" + (rollbackPhase ? "回滚状态" : "部署状态") + "] 服务副本 "
                + snapshot.runningReplicas + "/" + snapshot.desiredReplicas
                + "，updateState=" + (snapshot.updateState == null ? "-" : snapshot.updateState)
                + "，failedTasks=" + failures;
    }

    private String inspectCommand(String serviceName) {
        return "docker service inspect " + serviceName + " --format '{{json .}}' 2>/dev/null";
    }

    private String serviceListCommand(String serviceName) {
        return "docker service ls --filter name=" + serviceName + " --format '{{json .}}'";
    }

    private String taskListCommand(String serviceName) {
        return "docker service ps " + serviceName + " --no-trunc --format '{{json .}}'";
    }

    private String commandError(SshResult result) {
        if (result == null) {
            return "未返回执行结果";
        }
        String stderr = trimToNull(result.getStderr());
        if (stderr != null) {
            return stderr;
        }
        String stdout = trimToNull(result.getStdout());
        return stdout != null ? stdout : "exitCode=" + result.getExitCode();
    }

    private String errorSuffix(String error) {
        return error == null || error.trim().isEmpty() ? "" : "，最后错误: " + error.trim();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public static final class ConvergenceResult {
        private final boolean success;
        private final boolean rolledBack;
        private final String message;

        private ConvergenceResult(boolean success, boolean rolledBack, String message) {
            this.success = success;
            this.rolledBack = rolledBack;
            this.message = message;
        }

        public static ConvergenceResult converged(String message) {
            return new ConvergenceResult(true, false, message);
        }

        public static ConvergenceResult failed(String message) {
            return new ConvergenceResult(false, false, message);
        }

        public static ConvergenceResult rolledBack(String message) {
            return new ConvergenceResult(false, true, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isRolledBack() {
            return rolledBack;
        }

        public String getMessage() {
            return message;
        }
    }

    private static final class SnapshotRead {
        private final ServiceSnapshot snapshot;
        private final String error;

        private SnapshotRead(ServiceSnapshot snapshot, String error) {
            this.snapshot = snapshot;
            this.error = error;
        }

        private static SnapshotRead success(ServiceSnapshot snapshot) {
            return new SnapshotRead(snapshot, null);
        }

        private static SnapshotRead error(String error) {
            return new SnapshotRead(null, error);
        }
    }

    private static final class ServiceSnapshot {
        private final String image;
        private final String updateState;
        private final int runningReplicas;
        private final int desiredReplicas;

        private ServiceSnapshot(String image, String updateState, int runningReplicas, int desiredReplicas) {
            this.image = image;
            this.updateState = updateState;
            this.runningReplicas = runningReplicas;
            this.desiredReplicas = desiredReplicas;
        }
    }

    private static final class ReplicaCount {
        private final int running;
        private final int desired;

        private ReplicaCount(int running, int desired) {
            this.running = running;
            this.desired = desired;
        }
    }

    private static final class TaskFailureRead {
        private final String lastError;

        private TaskFailureRead(String lastError) {
            this.lastError = lastError;
        }

        private static TaskFailureRead empty() {
            return new TaskFailureRead(null);
        }
    }
}
