package com.easy.cd.terminal;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.auth.SessionManager;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.entity.ReplicaStatus;
import com.easy.cd.dto.ServiceLogInstanceDTO;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ReplicaStatusMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.service.ServiceLogService;
import com.easy.cd.util.SshExecutor.SshHost;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.easy.cd.util.SshExecutor.parseSshHostsFromConfig;

/**
 * Web 终端 WebSocket 处理器：桥接浏览器 xterm.js 与远端命令输出
 *
 * 两种模式（mode 参数区分）：
 *   shell（默认）：浏览器 xterm.js <-> WebSocket <-> JSch(PTY) <-> 节点 SSH <-> docker exec -it
 *   logs：只读日志流，同一条链路执行 docker service logs（复用连接/输出泵/清理逻辑）
 *
 * 客户端协议（文本帧，首字符区分类型）：
 *   '0' + 数据      终端键盘输入（logs 模式忽略）
 *   '1' + JSON      窗口大小 {"cols":120,"rows":30}
 * 服务端 -> 客户端：二进制帧（原始输出字节流，前端按 UTF-8 渲染）
 *
 * 连接参数：
 *   shell：/terminal?serviceId=&replicaId=&token=
 *   logs： /terminal?mode=logs&serviceId=&taskId=&tail=&follow=&token=
 * 安全：环境 needLogin 时校验 token；容器 ID 从 replica_status 表反查（不信任前端）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private final ServiceMapper serviceMapper;
    private final EnvironmentMapper environmentMapper;
    private final ReplicaStatusMapper replicaStatusMapper;
    private final SessionManager sessionManager;
    private final ServiceLogService serviceLogService;
    private final TerminalTargetHostResolver targetHostResolver;

    private final ServiceLogCommandBuilder serviceLogCommandBuilder = new ServiceLogCommandBuilder();

    /** 容器 ID 合法性校验（防命令注入） */
    private static final Pattern CONTAINER_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{6,64}$");

    private static final int CONNECT_TIMEOUT = 10_000;

    /** 每个 WebSocket 连接对应一条独立 SSH 通道 */
    private final Map<String, ShellContext> contexts = new ConcurrentHashMap<>();

    private static class ShellContext {
        WebSocketSession wsSession;   // 装饰后的会话（线程安全发送）
        Session sshSession;
        ChannelExec channel;
        OutputStream stdin;
        Thread pumpThread;
        boolean readOnly;             // logs 模式：忽略键盘输入
        volatile boolean closed;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        // 并发发送保护：输出泵线程与错误提示可能同时写
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, 10_000, 1024 * 1024);
        Map<String, String> params = parseQuery(rawSession.getUri() != null ? rawSession.getUri().getQuery() : null);

        try {
            Long serviceId = Long.parseLong(params.get("serviceId"));
            String token = params.get("token");

            AppService service = serviceMapper.selectById(serviceId);
            if (service == null) { closeWith(session, "服务不存在"); return; }
            Environment environment = environmentMapper.selectById(service.getEnvironmentId());
            if (environment == null) { closeWith(session, "环境不存在"); return; }

            // 环境需要登录时校验 token（WebSocket 不经过 MVC 拦截器）
            if (Boolean.TRUE.equals(environment.getNeedLogin())
                    && (token == null || sessionManager.getByToken(token) == null)) {
                closeWith(session, "未登录或登录已过期");
                return;
            }

            // logs 模式：只读服务聚合日志
            if ("logs".equals(params.get("mode"))) {
                openLogs(session, environment, service, params);
                return;
            }

            // shell 模式：副本信息从库里反查，拿到目标节点和容器 ID
            String replicaId = params.get("replicaId");
            ReplicaStatus replica = replicaStatusMapper.selectOne(
                    new LambdaQueryWrapper<ReplicaStatus>()
                            .eq(ReplicaStatus::getServiceId, serviceId)
                            .eq(ReplicaStatus::getReplicaId, replicaId)
                            .orderByDesc(ReplicaStatus::getId)
                            .last("LIMIT 1"));
            if (replica == null) { closeWith(session, "副本不存在或已被回收"); return; }

            String containerId = replica.getContainerId() != null && !replica.getContainerId().isEmpty()
                    ? replica.getContainerId() : replica.getContainerIdShort();
            if (containerId == null || !CONTAINER_ID_PATTERN.matcher(containerId).matches()) {
                closeWith(session, "容器 ID 缺失，副本可能还在启动中");
                return;
            }

            SshHost target = targetHostResolver.resolve(
                    environment, replica.getNodeName(), replica.getNodeIp());
            if (target == null) {
                closeWith(session, "未找到副本所在节点的 SSH 地址: " + replica.getNodeName());
                return;
            }

            String cmd = "docker exec -it " + containerId
                    + " /bin/sh -c '[ -x /bin/bash ] && exec /bin/bash || exec /bin/sh'";
            openCommandChannel(session, target, cmd,
                    "shell:" + replica.getReplicaName(), "\r\n\u001b[90m[会话已结束]\u001b[0m\r\n", null);
        } catch (NumberFormatException e) {
            closeWith(session, "参数错误");
        } catch (Exception e) {
            log.warn("终端建立失败: {}", e.getMessage());
            closeWith(session, "终端建立失败: " + e.getMessage());
        }
    }

    /**
     * logs 模式：在 manager 节点执行 docker service logs，输出泵回前端 xterm
     * 多 manager 时按顺序尝试（连接级 failover）
     */
    private void openLogs(WebSocketSession session, Environment environment, AppService service,
                          Map<String, String> params) {
        String serviceName = serviceLogService.resolveServiceName(service);

        Map<String, Object> config = JSON.parseObject(
                environment.getConfig(), new TypeReference<Map<String, Object>>() {});
        List<SshHost> hosts = parseSshHostsFromConfig(config);
        if (hosts.isEmpty()) { closeWith(session, "环境未配置 SSH 地址"); return; }

        int tail = 500;
        try { tail = Integer.parseInt(params.getOrDefault("tail", "500")); } catch (NumberFormatException ignored) {}
        int effectiveTail = tail > 0 ? Math.min(tail, 1000) : 500;
        boolean follow = Boolean.parseBoolean(params.getOrDefault("follow", "false"));
        String taskId = trimToNull(params.get("taskId"));

        String cmd;
        String targetLabel;
        try {
            if (taskId != null) {
                List<ServiceLogInstanceDTO> instances = serviceLogService.listInstances(service, environment);
                ServiceLogInstanceDTO selected = instances.stream()
                        .filter(instance -> taskId.equals(instance.getTaskId()))
                        .findFirst()
                        .orElse(null);
                if (selected == null) {
                    closeWith(session, "实例已被 Swarm 清理或不属于当前服务，请刷新实例列表");
                    return;
                }
                if (follow && !selected.isRunning()) {
                    closeWith(session, "历史实例不支持实时推送");
                    return;
                }
                cmd = serviceLogCommandBuilder.buildTask(taskId, effectiveTail, follow);
                targetLabel = selected.getName() + "." + shortId(taskId);
            } else if (follow) {
                // 实时聚合不读取历史积压；service selector 能继续接收后续替换 task 的新输出。
                cmd = serviceLogCommandBuilder.buildAggregate(
                        serviceName, java.util.Collections.emptyList(), effectiveTail, true);
                targetLabel = serviceName;
            } else {
                List<String> runningTaskIds = serviceLogService.listInstances(service, environment).stream()
                        .filter(ServiceLogInstanceDTO::isRunning)
                        .map(ServiceLogInstanceDTO::getTaskId)
                        .collect(Collectors.toList());
                if (runningTaskIds.isEmpty()) {
                    closeNormallyWith(session, "当前没有运行中的实例，可从下拉框选择历史实例查看日志");
                    return;
                }
                cmd = serviceLogCommandBuilder.buildAggregate(
                        serviceName, runningTaskIds, effectiveTail, false);
                targetLabel = serviceName;
            }
        } catch (Exception e) {
            log.warn("构造服务日志命令失败, service={}: {}", serviceName, e.getMessage());
            closeWith(session, e.getMessage() != null ? e.getMessage() : "查询服务日志实例失败");
            return;
        }

        // 静态模式读完即止，静默关闭；follow 模式断开时给提示
        String endMessage = follow ? "\r\n\u001b[90m[日志流已断开]\u001b[0m\r\n" : null;
        // 零输出时给出明确提示，避免前端空白无从判断
        String emptyHint = "\u001b[90m[" + targetLabel + (follow ? " 实时期间" : " 最近 " + effectiveTail + " 行内")
                + "没有日志输出]\u001b[0m\r\n";

        Exception lastError = null;
        for (SshHost host : hosts) {
            try {
                ShellContext ctx = openCommandChannel(session, host, cmd, "logs:" + targetLabel,
                        endMessage, emptyHint);
                ctx.readOnly = true;
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("日志通道建立失败, host={}: {}", host.getKey(), e.getMessage());
            }
        }
        closeWith(session, "日志通道建立失败: " + (lastError != null ? lastError.getMessage() : "未知错误"));
    }

    /**
     * 打开带 PTY 的 SSH 通道执行命令，并启动输出泵线程（shell / logs 模式共用）
     * PTY 的附带好处：连接断开时远端进程收到 SIGHUP 自动退出，不留孤儿 docker logs --follow
     *
     * @param endMessage 命令结束时发给前端的提示，null 表示静默关闭
     * @param emptyHint  命令全程零输出时发给前端的提示，null 表示不提示
     */
    private ShellContext openCommandChannel(WebSocketSession session, SshHost host, String command,
                                    String tag, String endMessage, String emptyHint) throws Exception {
        JSch jsch = new JSch();
        if (host.getPrivateKey() != null && !host.getPrivateKey().isEmpty()) {
            jsch.addIdentity(host.getKey(), host.getPrivateKey().getBytes(StandardCharsets.UTF_8), null, null);
        }
        Session ssh = jsch.getSession(host.getUsername(), host.getHost(), host.getPort());
        if (host.getPassword() != null && !host.getPassword().isEmpty()) {
            ssh.setPassword(host.getPassword());
        }
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        ssh.setConfig(config);
        ssh.setServerAliveInterval(30_000);
        ssh.connect(CONNECT_TIMEOUT);

        ChannelExec channel = (ChannelExec) ssh.openChannel("exec");
        channel.setCommand(command);
        channel.setPty(true);
        channel.setPtyType("xterm-256color");

        InputStream out = channel.getInputStream();
        OutputStream stdin = channel.getOutputStream();
        channel.connect(CONNECT_TIMEOUT);

        ShellContext ctx = new ShellContext();
        ctx.wsSession = session;
        ctx.sshSession = ssh;
        ctx.channel = channel;
        ctx.stdin = stdin;
        contexts.put(session.getId(), ctx);

        log.info("通道已打开: tag={}, node={}, cmd={}", tag, host.getKey(), command);

        // 输出泵：命令输出 -> WebSocket 二进制帧
        ctx.pumpThread = new Thread(() -> {
            byte[] buf = new byte[8192];
            long total = 0;
            try {
                int n;
                while (!ctx.closed && (n = out.read(buf)) != -1) {
                    total += n;
                    session.sendMessage(new BinaryMessage(java.util.Arrays.copyOf(buf, n)));
                }
            } catch (Exception e) {
                if (!ctx.closed) log.debug("输出泵结束: tag={}, {}", tag, e.getMessage());
            } finally {
                // 零输出多半是命令本身失败或没有匹配数据，记下退出码便于排查
                if (total == 0 && !ctx.closed) {
                    log.info("命令无输出: tag={}, exitStatus={}", tag, channel.getExitStatus());
                }
                // 命令退出时主动关闭 WebSocket
                try {
                    if (session.isOpen()) {
                        if (total == 0 && !ctx.closed && emptyHint != null) {
                            session.sendMessage(new TextMessage(emptyHint));
                        }
                        if (endMessage != null) {
                            session.sendMessage(new TextMessage(endMessage));
                        }
                        session.close(CloseStatus.NORMAL);
                    }
                } catch (Exception ignored) {}
            }
        }, "terminal-pump-" + session.getId());
        ctx.pumpThread.setDaemon(true);
        ctx.pumpThread.start();
        return ctx;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ShellContext ctx = contexts.get(session.getId());
        if (ctx == null || ctx.closed) return;

        String payload = message.getPayload();
        if (payload.isEmpty()) return;
        char type = payload.charAt(0);
        String data = payload.substring(1);

        if (type == '0') {
            // 键盘输入（logs 模式忽略）
            if (ctx.readOnly) return;
            ctx.stdin.write(data.getBytes(StandardCharsets.UTF_8));
            ctx.stdin.flush();
        } else if (type == '1') {
            // 窗口大小变化
            try {
                JSONObject size = JSON.parseObject(data);
                int cols = size.getIntValue("cols");
                int rows = size.getIntValue("rows");
                if (cols > 0 && rows > 0) {
                    ctx.channel.setPtySize(cols, rows, cols * 8, rows * 16);
                }
            } catch (Exception e) {
                log.debug("终端 resize 解析失败: {}", data);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("终端传输异常: {}", exception.getMessage());
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        ShellContext ctx = contexts.remove(sessionId);
        if (ctx == null) return;
        ctx.closed = true;
        try { if (ctx.channel != null && ctx.channel.isConnected()) ctx.channel.disconnect(); } catch (Exception ignored) {}
        try { if (ctx.sshSession != null && ctx.sshSession.isConnected()) ctx.sshSession.disconnect(); } catch (Exception ignored) {}
        if (ctx.pumpThread != null) ctx.pumpThread.interrupt();
        log.info("终端已关闭: session={}", sessionId);
    }

    /** 发送错误提示后关闭连接 */
    private void closeWith(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage("\u001b[31m" + message + "\u001b[0m\r\n"));
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {}
    }

    /** 发送普通状态提示后正常关闭连接 */
    private void closeNormallyWith(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage("\u001b[90m" + message + "\u001b[0m\r\n"));
            session.close(CloseStatus.NORMAL);
        } catch (Exception ignored) {}
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private String shortId(String value) {
        return value.length() > 12 ? value.substring(0, 12) : value;
    }

    private Map<String, String> parseQuery(String query) throws Exception {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        return params;
    }

    @PreDestroy
    public void destroy() {
        for (String id : contexts.keySet()) {
            cleanup(id);
        }
    }
}
