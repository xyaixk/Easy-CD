package com.easy.cd.vo;

import com.easy.cd.entity.DeployTask;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 部署任务VO
 */
@Data
public class DeployTaskVO {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日志预览截断长度（列表接口） */
    private static final int LOG_PREVIEW_LENGTH = 200;

    private Long id;

    private Long environmentId;

    private Long serviceId;

    private String serviceName;

    /** CREATE/UPDATE/DELETE/RESTART/STOP/ROLLBACK/SCALE */
    private String taskType;

    /** PENDING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 命令日志（列表为前200字符预览，详情为完整内容） */
    private String commandLog;

    private String errorMsg;

    private String submittedBy;

    /** 提交人 IP 地址 */
    private String submittedIp;

    private String createdTime;

    private String startedTime;

    private String finishedTime;

    /** 执行耗时（毫秒），未开始/未结束时为空 */
    private Long durationMs;

    /**
     * 转换实体
     * @param fullLog true=完整日志（详情），false=截断预览（列表）
     */
    public static DeployTaskVO from(DeployTask task, boolean fullLog) {
        DeployTaskVO vo = new DeployTaskVO();
        vo.setId(task.getId());
        vo.setEnvironmentId(task.getEnvironmentId());
        vo.setServiceId(task.getServiceId());
        vo.setServiceName(task.getServiceName());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setErrorMsg(task.getErrorMsg());
        vo.setSubmittedBy(task.getSubmittedBy());
        vo.setSubmittedIp(task.getSubmittedIp());
        vo.setCreatedTime(format(task.getCreatedTime()));
        vo.setStartedTime(format(task.getStartedTime()));
        vo.setFinishedTime(format(task.getFinishedTime()));
        if (task.getStartedTime() != null && task.getFinishedTime() != null) {
            vo.setDurationMs(Duration.between(task.getStartedTime(), task.getFinishedTime()).toMillis());
        }
        String log = task.getCommandLog();
        if (log != null && !fullLog && log.length() > LOG_PREVIEW_LENGTH) {
            log = log.substring(0, LOG_PREVIEW_LENGTH) + "...";
        }
        vo.setCommandLog(log);
        return vo;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }
}
