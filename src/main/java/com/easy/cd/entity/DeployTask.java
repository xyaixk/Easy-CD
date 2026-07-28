package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署操作异步任务
 */
@Data
@TableName("deploy_task")
public class DeployTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long environmentId;

    /** 服务ID（创建任务提交时为空） */
    private Long serviceId;

    private String serviceName;

    /** CREATE/UPDATE/DELETE/RESTART/STOP/ROLLBACK/SCALE */
    private String taskType;

    /** PENDING/RUNNING/SUCCESS/FAILED */
    private String status;

    /** 执行的SSH命令与输出 */
    private String commandLog;

    private String errorMsg;

    private String submittedBy;

    /** 提交人 IP 地址 */
    private String submittedIp;

    private LocalDateTime createdTime;

    private LocalDateTime startedTime;

    private LocalDateTime finishedTime;
}
