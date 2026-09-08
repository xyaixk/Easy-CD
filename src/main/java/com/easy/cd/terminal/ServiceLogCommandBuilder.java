package com.easy.cd.terminal;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 构造只读 Docker Swarm 日志命令。
 */
class ServiceLogCommandBuilder {

    private static final Pattern SERVICE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,64}$");
    private static final int DEFAULT_TAIL = 500;
    private static final int MAX_TAIL = 1000;

    String buildAggregate(String serviceName, List<String> runningTaskIds, int tail, boolean follow) {
        validateServiceName(serviceName);
        if (follow) {
            return buildLogsCommand(serviceName, 0, true);
        }
        if (runningTaskIds == null || runningTaskIds.isEmpty()) {
            throw new IllegalArgumentException("当前没有运行中的实例");
        }
        for (String taskId : runningTaskIds) {
            validateTaskId(taskId);
        }

        int normalizedTail = normalizeTail(tail);
        // 每个 task 的日志独立落盘，避免并发写入互相穿插；RFC3339Nano 时间戳可直接按 C locale 排序。
        StringBuilder command = new StringBuilder(
                "tmpdir=$(mktemp -d) || exit 1; pids=\"\"; status=0; ")
                .append("trap 'kill $pids 2>/dev/null; rm -rf \"$tmpdir\"' HUP INT TERM EXIT; ");
        int fileIndex = 0;
        for (String taskId : runningTaskIds) {
            command.append('(')
                    .append(buildLogsCommand(taskId, normalizedTail, false))
                    .append(") >\"$tmpdir/")
                    .append(fileIndex++)
                    .append(".log\" 2>&1 & pids=\"$pids $!\"; ");
        }
        command.append("for pid in $pids; do wait \"$pid\" || status=$?; done; ")
                .append("LC_ALL=C sort -k1,1 \"$tmpdir\"/*.log || status=$?; ")
                .append("trap - HUP INT TERM EXIT; rm -rf \"$tmpdir\"; exit $status");
        return command.toString();
    }

    String buildTask(String taskId, int tail, boolean follow) {
        validateTaskId(taskId);
        return buildLogsCommand(taskId, follow ? 0 : normalizeTail(tail), follow);
    }

    private String buildLogsCommand(String target, int tail, boolean follow) {
        return "docker service logs " + target
                + " --tail " + tail
                + " --timestamps --no-trunc"
                + (follow ? " --follow" : "")
                + " 2>&1";
    }

    private int normalizeTail(int tail) {
        if (tail <= 0) return DEFAULT_TAIL;
        return Math.min(tail, MAX_TAIL);
    }

    private void validateServiceName(String serviceName) {
        if (serviceName == null || !SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("服务名不合法");
        }
    }

    private void validateTaskId(String taskId) {
        if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("Task ID 不合法");
        }
    }
}
