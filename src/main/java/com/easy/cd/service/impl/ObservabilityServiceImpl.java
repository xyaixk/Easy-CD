package com.easy.cd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.config.OpenSearchConfig;
import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.entity.AppService;
import com.easy.cd.entity.Environment;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.mapper.ServiceMapper;
import com.easy.cd.service.ObservabilityService;
import com.easy.cd.vo.LogItemVO;
import com.easy.cd.vo.LogPageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.IndicesOptions;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.RangeQueryBuilder;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 日志检索服务：通过 OpenSearch 高级 REST 客户端访问索引。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>服务名候选直接读取 app_service 表（按环境隔离），不走 OpenSearch 聚合。</li>
 *   <li>查询 DSL 全部使用 {@link QueryBuilders} 构造，与 ES High-Level API 写法一致。</li>
 *   <li>所有外部异常统一封装为 {@link BusinessException} 并写入日志。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityServiceImpl implements ObservabilityService {

    /** 多个调用点复用的时间格式化器（线程安全不可变） */
    private static final DateTimeFormatter FMT_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpenSearchConfig osConfig;

    private final ServiceMapper serviceMapper;

    private final EnvironmentMapper environmentMapper;

    private final RestHighLevelClient openSearchClient;

    @Override
    public LogPageVO searchLogs(LogQueryDTO query) {
        String envName = resolveEnvName(query);
        int size = effectiveSize(query.getSize());
        int page = query.getPage() == null ? 1 : query.getPage();
        int from = Math.max(0, page - 1) * size;
        if (from + size > osConfig.getMaxResultWindow()) {
            size = Math.max(0, osConfig.getMaxResultWindow() - from);
            if (size <= 0) {
                return new LogPageVO(0L, Collections.emptyList());
            }
        }
        SearchSourceBuilder source = buildSearchSource(query, from, size);
        SearchResponse resp = executeSearch(envName, source);
        return parseSearchResponse(resp);
    }

    @Override
    public List<String> listServices(Long envId) {
        if (envId == null) {
            throw new BusinessException("envId 参数必填");
        }
        LambdaQueryWrapper<AppService> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AppService::getEnvironmentId, envId)
                .select(AppService::getName)
                .orderByAsc(AppService::getName);
        List<AppService> services = serviceMapper.selectList(wrapper);
        List<String> result = new ArrayList<>(services.size());
        for (AppService s : services) {
            if (StringUtils.isNotEmpty(s.getName())) {
                result.add(s.getName());
            }
        }
        return result;
    }

    @Override
    public void exportLogsCsv(LogQueryDTO query, HttpServletResponse response) {
        String envName = resolveEnvName(query);
        int size = osConfig.getMaxResultWindow();
        SearchSourceBuilder source = buildSearchSource(query, 0, size);
        SearchResponse resp = executeSearch(envName, source);
        LogPageVO page = parseSearchResponse(resp);

        String filename = "logs-" + envName + "-" + System.currentTimeMillis() + ".log";
        try {
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
            response.setContentType("text/plain;charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded);

            PrintWriter writer = response.getWriter();
            // 按时间正序输出（检索默认倒序，导出时反转）
            List<LogItemVO> items = new ArrayList<>(page.getItems());
            Collections.reverse(items);
            for (LogItemVO it : items) {
                writer.println(formatLogLine(it));
            }
            writer.flush();
            log.info("日志导出完成: env={}, rows={}", envName, items.size());
        } catch (Exception e) {
            log.error("日志导出失败: env={}", envName, e);
            throw new BusinessException("日志导出失败: " + e.getMessage());
        }
    }

    /**
     * 拼接单条原始日志行：
     * <pre>{timestamp} [{level}] [{service}] [{thread}] [{traceId}] {logger} - {message}{ | bizMessage}</pre>
     * 空字段会被跳过，避免出现 "[]、 - " 这种空干货。
     */
    private String formatLogLine(LogItemVO it) {
        StringBuilder sb = new StringBuilder(256);
        if (StringUtils.isNotBlank(it.getTimestamp())) sb.append(it.getTimestamp());
        if (StringUtils.isNotBlank(it.getLevel()))     sb.append(" [").append(it.getLevel()).append(']');
        if (StringUtils.isNotBlank(it.getService()))   sb.append(" [").append(it.getService()).append(']');
        if (StringUtils.isNotBlank(it.getThread()))    sb.append(" [").append(it.getThread()).append(']');
        if (StringUtils.isNotBlank(it.getTraceId()))   sb.append(" [").append(it.getTraceId()).append(']');
        if (StringUtils.isNotBlank(it.getLogger()))    sb.append(' ').append(it.getLogger());
        if (StringUtils.isNotBlank(it.getMessage()))   sb.append(" - ").append(it.getMessage());
        if (StringUtils.isNotBlank(it.getBizMessage())) sb.append(" | ").append(it.getBizMessage());
        return sb.toString();
    }

    // ============================ 内部方法 ============================

    /**
     * 解析查询请求中的环境名：优先取 envName，其次按 envId 反查 environment 表。
     */
    private String resolveEnvName(LogQueryDTO query) {
        if (query == null) {
            throw new BusinessException("查询参数不能为空");
        }
        if (StringUtils.isNotBlank(query.getEnvName())) {
            return query.getEnvName().trim();
        }
        if (query.getEnvId() != null) {
            Environment env = environmentMapper.selectById(query.getEnvId());
            if (env == null) {
                throw new BusinessException("环境不存在: " + query.getEnvId());
            }
            return env.getName();
        }
        throw new BusinessException("envId / envName 至少需要传入一个");
    }

    private int effectiveSize(Integer raw) {
        if (raw == null) {
            return 100;
        }
        if (raw == -1) {
            return osConfig.getMaxResultWindow();
        }
        return Math.min(Math.max(1, raw), osConfig.getMaxResultWindow());
    }

    /**
     * 构造 SearchSourceBuilder：bool query + 时间过滤 + 排序 + 分页。
     */
    private SearchSourceBuilder buildSearchSource(LogQueryDTO q, int from, int size) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();

        // 关键字：multi_match on message + biz_message
        if (StringUtils.isNotBlank(q.getKeyword())) {
            bool.must(QueryBuilders.multiMatchQuery(q.getKeyword().trim(), "message", "biz_message"));
        }
        // traceId：term 精确
        if (StringUtils.isNotBlank(q.getTraceId())) {
            bool.filter(QueryBuilders.termQuery("traceId.keyword", q.getTraceId().trim()));
        }
        // logger：prefix
        if (StringUtils.isNotBlank(q.getLogger())) {
            bool.filter(QueryBuilders.prefixQuery("logger.keyword", q.getLogger().trim()));
        }
        // container_name：wildcard *xxx*
        if (StringUtils.isNotBlank(q.getContainerName())) {
            bool.filter(QueryBuilders.wildcardQuery("container_name.keyword",
                    "*" + q.getContainerName().trim() + "*"));
        }
        // thread：prefix
        if (StringUtils.isNotBlank(q.getThread())) {
            bool.filter(QueryBuilders.prefixQuery("thread.keyword", q.getThread().trim()));
        }
        // services：用 app_from_image.keyword 精确匹配。
        // app_from_image 由采集器从镜像名 nexus.dev.ysb/{name}:{tag} 中截取，
        // 与 docker-compose 服务名、也就是 app_service.name 完全一致；
        // 而应用自报的 service / spring_app / app 字段各有命名风格（多个 -service 后缀），不可靠。
        if (!CollectionUtils.isEmpty(q.getServices())) {
            bool.filter(QueryBuilders.termsQuery("app_from_image.keyword", q.getServices()));
        }
        // levels：terms
        if (!CollectionUtils.isEmpty(q.getLevels())) {
            bool.filter(QueryBuilders.termsQuery("level_text.keyword", q.getLevels()));
        }
        // 时间范围
        RangeQueryBuilder range = buildRangeFilter(q);
        if (range != null) {
            bool.filter(range);
        }

        SearchSourceBuilder source = new SearchSourceBuilder()
                .from(from)
                .size(size)
                .trackTotalHits(true)
                .sort("@timestamp", SortOrder.DESC);
        source.query(bool.hasClauses() ? bool : QueryBuilders.matchAllQuery());
        return source;
    }

    /**
     * 构造时间范围过滤。优先使用自定义 from/to；否则按预设。
     */
    private RangeQueryBuilder buildRangeFilter(LogQueryDTO q) {
        String tr = q.getTimeRange();
        String fromStr;
        String toStr;
        if ("custom".equalsIgnoreCase(tr)) {
            fromStr = normalizeDateTime(q.getFrom());
            toStr = normalizeDateTime(q.getTo());
            if (fromStr == null && toStr == null) {
                return null;
            }
        } else {
            long minutes = parsePresetMinutes(tr);
            if (minutes <= 0) {
                return null;
            }
            fromStr = "now-" + minutes + "m";
            toStr = "now";
        }
        RangeQueryBuilder range = QueryBuilders.rangeQuery("@timestamp");
        if (fromStr != null) range.gte(fromStr);
        if (toStr != null)   range.lte(toStr);
        return range;
    }

    private long parsePresetMinutes(String tr) {
        if (StringUtils.isBlank(tr)) {
            return 60; // 默认 1h
        }
        switch (tr) {
            case "15m": return 15;
            case "1h":  return 60;
            case "6h":  return 360;
            case "24h": return 1440;
            default:    return 0;
        }
    }

    /**
     * 把 "yyyy-MM-ddTHH:mm" / "yyyy-MM-dd HH:mm" / "yyyy-MM-dd HH:mm:ss" 统一转为
     * 带本地时区偏移的 ISO-8601 字符串。
     */
    private String normalizeDateTime(String s) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        String v = s.trim().replace('T', ' ');
        try {
            DateTimeFormatter fmt = v.length() <= 16 ? FMT_MINUTE : FMT_SECOND;
            LocalDateTime ldt = LocalDateTime.parse(v, fmt);
            return ldt.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        } catch (Exception e) {
            log.warn("时间格式解析失败: {} -> {}", s, e.getMessage());
            return null;
        }
    }

    /** 调用 OpenSearch search 接口。 */
    private SearchResponse executeSearch(String envName, SearchSourceBuilder source) {
        // OpenSearch 索引名为全小写，environment 表里存的可能是 "Test"/"Prod"，拼接前统一小写避免漏查
        String index = osConfig.getIndexPattern().replace("{env}", envName.toLowerCase(Locale.ROOT));
        SearchRequest request = new SearchRequest(index)
                .source(source)
                .indicesOptions(IndicesOptions.lenientExpandOpen()); // 索引不存在时返回空，不抛异常
        try {
            SearchResponse resp = openSearchClient.search(request, RequestOptions.DEFAULT);
            // 排查用：打印实际索引、命中数、DSL；定位“筛选无结果”时直接看这一行就够
            long hits = resp != null && resp.getHits() != null && resp.getHits().getTotalHits() != null
                    ? resp.getHits().getTotalHits().value : -1L;
            log.info("OpenSearch search: index={} hits={} dsl={}", index, hits, source.toString().replaceAll("\\s+", " "));
            return resp;
        } catch (Exception e) {
            log.error("OpenSearch 检索失败: index={}", index, e);
            throw new BusinessException("日志检索失败: " + e.getMessage());
        }
    }

    /** 解析 SearchResponse 为 LogPageVO。 */
    private LogPageVO parseSearchResponse(SearchResponse resp) {
        if (resp == null || resp.getHits() == null) {
            return new LogPageVO(0L, Collections.emptyList());
        }
        long total = resp.getHits().getTotalHits() != null ? resp.getHits().getTotalHits().value : 0L;
        SearchHit[] hits = resp.getHits().getHits();
        List<LogItemVO> items = new ArrayList<>(hits.length);
        for (SearchHit hit : hits) {
            Map<String, Object> src = hit.getSourceAsMap();
            if (src == null) continue;
            items.add(toItem(src));
        }
        return new LogPageVO(total, items);
    }

    private LogItemVO toItem(Map<String, Object> src) {
        LogItemVO vo = new LogItemVO();
        vo.setTimestamp(Objects.toString(src.get("@timestamp"), null));
        vo.setLevel(Objects.toString(src.get("level_text"), null));
        vo.setService(Objects.toString(src.get("service"), null));
        vo.setMessage(Objects.toString(src.get("message"), null));
        vo.setBizMessage(Objects.toString(src.get("biz_message"), null));
        vo.setTraceId(Objects.toString(src.get("traceId"), null));
        vo.setLogger(Objects.toString(src.get("logger"), null));
        vo.setThread(Objects.toString(src.get("thread"), null));
        vo.setContainerName(Objects.toString(src.get("container_name"), null));
        vo.setImageName(Objects.toString(src.get("image_name"), null));
        vo.setSourceHost(Objects.toString(src.get("source_host"), null));
        return vo;
    }
}
