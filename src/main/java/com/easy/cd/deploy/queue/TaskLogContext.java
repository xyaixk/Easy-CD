package com.easy.cd.deploy.queue;

import java.util.function.Consumer;

/**
 * 任务日志上下文（ThreadLocal）
 * 队列 worker 线程执行任务前绑定，SshExecutor 在任务线程内执行命令时
 * 将命令与输出追加进来，每次追加后通过 flusher 回调增量刷库。
 */
public class TaskLogContext {

    private static final ThreadLocal<TaskLogContext> CURRENT = new ThreadLocal<>();

    /** 单条输出块最大长度，超出截断，防止 docker 大量输出撑爆日志 */
    private static final int MAX_BLOCK_LENGTH = 20_000;

    /** 日志总长度上限（约 1MB），超出后不再追加 */
    private static final int MAX_TOTAL_LENGTH = 1_000_000;

    private final StringBuilder buffer = new StringBuilder();
    private final Consumer<String> flusher;
    private boolean truncated = false;

    public TaskLogContext(Consumer<String> flusher) {
        this.flusher = flusher;
    }

    public static void bind(TaskLogContext context) {
        CURRENT.set(context);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /**
     * 追加一段日志（自动补换行）并刷库；当前线程未绑定上下文时静默忽略
     */
    public static void append(String block) {
        TaskLogContext context = CURRENT.get();
        if (context == null || block == null) {
            return;
        }
        context.doAppend(block);
    }

    private synchronized void doAppend(String block) {
        if (buffer.length() >= MAX_TOTAL_LENGTH) {
            if (!truncated) {
                truncated = true;
                buffer.append("\n...[日志超长，后续输出已省略]...\n");
                flush();
            }
            return;
        }
        String trimmed = block.length() > MAX_BLOCK_LENGTH
                ? block.substring(0, MAX_BLOCK_LENGTH) + "\n...[输出过长已截断]..."
                : block;
        buffer.append(trimmed);
        if (!trimmed.endsWith("\n")) {
            buffer.append('\n');
        }
        flush();
    }

    private void flush() {
        if (flusher != null) {
            try {
                flusher.accept(buffer.toString());
            } catch (Exception ignored) {
                // 刷库失败不影响任务执行，最终状态更新时会整体落库
            }
        }
    }

    public synchronized String content() {
        return buffer.toString();
    }
}
