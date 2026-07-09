package com.easy.cd.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.config.LokiConfig;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志检索服务：通过 Loki query_range API 查询 Alloy 写入的容器日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservabilityServiceImpl implements ObservabilityService {

    private static final int LOKI_MAX_QUERY_LIMIT = 5000;

    private static final DateTimeFormatter FMT_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FMT_SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern SPRING_LOG_PATTERN = Pattern.compile(
            "^(?<time>\\S+)\\s+(?<level>TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+\\[[^]]*]\\s+\\[(?<thread>[^]]*)]\\s+(?:\\[(?<trace>[^]-]+)(?:-[^]]*)?]\\s+)?(?<logger>\\S+)\\s*:\\s*(?<msg>.*)$");

    private final LokiConfig lokiConfig;
    private final ServiceMapper serviceMapper;
    private final EnvironmentMapper environmentMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public LogPageVO searchLogs(LogQueryDTO query) {
        String envName = resolveEnvName(query);
        int size = effectiveSize(query.getSize());
        int page = query.getPage() == null ? 1 : Math.max(1, query.getPage());
        int from = query.getSize() != null && query.getSize() == -1 ? 0 : Math.max(0, page - 1) * size;
        int fetchLimit = Math.min(lokiConfig.getMaxResultWindow(), from + size);
        if (fetchLimit <= 0) {
            return new LogPageVO(0L, Collections.emptyList());
        }

        LokiQueryWindow window = resolveQueryWindow(query);
        String logql = buildLogQl(envName, query);
        List<LogItemVO> fetched = executeLokiQuery(logql, window, fetchLimit);
        List<LogItemVO> pageItems = slicePage(fetched, from, size);

        long total = fetched.size();
        if (fetched.size() >= fetchLimit && fetchLimit < lokiConfig.getMaxResultWindow()) {
            total = fetchLimit + 1L;
        }
        return new LogPageVO(total, pageItems);
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
        LokiQueryWindow window = resolveQueryWindow(query);
        String logql = buildLogQl(envName, query);
        List<LogItemVO> items = executeLokiQuery(logql, window, lokiConfig.getMaxResultWindow());
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
            return maxQueryLimit();
        }
        return Math.min(Math.max(1, raw), maxQueryLimit());
    }

    private int maxQueryLimit() {
        return Math.min(Math.max(1, lokiConfig.getMaxResultWindow()), LOKI_MAX_QUERY_LIMIT);
    }

    private List<LogItemVO> slicePage(List<LogItemVO> items, int from, int size) {
        if (from >= items.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(items.size(), from + size);
        return new ArrayList<>(items.subList(from, to));
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

    private long parsePresetMinutes(String tr) {
        if (StringUtils.isBlank(tr)) {
            return 60;
        }
        switch (tr) {
            case "15m": return 15;
            case "1h": return 60;
            case "6h": return 360;
            case "24h": return 1440;
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

    private String buildLogQl(String envName, LogQueryDTO q) {
        StringBuilder selector = new StringBuilder();
        selector.append("{app_env=").append(quoteLabel(envName));
        if (StringUtils.isNotBlank(lokiConfig.getNamespace())) {
            selector.append(", namespace=").append(quoteLabel(lokiConfig.getNamespace().trim()));
        }
        if (!CollectionUtils.isEmpty(q.getServices())) {
            selector.append(", service_name=~").append(quoteLabel(joinRegex(q.getServices(), false)));
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

    private void appendLevelFilter(StringBuilder sb, List<String> levels) {
        if (CollectionUtils.isEmpty(levels)) {
            return;
        }
        String regex = joinRegex(levels, true);
        if (StringUtils.isNotBlank(regex) && !".*".equals(regex)) {
            sb.append(" |~ ").append(quoteString("(?i)\\b(" + regex + ")\\b"));
        }
    }

    private void appendLineFilter(StringBuilder sb, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append(" |= ").append(quoteString(value.trim()));
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

    private List<LogItemVO> executeLokiQuery(String logql, LokiQueryWindow window, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl(trimTrailingSlash(lokiConfig.getUri()) + "/loki/api/v1/query_range")
                .queryParam("query", logql)
                .queryParam("start", window.startNanos)
                .queryParam("end", window.endNanos)
                .queryParam("limit", limit)
                .queryParam("direction", "backward")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            List<LogItemVO> items = parseLokiResponse(response.getBody());
            items.sort(Comparator.comparing(ObservabilityServiceImpl::timestampNanos).reversed());
            log.info("Loki search: hits={} query={}", items.size(), logql);
            return items;
        } catch (Exception e) {
            log.error("Loki 检索失败: query={}", logql, e);
            throw new BusinessException("日志检索失败: " + e.getMessage());
        }
    }

    private String trimTrailingSlash(String uri) {
        if (StringUtils.isBlank(uri)) {
            return "http://localhost:3100";
        }
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
        JSONArray result = data.getJSONArray("result");
        if (result == null || result.isEmpty()) {
            return Collections.emptyList();
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
        vo.setMessage(line);
        if (stream != null) {
            vo.setService(firstNonBlank(stream.getString("service_name"), stream.getString("container_name")));
            vo.setContainerName(stream.getString("container_name"));
            vo.setLevel(normalizeLevel(stream.getString("detected_level")));
            vo.setSourceHost(stream.getString("host"));
        }

        Matcher matcher = SPRING_LOG_PATTERN.matcher(line == null ? "" : line);
        if (matcher.matches()) {
            vo.setLevel(firstNonBlank(matcher.group("level"), vo.getLevel()));
            vo.setThread(matcher.group("thread"));
            vo.setTraceId(matcher.group("trace"));
            vo.setLogger(matcher.group("logger"));
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

    private static long timestampNanos(LogItemVO item) {
        String timestamp = item.getTimestamp();
        if (StringUtils.isBlank(timestamp)) {
            return 0L;
        }
        try {
            Instant instant = OffsetDateTime.parse(timestamp).toInstant();
            return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
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
}
