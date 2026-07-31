package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_group")
public class ServiceGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long environmentId;

    private String name;

    private Integer sortOrder;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
