package com.easy.cd.config;

import lombok.Data;
import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * OpenSearch 配置：参数绑定 + 高级 REST 客户端单例。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchConfig {

    /** OpenSearch 集群地址（不带末尾 /） */
    private String uri = "http://localhost:9200";

    /** 索引模式，{env} 占位符将被当前环境名替换 */
    private String indexPattern = "logs-{env}-*";

    /** 单次 search 上限（不分页/全部 时也以此为最大值） */
    private int maxResultWindow = 10000;

    /**
     * 高级 REST 客户端：连接超时 1s、读取超时 15s（与项目其他 HTTP 调用一致）。
     * 客户端持有连接池/线程池，必须以单例 Bean 形式管理并在容器销毁时关闭。
     */
    @Bean(destroyMethod = "close")
    public RestHighLevelClient openSearchClient() {
        URI u = URI.create(uri);
        int port = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
        HttpHost host = new HttpHost(u.getHost(), port, u.getScheme());
        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(rc -> rc.setConnectTimeout(1000).setSocketTimeout(15000));
        return new RestHighLevelClient(builder);
    }
}
