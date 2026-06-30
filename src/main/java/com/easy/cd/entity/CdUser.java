package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cd_user")
public class CdUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
