package com.easy.cd.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.easy.cd.config.LokiConfig;
import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.entity.Environment;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.service.ObservabilityService;
import com.easy.cd.vo.LogContextVO;
import com.easy.cd.vo.LogHistogramBucketVO;
import com.easy.cd.vo.LogItemVO;
import com.easy.cd.vo.LogQueryResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志检索服务：通过 Loki HTTP API 查询 Alloy 写入的容器日志。
 * 查询范式对齐 Grafana Explore：游标翻页（无 offset）、直方图、上下文、trace 聚合、tail 实时。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityServiceImpl implements ObservabilityService {

    /** 单批查询上限 */
    private static final int MAX_BATCH_LIMIT = 1000;

    /** 上下文查询向前/向后的最大搜索窗口（1 小时，纳秒） */
    private static final long CONTEXT_WINDOW_NANOS = 3_600L * 1_000_000_000L;

    /** 直方图目标桶数（实际桶数按步长阶梯取整后浮动） */
    private static final int HISTOGRAM_TARGET_BUCKETS = 60;

    /** 直方图步长阶梯（秒） */
    private static final long[] HISTOGRAM_STEPS_SEC = {5, 10, 30, 60, 300, 900, 1800, 3600, 10800, 21600, 43200, 86400};

    private static final DateTimeFormatter FMT_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 允许通过 label-values 端点查询候选值的标签白名单 */
    private static final Set<String> ALLOWED_LABELS = new HashSet<>(Arrays.asList("service_name", "container_name", "image_name"));
    private static final Pattern SPRING_LOG_PATTERN = Pattern.compile(
            "^(?<time>\\S+)\\s+(?<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+\\[[^]]*]\\s+\\[(?<thread>[^]]*)]\\s+(?:\\[(?<trace>[^]-]+)(?:-[^]]*)?]\\s+)?(?<logger>\\S+)\\s*:\\s*(?<msg>.*)$");

    private final LokiConfig lokiConfig;
    private final EnvironmentMapper environmentMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    // ==================== 游标查询 ====================

    @Override
    public LogQueryResultVO queryLogs(LogQueryDTO query) {
        LokiEnvironmentConfig config = resolveLokiEnvironmentConfig(query);
        int limit = clampLimit(query.getLimit());
        LokiQueryWindow window = resolveQueryWindow(query);

        // 游标：向更早翻页时用上一批最旧一条的 tsNanos 收窄 end（Loki end 为开区间，天然排除锚点行）
        long endNanos = window.endNanos;
        Long before = parseNanosSafely(query.getBeforeNanos());
        if (before != null && before > window.startNanos && before < endNanos) {
            endNanos = before;
        }

        String logql = buildLogQl(config.appEnv, config.namespace, query);
        List<LogItemVO> items = executeLokiQuery(config.uri, logql, window.startNanos, endNanos, limit, "backward");
        return new LogQueryResultVO(items, items.size() >= limit);
    }

    // ==================== 直方图 ====================

    @Override
    public List<LogHistogramBucketVO> histogram(LogQueryDTO query) {
        LokiEnvironmentConfig config = resolveLokiEnvironmentConfig(query);
        LokiQueryWindow window = resolveQueryWindow(query);
        long startSec = window.startNanos / 1_000_000_000L;
        long endSec = window.endNanos / 1_000_000_000L;
        long stepSec = chooseHistogramStep(Math.max(1, endSec - startSec));

        String logql = buildLogQl(config.appEnv, config.namespace, query);
        String metricQl = "sum by (detected_level) (count_over_time(" + logql + " [" + stepSec + "s]))";

        URI uri = UriComponentsBuilder.fromHttpUrl(config.uri + "/loki/api/v1/query_range")
                .queryParam("query", metricQl)
                .queryParam("start", window.startNanos)
                .queryParam("end", window.endNanos)
                .queryParam("step", stepSec + "s")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();

        JSONArray result;
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            JSONObject root = JSON.parseObject(response.getBody());
            JSONObject data = root != null ? root.getJSONObject("data") : null;
            result = data != null ? data.getJSONArray("result") : null;
        } catch (Exception e) {
            log.error("Loki 直方图查询失败: query={}", metricQl, e);
            throw new BusinessException("日志直方图查询失败: " + e.getMessage());
        }

        // 评估点 T 的计数覆盖 (T-step, T]，展示时以桶起点 T-step 为准
        Map<Long, LogHistogramBucketVO> buckets = new HashMap<>();
        if (result != null) {
            for (int i = 0; i < result.size(); i++) {
                JSONObject series = result.getJSONObject(i);
                JSONObject metric = series.getJSONObject("metric");
                String level = metric != null ? StringUtils.lowerCase(metric.getString("detected_level")) : null;
                JSONArray values = series.getJSONArray("values");
                if (values == null) {
                    continue;
                }
                for (int j = 0; j < values.size(); j++) {
                    JSONArray point = values.getJSONArray(j);
                    if (point == null || point.size() < 2) {
                        continue;
                    }
                    long evalSec = point.getLongValue(0);
                    long count = (long) Double.parseDouble(point.getString(1));
                    long bucketStartMs = (evalSec - stepSec) * 1000L;
                    LogHistogramBucketVO bucket = buckets.computeIfAbsent(bucketStartMs, ms -> {
                        LogHistogramBucketVO b = new LogHistogramBucketVO();
                        b.setTsMs(ms);
                        return b;
                    });
                    accumulateLevel(bucket, level, count);
                }
            }
        }

        // 按窗口生成连续桶，缺失补零，保证前端 x 轴均匀
        List<LogHistogramBucketVO> list = new ArrayList<>();
        for (long evalSec = startSec; evalSec <= endSec; evalSec += stepSec) {
            long bucketStartMs = (evalSec - stepSec) * 1000L;
            LogHistogramBucketVO bucket = buckets.get(bucketStartMs);
            if (bucket == null) {
                bucket = new LogHistogramBucketVO();
                bucket.setTsMs(bucketStartMs);
            }
            list.add(bucket);
        }
        return list;
    }

    private void accumulateLevel(LogHistogramBucketVO bucket, String level, long count) {
        if (level == null) {
            bucket.setOther(bucket.getOther() + count);
            return;
        }
        switch (level) {
            case "error": bucket.setError(bucket.getError() + count); break;
            case "warn":
            case "warning": bucket.setWarn(bucket.getWarn() + count); break;
            case "info": bucket.setInfo(bucket.getInfo() + count); break;
            case "debug":
            case "trace": bucket.setDebug(bucket.getDebug() + count); break;
            default: bucket.setOther(bucket.getOther() + count);
        }
    }

    private long chooseHistogramStep(long spanSec) {
        long ideal = Math.max(1, spanSec / HISTOGRAM_TARGET_BUCKETS);
        for (long step : HISTOGRAM_STEPS_SEC) {
            if (step >= ideal) {
                return step;
            }
        }
        return HISTOGRAM_STEPS_SEC[HISTOGRAM_STEPS_SEC.length - 1];
    }

    // ==================== 上下文 ====================

    @Override
    public LogContextVO queryContext(Long envId, String container, String tsNanos, Integer limit) {
        if (StringUtils.isBlank(container)) {
            throw new BusinessException("container 参数必填");
        }
        Long anchor = parseNanosSafely(tsNanos);
        if (anchor == null) {
            throw new BusinessException("tsNanos 参数非法");
        }
        LokiEnvironmentConfig config = resolveLokiEnvironmentConfig(envId);
        int n = limit == null ? 50 : Math.min(Math.max(1, limit), 500);

        // 同一容器流不带级别/关键字过滤，还原真实上下文
        StringBuilder selector = new StringBuilder();
        selector.append("{app_env=").append(quoteLabel(config.appEnv));
        if (StringUtils.isNotBlank(config.namespace)) {
            selector.append(", namespace=").append(quoteLabel(config.namespace));
        }
        selector.append(", container_name=").append(quoteLabel(container.trim())).append('}');
        String logql = selector.toString();

        // 锚点之前 n 行：[anchor-1h, anchor) backward 后反转为正序
        List<LogItemVO> before = executeLokiQuery(
                config.uri, logql, anchor - CONTEXT_WINDOW_NANOS, anchor, n, "backward");
        Collections.reverse(before);

        // 锚点行 + 之后 n 行：[anchor, anchor+1h) forward
        List<LogItemVO> afterWithAnchor = executeLokiQuery(
                config.uri, logql, anchor, anchor + CONTEXT_WINDOW_NANOS, n + 1, "forward");

        List<LogItemVO> items = new ArrayList<>(before.size() + afterWithAnchor.size());
        items.addAll(before);
        items.addAll(afterWithAnchor);

        int anchorIndex = -1;
        if (!afterWithAnchor.isEmpty() && tsNanos.equals(afterWithAnchor.get(0).getTsNanos())) {
            anchorIndex = before.size();
        }
        return new LogContextVO(items, anchorIndex);
    }

    // ==================== trace 聚合 ====================

    @Override
    public List<LogItemVO> queryTrace(Long envId, String traceId, String from, String to) {
        if (StringUtils.isBlank(traceId)) {
            throw new BusinessException("traceId 参数必填");
        }
        LokiEnvironmentConfig config = resolveLokiEnvironmentConfig(envId);

        ZonedDateTime toTime = parseDateTime(to);
        if (toTime == null) {
            toTime = ZonedDateTime.now();
        }
        ZonedDateTime fromTime = parseDateTime(from);
        if (fromTime == null) {
            fromTime = toTime.minusMinutes(30);
        }

        String logql = buildEnvironmentSelector(config) + " |~ " + quoteString("(?i)" + regexpQuote(traceId.trim()));
        return executeLokiQuery(config.uri, logql, toNanos(fromTime), toNanos(toTime), MAX_BATCH_LIMIT, "forward");
    }

    // ==================== 标签 / 导出 ====================

    @Override
    public List<String> listServices(Long envId) {
        return listLabelValues(envId, "service_name");
    }

    @Override
    public List<String> listLabelValues(Long envId, String label) {
        String target = label == null ? "" : label.trim();
        if (!ALLOWED_LABELS.contains(target)) {
            throw new BusinessException("不支持的标签: " + label);
        }
        LokiEnvironmentConfig config = resolveLokiEnvironmentConfig(envId);
        URI uri = UriComponentsBuilder.fromHttpUrl(config.uri + "/loki/api/v1/label/" + target + "/values")
                .queryParam("query", buildEnvironmentSelector(config))
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
        return executeLokiStringList(uri, target + " 标签");
    }

    @Override
    public void exportLogsCsv(LogQueryDTO query, HttpServletResponse response) {
        LokiEnvironmentConfig environmentConfig = resolveLokiEnvironmentConfig(query);
        String envName = environmentConfig.environmentName;
        LokiQueryWindow window = resolveQueryWindow(query);
        String logql = buildLogQl(environmentConfig.appEnv, environmentConfig.namespace, query);
        List<LogItemVO> items = executeLokiQuery(environmentConfig.uri, logql,
                window.startNanos, window.endNanos, lokiConfig.getMaxResultWindow(), "backward");
        Collections.reverse(items);

        String filename = "logs-" + envName + "-" + System.currentTimeMillis() + ".log";
        try {
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
            response.setContentType("text/plain;charset=UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded);

            PrintWriter writer = response.getWriter();
            for (LogItemVO it : items) {
                writer.println(formatLogLine(it));
            }
            writer.flush();
            log.info("Loki日志导出完成: env={}, rows={}", envName, items.size());
        } catch (Exception e) {
            log.error("日志导出失败: env={}", envName, e);
            throw new BusinessException("日志导出失败: " + e.getMessage());
        }
    }

    // ==================== tail 实时 ====================

    @Override
    public URI buildTailUri(LogQueryDTO query) {
        LokiEnvironmentConfig config = resolveLokiEnvironmentConfig(query);
        String logql = buildLogQl(config.appEnv, config.namespace, query);
        // http -> ws / https -> wss
        String wsBase = config.uri.replaceFirst("^http", "ws");
        return UriComponentsBuilder.fromUriString(wsBase + "/loki/api/v1/tail")
                .queryParam("query", logql)
                .queryParam("limit", 100)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }

    @Override
    public List<LogItemVO> parseTailFrame(String payload) {
        if (StringUtils.isBlank(payload)) {
            return Collections.emptyList();
        }
        JSONObject root = JSON.parseObject(payload);
        JSONArray streams = root != null ? root.getJSONArray("streams") : null;
        List<LogItemVO> items = parseStreamsArray(streams);
        items.sort(Comparator.comparingLong(ObservabilityServiceImpl::itemNanos));
        return items;
    }

    // ==================== 私有工具 ====================

    private String formatLogLine(LogItemVO it) {
        StringBuilder sb = new StringBuilder(256);
        if (StringUtils.isNotBlank(it.getTimestamp())) sb.append(it.getTimestamp());
        if (StringUtils.isNotBlank(it.getLevel())) sb.append(" [").append(it.getLevel()).append(']');
        if (StringUtils.isNotBlank(it.getService())) sb.append(" [").append(it.getService()).append(']');
        if (StringUtils.isNotBlank(it.getThread())) sb.append(" [").append(it.getThread()).append(']');
        if (StringUtils.isNotBlank(it.getTraceId())) sb.append(" [").append(it.getTraceId()).append(']');
        if (StringUtils.isNotBlank(it.getLogger())) sb.append(' ').append(it.getLogger());
        if (StringUtils.isNotBlank(it.getMessage())) sb.append(" - ").append(it.getMessage());
        if (StringUtils.isNotBlank(it.getBizMessage())) sb.append(" | ").append(it.getBizMessage());
        return sb.toString();
    }

    private LokiEnvironmentConfig resolveLokiEnvironmentConfig(LogQueryDTO query) {
        if (query == null || query.getEnvId() == null) {
            throw new BusinessException("envId 参数必填");
        }
        return resolveLokiEnvironmentConfig(query.getEnvId());
    }

    private LokiEnvironmentConfig resolveLokiEnvironmentConfig(Long envId) {
        if (envId == null) {
            throw new BusinessException("envId 参数必填");
        }

        Environment environment = environmentMapper.selectById(envId);
        if (environment == null) {
            throw new BusinessException("环境不存在: " + envId);
        }

        JSONObject config;
        try {
            config = JSON.parseObject(environment.getConfig());
        } catch (Exception e) {
            throw new BusinessException("环境配置格式错误: " + environment.getName());
        }
        String uri = config != null ? config.getString("lokiUri") : null;
        if (StringUtils.isBlank(uri)) {
            throw new BusinessException("当前环境未配置 Loki");
        }

        String normalizedUri = trimTrailingSlash(uri);
        URI parsedUri;
        try {
            parsedUri = URI.create(normalizedUri);
        } catch (Exception e) {
            throw new BusinessException("当前环境的 Loki 地址格式错误");
        }
        String scheme = parsedUri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || StringUtils.isBlank(parsedUri.getHost())) {
            throw new BusinessException("当前环境的 Loki 地址必须是有效的 HTTP/HTTPS 地址");
        }

        String namespace = config.getString("lokiNamespace");
        String appEnv = StringUtils.defaultIfBlank(config.getString("lokiAppEnv"), environment.getName());
        return new LokiEnvironmentConfig(environment.getName(), appEnv.trim(), normalizedUri,
                StringUtils.trimToEmpty(namespace));
    }

    private int clampLimit(Integer raw) {
        if (raw == null) {
            return 500;
        }
        return Math.min(Math.max(1, raw), MAX_BATCH_LIMIT);
    }

    private Long parseNanosSafely(String nanos) {
        if (StringUtils.isBlank(nanos)) {
            return null;
        }
        try {
            return Long.parseLong(nanos.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LokiQueryWindow resolveQueryWindow(LogQueryDTO query) {
        String tr = query.getTimeRange();
        ZonedDateTime to = parseDateTime(query.getTo());
        if (to == null) {
            to = ZonedDateTime.now();
        }
        ZonedDateTime from = to.minusHours(1);

        if ("custom".equalsIgnoreCase(tr)) {
            ZonedDateTime customFrom = parseDateTime(query.getFrom());
            ZonedDateTime customTo = parseDateTime(query.getTo());
            if (customFrom != null) {
                from = customFrom;
            }
            if (customTo != null) {
                to = customTo;
            }
        } else {
            long minutes = parsePresetMinutes(tr);
            from = to.minusMinutes(minutes > 0 ? minutes : 60);
        }
        return new LokiQueryWindow(toNanos(from), toNanos(to));
    }

    /** 解析快捷区间（如 5m/30m/1h/12h/2d/7d）为分钟数，无法识别时回落 60 分钟 */
    private long parsePresetMinutes(String tr) {
        if (StringUtils.isBlank(tr)) {
            return 60;
        }
        String v = tr.trim().toLowerCase();
        char unit = v.charAt(v.length() - 1);
        long n;
        try {
            n = Long.parseLong(v.substring(0, v.length() - 1));
        } catch (NumberFormatException e) {
            return 60;
        }
        if (n <= 0) {
            return 60;
        }
        switch (unit) {
            case 'm': return n;
            case 'h': return n * 60;
            case 'd': return n * 1440;
            default: return 60;
        }
    }

    private ZonedDateTime parseDateTime(String s) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        String v = s.trim().replace('T', ' ');
        try {
            DateTimeFormatter fmt = v.length() <= 16 ? FMT_MINUTE : FMT_SECOND;
            LocalDateTime ldt = LocalDateTime.parse(v, fmt);
            return ldt.atZone(ZoneId.systemDefault());
        } catch (Exception e) {
            log.warn("时间格式解析失败: {} -> {}", s, e.getMessage());
            return null;
        }
    }

    private long toNanos(ZonedDateTime time) {
        Instant instant = time.toInstant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private String buildLogQl(String envName, String namespace, LogQueryDTO q) {
        StringBuilder selector = new StringBuilder();
        selector.append("{app_env=").append(quoteLabel(envName));
        if (StringUtils.isNotBlank(namespace)) {
            selector.append(", namespace=").append(quoteLabel(namespace));
        }
        if (!CollectionUtils.isEmpty(q.getServices())) {
            selector.append(", service_name=~").append(quoteLabel(joinRegex(q.getServices(), false)));
        }
        if (!CollectionUtils.isEmpty(q.getImages())) {
            selector.append(", image_name=~").append(quoteLabel(joinRegex(q.getImages(), false)));
        }
        if (StringUtils.isNotBlank(q.getContainerName())) {
            selector.append(", container_name=~").append(quoteLabel(".*" + regexpQuote(q.getContainerName().trim()) + ".*"));
        }
        selector.append('}');

        appendLevelFilter(selector, q.getLevels());
        appendLineFilter(selector, q.getKeyword());
        appendLineFilter(selector, q.getTraceId());
        appendLineFilter(selector, q.getLogger());
        appendLineFilter(selector, q.getThread());
        return selector.toString();
    }

    /** 级别过滤：基于 Loki structured metadata 的 detected_level，与直方图口径一致，对非 Spring 格式行同样生效 */
    private void appendLevelFilter(StringBuilder sb, List<String> levels) {
        if (CollectionUtils.isEmpty(levels)) {
            return;
        }
        Set<String> vals = new LinkedHashSet<>();
        for (String lv : levels) {
            if (StringUtils.isBlank(lv)) {
                continue;
            }
            switch (lv.trim().toUpperCase(Locale.ROOT)) {
                case "ERROR": vals.add("error"); break;
                case "WARN": vals.add("warn"); vals.add("warning"); break;
                case "INFO": vals.add("info"); break;
                case "DEBUG": vals.add("debug"); vals.add("trace"); break;
                default: break;
            }
        }
        // 四个级别全选时不加过滤，避免漏掉 unknown 级别的行
        if (vals.isEmpty() || vals.size() >= 6) {
            return;
        }
        sb.append(" | detected_level=~").append(quoteString(String.join("|", vals)));
    }

    /** 行内容过滤：大小写不敏感的子串包含 */
    private void appendLineFilter(StringBuilder sb, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append(" |~ ").append(quoteString("(?i)" + regexpQuote(value.trim())));
        }
    }

    private String buildEnvironmentSelector(LokiEnvironmentConfig config) {
        StringBuilder selector = new StringBuilder("{app_env=")
                .append(quoteLabel(config.appEnv));
        if (StringUtils.isNotBlank(config.namespace)) {
            selector.append(", namespace=").append(quoteLabel(config.namespace));
        }
        return selector.append('}').toString();
    }

    private List<String> executeLokiStringList(URI uri, String dataType) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            JSONObject root = JSON.parseObject(response.getBody());
            JSONArray data = root != null ? root.getJSONArray("data") : null;
            if (data == null || data.isEmpty()) {
                return new ArrayList<>();
            }
            Set<String> unique = new HashSet<>();
            for (int i = 0; i < data.size(); i++) {
                String value = data.getString(i);
                if (StringUtils.isNotBlank(value)) {
                    unique.add(value);
                }
            }
            List<String> result = new ArrayList<>(unique);
            Collections.sort(result);
            return result;
        } catch (Exception e) {
            log.error("获取 Loki {}失败: uri={}", dataType, uri, e);
            throw new BusinessException("获取 Loki " + dataType + "失败: " + e.getMessage());
        }
    }

    private String joinRegex(List<String> values, boolean lower) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (StringUtils.isBlank(value)) {
                continue;
            }
            unique.add(regexpQuote(lower ? value.trim().toLowerCase(Locale.ROOT) : value.trim()));
        }
        if (unique.isEmpty()) {
            return ".*";
        }
        return String.join("|", unique);
    }

    private String quoteLabel(String value) {
        return quoteString(value == null ? "" : value);
    }

    private String quoteString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String regexpQuote(String value) {
        StringBuilder sb = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 执行 Loki query_range 日志查询。
     * backward 返回时间倒序（最新在前），forward 返回时间正序。
     */
    private List<LogItemVO> executeLokiQuery(String lokiUri, String logql,
                                             long startNanos, long endNanos, int limit, String direction) {
        URI uri = UriComponentsBuilder.fromHttpUrl(lokiUri + "/loki/api/v1/query_range")
                .queryParam("query", logql)
                .queryParam("start", startNanos)
                .queryParam("end", endNanos)
                .queryParam("limit", limit)
                .queryParam("direction", direction)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            List<LogItemVO> items = parseLokiResponse(response.getBody());
            Comparator<LogItemVO> asc = Comparator.comparingLong(ObservabilityServiceImpl::itemNanos);
            items.sort("backward".equals(direction) ? asc.reversed() : asc);
            // Loki 按流返回可能超过 limit 合并后的条数，这里统一截断
            if (items.size() > limit) {
                items = new ArrayList<>(items.subList(0, limit));
            }
            log.info("Loki search: hits={} direction={} query={}", items.size(), direction, logql);
            return items;
        } catch (Exception e) {
            log.error("Loki 检索失败: query={}", logql, e);
            throw new BusinessException("日志检索失败: " + e.getMessage());
        }
    }

    private String trimTrailingSlash(String uri) {
        String value = uri.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private List<LogItemVO> parseLokiResponse(String body) {
        if (StringUtils.isBlank(body)) {
            return Collections.emptyList();
        }
        JSONObject root = JSON.parseObject(body);
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return Collections.emptyList();
        }
        return parseStreamsArray(data.getJSONArray("result"));
    }

    /** 解析 streams 数组（query_range 的 data.result 与 tail 帧的 streams 结构一致） */
    private List<LogItemVO> parseStreamsArray(JSONArray result) {
        if (result == null || result.isEmpty()) {
            return new ArrayList<>();
        }
        List<LogItemVO> items = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            JSONObject streamObj = result.getJSONObject(i);
            JSONObject stream = streamObj.getJSONObject("stream");
            JSONArray values = streamObj.getJSONArray("values");
            if (values == null) {
                continue;
            }
            for (int j = 0; j < values.size(); j++) {
                JSONArray pair = values.getJSONArray(j);
                if (pair == null || pair.size() < 2) {
                    continue;
                }
                String nanos = pair.getString(0);
                String line = pair.getString(1);
                items.add(toItem(stream, nanos, line));
            }
        }
        return items;
    }

    private LogItemVO toItem(JSONObject stream, String nanos, String line) {
        LogItemVO vo = new LogItemVO();
        vo.setTimestamp(formatNanos(nanos));
        vo.setTsNanos(nanos);
        vo.setMessage(line);
        if (stream != null) {
            vo.setService(firstNonBlank(stream.getString("service_name"), stream.getString("service")));
            vo.setContainerName(firstNonBlank(stream.getString("container_name"), stream.getString("container")));
            vo.setImageName(stream.getString("image_name"));
            vo.setLevel(normalizeLevel(stream.getString("detected_level")));
            vo.setSourceHost(stream.getString("host"));
            vo.setTraceId(stream.getString("trace_id"));
            vo.setLogger(stream.getString("logger"));
            vo.setThread(stream.getString("thread"));
        }

        Matcher matcher = SPRING_LOG_PATTERN.matcher(line == null ? "" : line);
        if (matcher.matches()) {
            vo.setLevel(firstNonBlank(matcher.group("level"), vo.getLevel()));
            vo.setThread(firstNonBlank(matcher.group("thread"), vo.getThread()));
            vo.setTraceId(firstNonBlank(matcher.group("trace"), vo.getTraceId()));
            vo.setLogger(firstNonBlank(matcher.group("logger"), vo.getLogger()));
            vo.setBizMessage(matcher.group("msg"));
        }
        if (StringUtils.isBlank(vo.getLevel())) {
            vo.setLevel("UNKNOWN");
        }
        return vo;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private String normalizeLevel(String level) {
        return StringUtils.isBlank(level) ? null : level.trim().toUpperCase(Locale.ROOT);
    }

    private String formatNanos(String nanos) {
        try {
            long ns = Long.parseLong(nanos);
            Instant instant = Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L);
            return instant.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();
        } catch (Exception e) {
            return nanos;
        }
    }

    private static long itemNanos(LogItemVO item) {
        try {
            return Long.parseLong(item.getTsNanos());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static class LokiQueryWindow {
        private final long startNanos;
        private final long endNanos;

        private LokiQueryWindow(long startNanos, long endNanos) {
            this.startNanos = startNanos;
            this.endNanos = endNanos;
        }
    }

    private static class LokiEnvironmentConfig {
        private final String environmentName;
        private final String appEnv;
        private final String uri;
        private final String namespace;

        private LokiEnvironmentConfig(String environmentName, String appEnv, String uri, String namespace) {
            this.environmentName = environmentName;
            this.appEnv = appEnv;
            this.uri = uri;
            this.namespace = namespace;
        }
    }
}
