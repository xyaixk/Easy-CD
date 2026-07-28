package com.easy.cd.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Loki 日志检索配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "loki")
public class LokiConfig {

    /** 单次查询上限。 */
    private int maxResultWindow = 5000;
}
