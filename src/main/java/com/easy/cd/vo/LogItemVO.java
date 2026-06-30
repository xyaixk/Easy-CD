package com.easy.cd.vo;

import lombok.Data;

/**
 * 单条日志记录返回。字段直接对应前端 LogSearchDialog 的列。
 */
@Data
public class LogItemVO {

    /** ISO-8601 时间字符串（@timestamp 原值） */
    private String timestamp;

    /** 级别文本：INFO / WARN / ERROR / DEBUG */
    private String level;

    /** 服务名 */
    private String service;

    /** 消息正文 */
    private String message;

    /** 业务消息（可能为空） */
    private String bizMessage;

    /** 链路 ID（可能为空） */
    private String traceId;

    /** Logger 类全名 */
    private String logger;

    /** 线程名 */
    private String thread;

    /** 容器名 */
    private String containerName;

    /** 镜像名 */
    private String imageName;

    /** 来源主机 */
    private String sourceHost;
}
