package com.easy.cd.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 镜像版本信息DTO
 */
@Data
@Builder
public class ImageVersionDTO {
    
    /**
     * 版本号/标签
     */
    private String version;
    
    /**
     * 镜像摘要 (digest)
     */
    private String digest;
    
    /**
     * 镜像大小（字节）
     */
    private Long size;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdTime;
    
    /**
     * 是否为当前运行版本
     */
    private Boolean isCurrent;
}
