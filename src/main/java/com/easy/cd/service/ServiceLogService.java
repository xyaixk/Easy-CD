package com.easy.cd.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.easy.cd.dto.ServiceLogInstanceDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.util.SshExecutor;
import com.easy.cd.util.SshExecutor.SshHost;
import com.easy.cd.util.SshExecutor.SshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 查询 Docker Swarm 中仍保留的服务 task，供日志实例选择和权限校验使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceLogService {

    private static final Pattern SERVICE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9]{1,64}$");
    private static final int MAX_ERROR_LENGTH = 500;

    private final ServiceMapper serviceMapper;
    private final EnvironmentMapper environmentMapper;
    private final SshExecutor sshExecutor;

    public List<ServiceLogInstanceDTO> listInstances(Long serviceId) {
        AppService appService = serviceMapper.selectById(serviceId);
        if (appService == null) {
            throw new BusinessException("服务不存在");
        }

        Environment environment = environmentMapper.selectById(appService.getEnvironmentId());
        if (environment == null) {
            throw new BusinessException("环境不存在");
        }
        if (!"docker".equalsIgnoreCase(environment.getDeployType())) {
            throw new BusinessException("当前部署类型不支持 Docker Swarm 实例日志");
        }

        return listInstances(appService, environment);
    }

    public List<ServiceLogInstanceDTO> listInstances(AppService appService, Environment environment) {
        String serviceName = resolveServiceName(appService);
        List<SshHost> hosts = parseHosts(environment);

        String psCommand = "docker service ps " + serviceName
                + " --no-trunc --format '{{json .}}'";
        SshResult psResult = sshExecutor.executeCommandWithFailover(hosts, psCommand);
        if (!psResult.isSuccess()) {
            throw new BusinessException("查询服务日志实例失败: " + resultError(psResult));
        }
        if (!psResult.hasOutput()) {
            return Collections.emptyList();
        }

        Map<String, TaskDisplayInfo> displayInfoById = parseTaskDisplayInfo(psResult.getStdout());
        if (displayInfoById.isEmpty()) {
            return Collections.emptyList();
        }

        String inspectCommand = "docker inspect --type task --format '{{json .}}' "
                + String.join(" ", displayInfoById.keySet());
        SshResult inspectResult = sshExecutor.executeCommandWithFailover(hosts, inspectCommand);
        if (!inspectResult.isSuccess()) {
            if (!isPrunedTaskInspectResult(inspectResult)) {
                throw new BusinessException("读取服务日志实例状态失败: " + resultError(inspectResult));
            }
            log.debug("部分 Docker task 在状态读取期间已被清理: {}", resultError(inspectResult));
        }
        if (!inspectResult.hasOutput()) {
            throw new BusinessException("读取服务日志实例状态失败: Docker 未返回 task 状态");
        }

        List<ServiceLogInstanceDTO> instances = parseTaskDetails(
                inspectResult.getStdout(), displayInfoById, serviceName);
        if (instances.isEmpty()) {
            throw new BusinessException("读取服务日志实例状态失败: Docker task 状态无法解析");
        }
        instances.sort(this::compareInstances);
        return instances;
    }

    public String resolveServiceName(AppService appService) {
        String externalName = appService.getExternalServiceName();
        String serviceName = externalName != null && !externalName.trim().isEmpty()
                ? externalName.trim()
                : appService.getName().toLowerCase();
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            throw new BusinessException("服务名不合法");
        }
        return serviceName;
    }

    private List<SshHost> parseHosts(Environment environment) {
        try {
            Map<String, Object> config = JSON.parseObject(
                    environment.getConfig(), new TypeReference<Map<String, Object>>() {});
            List<SshHost> hosts = SshExecutor.parseSshHostsFromConfig(config);
            if (hosts.isEmpty()) {
                throw new BusinessException("环境未配置 SSH 地址");
            }
            return hosts;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("环境 SSH 配置无效", e);
        }
    }

    private Map<String, TaskDisplayInfo> parseTaskDisplayInfo(String output) {
        Map<String, TaskDisplayInfo> result = new LinkedHashMap<>();
        for (String line : output.split("\\r?\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JSONObject json = JSON.parseObject(line.trim());
                String taskId = json.getString("ID");
                validateTaskId(taskId);
                TaskDisplayInfo info = new TaskDisplayInfo();
                info.name = stripHistoryPrefix(json.getString("Name"));
                info.node = json.getString("Node");
                result.put(taskId, info);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException("解析 Docker task 列表失败", e);
            }
        }
        return result;
    }

    private List<ServiceLogInstanceDTO> parseTaskDetails(
            String output, Map<String, TaskDisplayInfo> displayInfoById, String serviceName) {
        List<ServiceLogInstanceDTO> result = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JSONObject task = JSON.parseObject(line.trim());
                String taskId = task.getString("ID");
                TaskDisplayInfo displayInfo = displayInfoById.get(taskId);
                if (displayInfo == null) {
                    log.warn("忽略不属于当前服务的 task 状态: {}", taskId);
                    continue;
                }

                JSONObject status = task.getJSONObject("Status");
                String state = status != null ? status.getString("State") : null;
                String desiredState = task.getString("DesiredState");
                String timestamp = normalizeTimestamp(status != null ? status.getString("Timestamp") : null);
                Integer slot = task.getInteger("Slot");
                String name = displayInfo.name;
                if (name == null || name.trim().isEmpty()) {
                    name = slot != null && slot > 0
                            ? serviceName + "." + slot
                            : serviceName + "." + shortTaskId(taskId);
                }

                result.add(ServiceLogInstanceDTO.builder()
                        .taskId(taskId)
                        .name(name)
                        .slot(slot)
                        .node(displayInfo.node)
                        .state(state)
                        .desiredState(desiredState)
                        .statusTimestamp(timestamp)
                        .errorMessage(status != null ? emptyToNull(status.getString("Err")) : null)
                        .running("running".equalsIgnoreCase(state)
                                && "running".equalsIgnoreCase(desiredState))
                        .build());
            } catch (Exception e) {
                log.warn("忽略无法解析的 Docker task 状态行: {}", summarize(line), e);
            }
        }
        return result;
    }

    private int compareInstances(ServiceLogInstanceDTO left, ServiceLogInstanceDTO right) {
        if (left.isRunning() != right.isRunning()) {
            return left.isRunning() ? -1 : 1;
        }

        Instant leftTime = parseInstant(left.getStatusTimestamp());
        Instant rightTime = parseInstant(right.getStatusTimestamp());
        if (leftTime != null || rightTime != null) {
            if (leftTime == null) return 1;
            if (rightTime == null) return -1;
            int timeComparison = rightTime.compareTo(leftTime);
            if (timeComparison != 0) return timeComparison;
        }
        return left.getTaskId().compareTo(right.getTaskId());
    }

    private String normalizeTimestamp(String value) {
        Instant instant = parseInstant(value);
        return instant != null ? instant.toString() : null;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void validateTaskId(String taskId) {
        if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new BusinessException("Docker 返回了非法 Task ID");
        }
    }

    private String stripHistoryPrefix(String name) {
        return name == null ? null : name.replaceFirst("^_\\s*", "");
    }

    private String shortTaskId(String taskId) {
        return taskId.length() > 12 ? taskId.substring(0, 12) : taskId;
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private String resultError(SshResult result) {
        String error = result.getStderr();
        if (error == null || error.trim().isEmpty()) {
            error = result.getStdout();
        }
        return summarize(error == null || error.trim().isEmpty() ? "未知错误" : error);
    }

    private boolean isPrunedTaskInspectResult(SshResult result) {
        if (!result.hasOutput() || result.getStderr() == null || result.getStderr().trim().isEmpty()) {
            return false;
        }
        for (String line : result.getStderr().split("\\r?\\n")) {
            if (!line.trim().isEmpty() && !line.contains("No such object")) {
                return false;
            }
        }
        return true;
    }

    private String summarize(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    private static class TaskDisplayInfo {
        private String name;
        private String node;
    }
}
