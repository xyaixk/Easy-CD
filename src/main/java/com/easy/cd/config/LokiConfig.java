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

    /** Loki 地址，不带末尾 /。 */
    private String uri = "http://localhost:3100";

    /** Easy-CD/Alloy 写入 Loki 的 namespace 标签。 */
    private String namespace = "ysb";

    /** 单次查询上限。 */
    private int maxResultWindow = 5000;
}
