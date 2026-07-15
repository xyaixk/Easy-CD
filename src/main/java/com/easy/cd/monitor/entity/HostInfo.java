package com.easy.cd.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 宿主机静态信息表（5min 刷新）
 */
@Data
@TableName("host_info")
public class HostInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long environmentId;

    /** 主机唯一键 user@ip:port */
    private String hostKey;

    private String ip;

    private String hostname;

    /** Swarm 节点 ID */
    private String swarmNodeId;

    /** manager / worker */
    private String swarmRole;

    /** ready / down */
    private String swarmStatus;

    private Integer cpuCores;

    /** 字节 */
    private Long memTotal;

    /** 根分区总量，字节 */
    private Long diskTotal;

    private LocalDateTime lastSeenTime;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
