package com.easy.cd.deploy.queue;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.easy.cd.auth.AuthContext;
import com.easy.cd.auth.LoginUser;
import com.easy.cd.entity.DeployTask;
import com.easy.cd.mapper.DeployTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 部署任务队列服务
 * 每个环境一个单线程队列：同环境操作严格串行，不同环境并行执行。
 * 任务记录持久化到 deploy_task 表，执行期间的 SSH 命令与输出通过
 * TaskLogContext 增量写入 command_log 字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployTaskQueueService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private final DeployTaskMapper deployTaskMapper;

    /** 每环境一个单线程执行器，懒创建 */
    private final ConcurrentHashMap<Long, ExecutorService> executors = new ConcurrentHashMap<>();

    /**
     * 提交任务：插入 PENDING 记录 → 入队 → 返回 taskId
     */
    public Long submit(Long environmentId, Long serviceId, String serviceName, String taskType, Runnable action) {
        DeployTask task = new DeployTask();
        task.setEnvironmentId(environmentId);
        task.setServiceId(serviceId);
        task.setServiceName(serviceName);
        task.setTaskType(taskType);
        task.setStatus(STATUS_PENDING);
        task.setCreatedTime(LocalDateTime.now());
        LoginUser user = AuthContext.getCurrentUser();
        task.setSubmittedBy(user != null ? user.getUsername() : "anonymous");
        task.setSubmittedIp(resolveClientIp());
        deployTaskMapper.insert(task);

        Long taskId = task.getId();
        executorFor(environmentId).submit(() -> runTask(taskId, action));
        log.info("部署任务已入队: taskId={}, type={}, service={}, env={}", taskId, taskType, serviceName, environmentId);
        return taskId;
    }

    /**
     * 从当前 HTTP 请求解析提交人 IP（优先代理头），非请求线程返回 null
     */
    private String resolveClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // 多级代理时取第一个（客户端真实 IP）
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private ExecutorService executorFor(Long environmentId) {
        return executors.computeIfAbsent(environmentId, id ->
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "deploy-task-env-" + id);
                    t.setDaemon(true);
                    return t;
                }));
    }

    /**
     * worker 线程内执行任务：更新状态 → 绑定日志上下文 → 执行业务闭包 → 落终态
     */
    private void runTask(Long taskId, Runnable action) {
        DeployTask task = deployTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务记录不存在，跳过执行: taskId={}", taskId);
            return;
        }

        task.setStatus(STATUS_RUNNING);
        task.setStartedTime(LocalDateTime.now());
        deployTaskMapper.updateById(task);

        // 每条 SSH 命令完成后增量刷 command_log，前端轮询可实时看到输出
        TaskLogContext context = new TaskLogContext(logContent -> {
            DeployTask patch = new DeployTask();
            patch.setId(taskId);
            patch.setCommandLog(logContent);
            deployTaskMapper.updateById(patch);
        });
        TaskLogContext.bind(context);
        try {
            action.run();
            task.setStatus(STATUS_SUCCESS);
            log.info("部署任务执行成功: taskId={}, type={}, service={}", taskId, task.getTaskType(), task.getServiceName());
        } catch (Exception e) {
            task.setStatus(STATUS_FAILED);
            task.setErrorMsg(abbreviate(e.getMessage()));
            TaskLogContext.append("[任务失败] " + e.getMessage());
            log.error("部署任务执行失败: taskId={}, type={}, service={}", taskId, task.getTaskType(), task.getServiceName(), e);
        } finally {
            TaskLogContext.unbind();
            task.setFinishedTime(LocalDateTime.now());
            task.setCommandLog(context.content());
            deployTaskMapper.updateById(task);
        }
    }

    private String abbreviate(String message) {
        if (message == null) return "未知错误";
        return message.length() > 900 ? message.substring(0, 900) + "..." : message;
    }

    /**
     * 启动恢复：后端重启后，将遗留的 PENDING/RUNNING 任务标记为失败
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleTasks() {
        LambdaUpdateWrapper<DeployTask> wrapper = new LambdaUpdateWrapper<DeployTask>()
                .in(DeployTask::getStatus, Arrays.asList(STATUS_PENDING, STATUS_RUNNING))
                .set(DeployTask::getStatus, STATUS_FAILED)
                .set(DeployTask::getErrorMsg, "后端重启中断")
                .set(DeployTask::getFinishedTime, LocalDateTime.now());
        int updated = deployTaskMapper.update(null, wrapper);
        if (updated > 0) {
            log.warn("启动恢复：{} 个遗留任务已标记为失败", updated);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭部署任务队列，共 {} 个环境执行器", executors.size());
        for (Map.Entry<Long, ExecutorService> entry : executors.entrySet()) {
            entry.getValue().shutdown();
            try {
                if (!entry.getValue().awaitTermination(5, TimeUnit.SECONDS)) {
                    entry.getValue().shutdownNow();
                }
            } catch (InterruptedException e) {
                entry.getValue().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        executors.clear();
    }
}
