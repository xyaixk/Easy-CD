package com.easy.cd.util;

import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 命令执行工具类
 * 封装 JSch，支持密码/密钥认证、连接池复用、多 Manager 故障转移
 */
@Slf4j
@Component
public class SshExecutor {

    /** 连接池：key = "user@host:port"，复用 Session */
    private final ConcurrentHashMap<String, Session> sessionPool = new ConcurrentHashMap<>();

    /** 默认命令执行超时（毫秒） */
    private static final int DEFAULT_TIMEOUT = 30_000;

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT = 10_000;

    /**
     * SSH 连接配置
     */
    public static class SshHost {
        private String host;
        private int port = 22;
        private String username = "root";
        private String password;
        private String privateKey;

        public SshHost() {}

        public SshHost(String host, int port, String username, String password, String privateKey) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.privateKey = privateKey;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }

        /** 兼容旧字段名 */
        public void setPrivateKeyPath(String privateKeyPath) { this.privateKey = privateKeyPath; }

        public String getKey() {
            return username + "@" + host + ":" + port;
        }
    }

    /**
     * 命令执行结果
     */
    public static class SshResult {
        private int exitCode;
        private String stdout;
        private String stderr;

        public SshResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }

        public boolean isSuccess() { return exitCode == 0; }

        /** stdout 为空或全是空白时为 true */
        public boolean hasOutput() { return stdout != null && !stdout.trim().isEmpty(); }

        @Override
        public String toString() {
            return "SshResult{exitCode=" + exitCode + ", stdout length=" +
                    (stdout != null ? stdout.length() : 0) + ", stderr length=" +
                    (stderr != null ? stderr.length() : 0) + "}";
        }
    }

    /**
     * 统一命令日志入口：
     * - 高频只读轮询（状态/指标/副本刷新、存在性检查、同步任务）走 debug，默认不刷屏
     * - 其他（部署、创建、更新、伸缩、回滚、日志拉取、镜像 pull、网络操作）走 info，便于方便自查
     */
    private void logCommand(SshHost host, String command) {
        if (command == null) return;
        String hostKey = host != null ? host.getKey() : "?";
        if (isPollingCommand(command)) {
            log.debug("SSH> [{}] {}", hostKey, command);
        } else {
            log.info("SSH> [{}] {}", hostKey, command);
        }
    }

    private boolean isPollingCommand(String command) {
        // 只读、高频、结果化拉取的命令，不属于“用户操作”
        return command.contains("inspect ") && command.contains("--format")
                || command.startsWith("docker service ps ")
                || command.startsWith("docker service ls ")
                || command.startsWith("docker node ls ")
                || command.startsWith("docker stats ")
                || command.startsWith("docker container stats ")
                || command.startsWith("docker network inspect ")
                // 监控采集脚本（宿主机 /proc 采样、副本 docker stats 段标记）
                || command.contains("/proc/stat")
                || command.contains("/proc/meminfo")
                || command.contains("/proc/loadavg")
                || command.contains("===STAT===")
                || command.contains("===REPLICA_STATS===");
    }

    /**
     * 在单台主机上执行命令
     */
    public SshResult executeCommand(SshHost host, String command) {
        return executeCommand(host, command, DEFAULT_TIMEOUT);
    }

    /**
     * 在单台主机上执行命令（指定超时）
     */
    public SshResult executeCommand(SshHost host, String command, int timeoutMs) {
        Session session = null;
        ChannelExec channel = null;
        logCommand(host, command);
        try {
            session = getOrCreateSession(host);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

            // 主动读取模式：使用 InputStream 而非 setOutputStream
            // 避免 JSch 后台写入线程导致 channel 不及时关闭的问题
            InputStream in = channel.getInputStream();
            InputStream err = channel.getExtInputStream();

            channel.connect(CONNECT_TIMEOUT);

            byte[] buf = new byte[8192];
            long startTime = System.currentTimeMillis();
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(buf, 0, buf.length);
                    if (i < 0) break;
                    stdoutStream.write(buf, 0, i);
                }
                while (err.available() > 0) {
                    int i = err.read(buf, 0, buf.length);
                    if (i < 0) break;
                    stderrStream.write(buf, 0, i);
                }
                if (channel.isClosed()) {
                    // 确保残留数据读完
                    if (in.available() > 0 || err.available() > 0) continue;
                    break;
                }
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    log.warn("SSH命令执行超时 ({}ms): {}", timeoutMs, command);
                    break;
                }
                Thread.sleep(50);
            }

            int exitCode = channel.getExitStatus();
            String stdout = stdoutStream.toString("UTF-8");
            String stderr = stderrStream.toString("UTF-8");

            if (exitCode != 0) {
                log.debug("SSH命令退出码非零: exitCode={}, cmd={}, stderr={}", exitCode, command, stderr);
            }

            return new SshResult(exitCode, stdout, stderr);

        } catch (Exception e) {
            log.warn("SSH命令执行失败: host={}, cmd={}, error={}", host.getKey(), command, e.getMessage());
            // 连接异常时移除缓存的 Session
            invalidateSession(host);
            return new SshResult(-1, "", e.getMessage());
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * 带故障转移的命令执行（多 Manager 节点）
     * 随机选一个节点执行，失败则尝试下一个
     */
    public SshResult executeCommandWithFailover(List<SshHost> hosts, String command) {
        if (hosts == null || hosts.isEmpty()) {
            return new SshResult(-1, "", "SSH 主机列表为空");
        }

        List<SshHost> shuffled = new ArrayList<>(hosts);
        Collections.shuffle(shuffled);

        Exception lastException = null;
        for (SshHost host : shuffled) {
            try {
                SshResult result = executeCommand(host, command);
                // 命令本身执行失败（如 docker service 不存在）不算连接故障，直接返回
                if (result.getExitCode() >= 0) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("节点 {} 执行命令失败，尝试下一个: {}", host.getKey(), e.getMessage());
                lastException = e;
                invalidateSession(host);
            }
        }

        String errMsg = "所有 SSH 节点执行命令失败，已尝试: " + hosts.size() + " 个节点";
        if (lastException != null) {
            errMsg += "，最后错误: " + lastException.getMessage();
        }
        return new SshResult(-1, "", errMsg);
    }

    /**
     * 流式命令行回调：适用于 docker service logs --follow 等长连接命令
     */
    public interface LineHandler {
        /** 每到一行输出就回调，返回 false 表示中断命令执行 */
        boolean onLine(String line, boolean isStderr);
    }

    /**
     * 流式执行命令，按行回调（不缓存所有输出）
     * @param overallTimeoutMs 总超时（0 表示不限时）
     */
    public void executeCommandStreaming(SshHost host, String command, LineHandler handler, int overallTimeoutMs) throws Exception {
        Session session;
        ChannelExec channel = null;
        logCommand(host, command);
        try {
            session = getOrCreateSession(host);
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            InputStream in = channel.getInputStream();
            InputStream err = channel.getExtInputStream();

            channel.connect(CONNECT_TIMEOUT);

            byte[] buf = new byte[8192];
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            long startTime = System.currentTimeMillis();
            boolean stopped = false;

            while (!stopped) {
                // stdout
                while (in.available() > 0) {
                    int i = in.read(buf, 0, buf.length);
                    if (i < 0) break;
                    stdoutBuf.append(new String(buf, 0, i, "UTF-8"));
                    int nl;
                    while ((nl = stdoutBuf.indexOf("\n")) != -1) {
                        String line = stdoutBuf.substring(0, nl);
                        stdoutBuf.delete(0, nl + 1);
                        if (!handler.onLine(line, false)) { stopped = true; break; }
                    }
                    if (stopped) break;
                }
                if (stopped) break;
                // stderr
                while (err.available() > 0) {
                    int i = err.read(buf, 0, buf.length);
                    if (i < 0) break;
                    stderrBuf.append(new String(buf, 0, i, "UTF-8"));
                    int nl;
                    while ((nl = stderrBuf.indexOf("\n")) != -1) {
                        String line = stderrBuf.substring(0, nl);
                        stderrBuf.delete(0, nl + 1);
                        if (!handler.onLine(line, true)) { stopped = true; break; }
                    }
                    if (stopped) break;
                }
                if (stopped) break;

                if (channel.isClosed()) {
                    // 消耗残留
                    if (stdoutBuf.length() > 0) handler.onLine(stdoutBuf.toString(), false);
                    if (stderrBuf.length() > 0) handler.onLine(stderrBuf.toString(), true);
                    break;
                }
                if (overallTimeoutMs > 0 && System.currentTimeMillis() - startTime > overallTimeoutMs) {
                    log.warn("SSH 流式命令超时 ({}ms): {}", overallTimeoutMs, command);
                    break;
                }
                Thread.sleep(50);
            }
        } catch (Exception e) {
            invalidateSession(host);
            throw e;
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    /**
     * 带故障转移的流式执行（仅连接失败时切换下一个）
     */
    public void executeCommandStreamingWithFailover(List<SshHost> hosts, String command, LineHandler handler, int overallTimeoutMs) throws Exception {
        if (hosts == null || hosts.isEmpty()) {
            throw new RuntimeException("SSH 主机列表为空");
        }
        List<SshHost> shuffled = new ArrayList<>(hosts);
        Collections.shuffle(shuffled);

        Exception lastException = null;
        for (SshHost host : shuffled) {
            try {
                executeCommandStreaming(host, command, handler, overallTimeoutMs);
                return;
            } catch (Exception e) {
                log.warn("节点 {} 流式执行失败，尝试下一个: {}", host.getKey(), e.getMessage());
                lastException = e;
            }
        }
        throw lastException != null ? lastException : new RuntimeException("所有 SSH 节点流式执行失败");
    }

    /**
     * 获取或创建 SSH Session（带连接池复用）
     */
    private Session getOrCreateSession(SshHost host) throws JSchException {
        String key = host.getKey();

        Session session = sessionPool.get(key);
        if (session != null && session.isConnected()) {
            return session;
        }

        // Session 不存在或已断开，创建新的
        synchronized (this) {
            // 双重检查
            session = sessionPool.get(key);
            if (session != null && session.isConnected()) {
                return session;
            }

            JSch jsch = new JSch();

            // 密钥认证（直接传入私钥文本）
            if (host.getPrivateKey() != null && !host.getPrivateKey().isEmpty()) {
                jsch.addIdentity(host.getKey(), host.getPrivateKey().getBytes(), null, null);
            }

            session = jsch.getSession(host.getUsername(), host.getHost(), host.getPort());

            // 密码认证
            if (host.getPassword() != null && !host.getPassword().isEmpty()) {
                session.setPassword(host.getPassword());
            }

            // 跳过 host key 检查（内网环境）
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);

            session.setServerAliveInterval(60_000); // 保活
            session.setServerAliveCountMax(3);
            session.connect(CONNECT_TIMEOUT);

            sessionPool.put(key, session);
            log.info("SSH 连接建立成功: {}", key);

            return session;
        }
    }

    /**
     * 移除失效的 Session
     */
    private void invalidateSession(SshHost host) {
        String key = host.getKey();
        Session session = sessionPool.remove(key);
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        log.debug("SSH Session 已移除: {}", key);
    }

    /**
     * 应用关闭时清理所有连接
     */
    @PreDestroy
    public void destroy() {
        log.info("关闭所有 SSH 连接，共 {} 个", sessionPool.size());
        for (Map.Entry<String, Session> entry : sessionPool.entrySet()) {
            try {
                if (entry.getValue().isConnected()) {
                    entry.getValue().disconnect();
                }
            } catch (Exception e) {
                log.debug("关闭 SSH 连接失败: {}", entry.getKey(), e);
            }
        }
        sessionPool.clear();
    }

    /**
     * 从环境 config JSON 中解析 SSH 主机列表
     * 支持两种格式：
     * 1. 对象数组: [{"host":"x","port":22,"username":"root","password":"xx"}]
     * 2. 简单字符串数组（兼容旧格式）: ["ssh://root:pass@host:22"]
     */
    public static List<SshHost> parseSshHosts(List<Map<String, Object>> hostConfigs) {
        List<SshHost> hosts = new ArrayList<>();
        if (hostConfigs == null) return hosts;

        for (Map<String, Object> cfg : hostConfigs) {
            SshHost h = new SshHost();
            h.setHost((String) cfg.get("host"));
            h.setPort(cfg.get("port") != null ? Integer.parseInt(cfg.get("port").toString()) : 22);
            h.setUsername(cfg.get("username") != null ? (String) cfg.get("username") : "root");
            h.setPassword((String) cfg.get("password"));
            h.setPrivateKeyPath((String) cfg.get("privateKey"));
            hosts.add(h);
        }
        return hosts;
    }

    /**
     * 从环境配置 Map 中解析 SSH 主机列表（兼容结构化和旧版字符串格式）
     */
    @SuppressWarnings("unchecked")
    public static List<SshHost> parseSshHostsFromConfig(Map<String, Object> config) {
        if (config == null) return Collections.emptyList();
        Object hostsObj = config.get("swarmManagerHosts");
        if (hostsObj == null) return Collections.emptyList();
        List<?> hostsList = (List<?>) hostsObj;
        if (hostsList.isEmpty()) return Collections.emptyList();

        Object first = hostsList.get(0);
        if (first instanceof Map) {
            return parseSshHosts((List<Map<String, Object>>) hostsObj);
        } else if (first instanceof String) {
            List<SshHost> sshHosts = new ArrayList<>();
            for (Object item : hostsList) {
                String hostStr = item.toString();
                String host = hostStr.replace("tcp://", "").replace("ssh://", "").replaceAll(":\\d+$", "");
                SshHost sshHost = new SshHost();
                sshHost.setHost(host);
                sshHost.setPort(22);
                sshHost.setUsername("root");
                if (config.containsKey("sshPassword")) sshHost.setPassword((String) config.get("sshPassword"));
                if (config.containsKey("sshPrivateKey")) sshHost.setPrivateKeyPath((String) config.get("sshPrivateKey"));
                sshHosts.add(sshHost);
            }
            return sshHosts;
        }
        return Collections.emptyList();
    }
}
