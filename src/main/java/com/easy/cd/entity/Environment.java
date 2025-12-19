package com.easy.cd.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("environment")
public class Environment {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String name;
    
    private String color;
    
    private String deployType;
    
    private String config;
    
    private LocalDateTime createdTime;
}
