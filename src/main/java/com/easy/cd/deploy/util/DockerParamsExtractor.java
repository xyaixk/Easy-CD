package com.easy.cd.deploy.util;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.*;

/**
 * 从 docker service inspect JSON 反向提取运行参数 (dockerParams) 的工具类。
 * 字段命名与 DockerDeployStrategy 部署命令拼装保持一致，保证 diff 更新可对齐。
 * 无状态、纯静态。
 */
public class DockerParamsExtractor {

    /** Docker 内置参数 key，用于反向提取时区分环境变量 */
    public static final Set<String> BUILT_IN_KEYS = new HashSet<>(Arrays.asList(
            "replicas", "cpus", "memory", "memory-reservation", "restart",
            "restart-max-attempts", "restart-delay", "publish", "network",
            "healthcheck", "healthcheck_interval", "healthcheck_timeout",
            "healthcheck_retries", "healthcheck_start_period", "update_parallelism",
            "update_delay", "update_monitor", "update_failure_action", "update_order",
            "mounts", "log-driver", "log-opts", "container-labels", "container-label",
            "command"
    ));

    private DockerParamsExtractor() {}

    /**
     * 从 docker service inspect 结果反向构建 dockerParams map
     */
    public static Map<String, Object> extractFromInspect(JSONObject inspectJson) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (inspectJson == null) return params;

        try {
            JSONObject spec = inspectJson.getJSONObject("Spec");
            if (spec == null) return params;

            JSONObject taskTemplate = spec.getJSONObject("TaskTemplate");
            if (taskTemplate == null) return params;

            JSONObject containerSpec = taskTemplate.getJSONObject("ContainerSpec");

            extractResources(taskTemplate, params);
            extractRestartPolicy(taskTemplate, params);
            extractPorts(spec, params);
            extractHealthcheck(containerSpec, params);
            extractUpdateConfig(spec, params);
            extractMounts(containerSpec, params);
            extractLogDriver(taskTemplate, params);
            extractContainerLabels(containerSpec, params);
            extractNetworks(taskTemplate, params);
            extractEnvVars(containerSpec, params);
            extractCommand(containerSpec, params);
        } catch (Exception ignored) {
        }
        return params;
    }

    private static void extractResources(JSONObject taskTemplate, Map<String, Object> params) {
        JSONObject resources = taskTemplate.getJSONObject("Resources");
        if (resources == null) return;

        JSONObject limits = resources.getJSONObject("Limits");
        if (limits != null) {
            Long nanoCpus = limits.getLong("NanoCPUs");
            if (nanoCpus != null && nanoCpus > 0) {
                params.put("cpus", String.format("%.2f", nanoCpus / 1_000_000_000.0));
            }
            Long memBytes = limits.getLong("MemoryBytes");
            if (memBytes != null && memBytes > 0) {
                params.put("memory", formatMemory(memBytes));
            }
        }
        JSONObject reservations = resources.getJSONObject("Reservations");
        if (reservations != null) {
            Long memReserv = reservations.getLong("MemoryBytes");
            if (memReserv != null && memReserv > 0) {
                params.put("memory-reservation", formatMemory(memReserv));
            }
        }
    }

    private static void extractRestartPolicy(JSONObject taskTemplate, Map<String, Object> params) {
        JSONObject restartPolicy = taskTemplate.getJSONObject("RestartPolicy");
        if (restartPolicy == null) return;

        String condition = restartPolicy.getString("Condition");
        if (condition != null) {
            params.put("restart", condition);
        }
        Long maxAttempts = restartPolicy.getLong("MaxAttempts");
        if (maxAttempts != null && maxAttempts > 0) {
            params.put("restart-max-attempts", maxAttempts);
        }
        Long restartDelay = restartPolicy.getLong("Delay");
        if (restartDelay != null && restartDelay > 0) {
            params.put("restart-delay", nanoToSeconds(restartDelay) + "s");
        }
    }

    private static void extractPorts(JSONObject spec, Map<String, Object> params) {
        JSONObject endpointSpec = spec.getJSONObject("EndpointSpec");
        if (endpointSpec == null) return;

        JSONArray ports = endpointSpec.getJSONArray("Ports");
        if (ports == null || ports.isEmpty()) return;

        List<String> portMappings = new ArrayList<>();
        for (int i = 0; i < ports.size(); i++) {
            JSONObject port = ports.getJSONObject(i);
            Integer published = port.getInteger("PublishedPort");
            Integer target = port.getInteger("TargetPort");
            String protocol = port.getString("Protocol");
            if (published != null && target != null) {
                String mapping = published + ":" + target;
                if (protocol != null && !"tcp".equalsIgnoreCase(protocol)) {
                    mapping += "/" + protocol;
                }
                portMappings.add(mapping);
            }
        }
        if (!portMappings.isEmpty()) {
            params.put("publish", String.join("\n", portMappings));
        }
    }

    private static void extractHealthcheck(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONObject healthcheck = containerSpec.getJSONObject("Healthcheck");
        if (healthcheck == null) return;

        JSONArray test = healthcheck.getJSONArray("Test");
        if (test != null && test.size() > 1) {
            params.put("healthcheck", test.getString(1));
        }
        Long interval = healthcheck.getLong("Interval");
        if (interval != null && interval > 0) {
            params.put("healthcheck_interval", nanoToSeconds(interval) + "s");
        }
        Long timeout = healthcheck.getLong("Timeout");
        if (timeout != null && timeout > 0) {
            params.put("healthcheck_timeout", nanoToSeconds(timeout) + "s");
        }
        Integer retries = healthcheck.getInteger("Retries");
        if (retries != null) {
            params.put("healthcheck_retries", retries);
        }
        Long startPeriod = healthcheck.getLong("StartPeriod");
        if (startPeriod != null && startPeriod > 0) {
            params.put("healthcheck_start_period", nanoToSeconds(startPeriod) + "s");
        }
    }

    private static void extractUpdateConfig(JSONObject spec, Map<String, Object> params) {
        JSONObject updateConfig = spec.getJSONObject("UpdateConfig");
        if (updateConfig == null) return;

        Integer parallelism = updateConfig.getInteger("Parallelism");
        if (parallelism != null) {
            params.put("update_parallelism", parallelism);
        }
        Long delay = updateConfig.getLong("Delay");
        if (delay != null && delay > 0) {
            params.put("update_delay", nanoToSeconds(delay) + "s");
        }
        String failureAction = updateConfig.getString("FailureAction");
        if (failureAction != null) {
            params.put("update_failure_action", failureAction);
        }
        String order = updateConfig.getString("Order");
        if (order != null) {
            params.put("update_order", order);
        }
    }

    private static void extractMounts(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONArray mounts = containerSpec.getJSONArray("Mounts");
        if (mounts == null || mounts.isEmpty()) return;

        List<String> mountList = new ArrayList<>();
        for (int i = 0; i < mounts.size(); i++) {
            JSONObject mount = mounts.getJSONObject(i);
            String source = mount.getString("Source");
            String target = mount.getString("Target");
            Boolean readOnly = mount.getBoolean("ReadOnly");

            if (source != null && target != null) {
                String entry = source + ":" + target;
                if (Boolean.TRUE.equals(readOnly)) entry += ":ro";
                mountList.add(entry);
            }
        }
        if (!mountList.isEmpty()) {
            params.put("mounts", String.join("\n", mountList));
        }
    }

    private static void extractLogDriver(JSONObject taskTemplate, Map<String, Object> params) {
        JSONObject logDriver = taskTemplate.getJSONObject("LogDriver");
        if (logDriver == null) return;

        String name = logDriver.getString("Name");
        if (name != null) {
            params.put("log-driver", name);
        }
        JSONObject options = logDriver.getJSONObject("Options");
        if (options != null && !options.isEmpty()) {
            params.put("log-opts", options.toJSONString());
        }
    }

    private static void extractContainerLabels(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONObject labels = containerSpec.getJSONObject("Labels");
        if (labels == null || labels.isEmpty()) return;

        List<String> labelList = new ArrayList<>();
        for (Map.Entry<String, Object> entry : labels.entrySet()) {
            labelList.add(entry.getKey() + "=" + entry.getValue());
        }
        if (!labelList.isEmpty()) {
            params.put("container-labels", String.join("\n", labelList));
        }
    }

    private static void extractNetworks(JSONObject taskTemplate, Map<String, Object> params) {
        JSONArray networks = taskTemplate.getJSONArray("Networks");
        if (networks == null || networks.isEmpty()) return;

        for (int i = 0; i < networks.size(); i++) {
            JSONObject net = networks.getJSONObject(i);
            String target = net.getString("Target");
            if (target != null && !params.containsKey("network")) {
                params.put("network", target);
            }
        }
    }

    private static void extractEnvVars(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONArray envArr = containerSpec.getJSONArray("Env");
        if (envArr == null) return;

        for (int i = 0; i < envArr.size(); i++) {
            String env = envArr.getString(i);
            int eqIdx = env.indexOf('=');
            if (eqIdx > 0) {
                String key = env.substring(0, eqIdx);
                String val = env.substring(eqIdx + 1);
                if (!BUILT_IN_KEYS.contains(key.toLowerCase()) && !params.containsKey(key)) {
                    params.put(key, val);
                }
            }
        }
    }

    /** 提取镜像后的启动命令参数 (ContainerSpec.Args) */
    private static void extractCommand(JSONObject containerSpec, Map<String, Object> params) {
        if (containerSpec == null) return;
        JSONArray args = containerSpec.getJSONArray("Args");
        if (args == null || args.isEmpty()) return;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.getString(i);
            if (a != null) tokens.add(a);
        }
        if (!tokens.isEmpty()) {
            params.put("command", String.join(" ", tokens));
        }
    }

    private static String formatMemory(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            double gb = bytes / (1024.0 * 1024 * 1024);
            return gb == (long) gb ? (long) gb + "G" : String.format("%.1fG", gb);
        } else if (bytes >= 1024L * 1024) {
            double mb = bytes / (1024.0 * 1024);
            return mb == (long) mb ? (long) mb + "M" : String.format("%.1fM", mb);
        }
        return bytes + "B";
    }

    private static long nanoToSeconds(long nanos) {
        return nanos / 1_000_000_000L;
    }
}
