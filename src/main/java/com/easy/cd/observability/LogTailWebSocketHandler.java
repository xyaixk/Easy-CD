package com.easy.cd.observability;

import com.alibaba.fastjson.JSON;
import com.easy.cd.auth.SessionManager;
import com.easy.cd.dto.LogQueryDTO;
import com.easy.cd.entity.Environment;
import com.easy.cd.mapper.EnvironmentMapper;
import com.easy.cd.service.ObservabilityService;
import com.easy.cd.vo.LogItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PreDestroy;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 日志实时推送代理：浏览器 <-> 本服务 <-> Loki /loki/api/v1/tail（WebSocket）
 *
 * 浏览器不直连 Loki：由本服务做 token 鉴权、环境隔离和 LogQL 构造。
 * 连接参数：/logs/tail?envId=&token=&services=a,b&levels=INFO,ERROR&keyword=&traceId=&logger=&containerName=&thread=
 * 服务端 -> 客户端：文本帧，JSON 数组（LogItemVO 列表，时间正序）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogTailWebSocketHandler extends TextWebSocketHandler {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final EnvironmentMapper environmentMapper;
    private final SessionManager sessionManager;
    private final ObservabilityService observabilityService;

    /** 浏览器 sessionId -> 对应的 Loki 上游连接 */
    private final Map<String, WebSocketSession> upstreams = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, 10_000, 2 * 1024 * 1024);
        Map<String, String> params = parseQuery(rawSession.getUri() != null ? rawSession.getUri().getQuery() : null);

        try {
            Long envId = Long.parseLong(params.get("envId"));
            Environment environment = environmentMapper.selectById(envId);
            if (environment == null) { closeWith(session, "环境不存在"); return; }

            // 环境需要登录时校验 token（WebSocket 不经过 MVC 拦截器）
            String token = params.get("token");
            if (Boolean.TRUE.equals(environment.getNeedLogin())
                    && (token == null || sessionManager.getByToken(token) == null)) {
                closeWith(session, "未登录或登录已过期");
                return;
            }

            LogQueryDTO query = buildQuery(envId, params);
            URI tailUri = observabilityService.buildTailUri(query);
            connectUpstream(session, tailUri);
        } catch (NumberFormatException e) {
            closeWith(session, "参数错误");
        } catch (Exception e) {
            log.warn("日志 tail 建立失败: {}", e.getMessage());
            closeWith(session, "实时日志连接失败: " + e.getMessage());
        }
    }

    private LogQueryDTO buildQuery(Long envId, Map<String, String> params) {
        LogQueryDTO query = new LogQueryDTO();
        query.setEnvId(envId);
        query.setServices(splitParam(params.get("services")));
        query.setImages(splitParam(params.get("images")));
        query.setLevels(splitParam(params.get("levels")));
        query.setKeyword(params.get("keyword"));
        query.setTraceId(params.get("traceId"));
        query.setLogger(params.get("logger"));
        query.setContainerName(params.get("containerName"));
        query.setThread(params.get("thread"));
        return query;
    }

    private List<String> splitParam(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 作为 WebSocket 客户端连接 Loki tail，把推送帧解析后转发给浏览器 */
    private void connectUpstream(WebSocketSession browser, URI tailUri) throws Exception {
        // Loki tail 帧可能超过 Tomcat 默认 8K 文本缓冲
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(2 * 1024 * 1024);
        container.setDefaultMaxBinaryMessageBufferSize(2 * 1024 * 1024);
        StandardWebSocketClient client = new StandardWebSocketClient(container);

        TextWebSocketHandler upstreamHandler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession lokiSession, TextMessage message) {
                try {
                    List<LogItemVO> items = observabilityService.parseTailFrame(message.getPayload());
                    if (!items.isEmpty() && browser.isOpen()) {
                        browser.sendMessage(new TextMessage(JSON.toJSONString(items)));
                    }
                } catch (Exception e) {
                    log.debug("tail 帧转发失败: {}", e.getMessage());
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession lokiSession, CloseStatus status) {
                // 上游断开时同步关闭浏览器侧
                try {
                    if (browser.isOpen()) {
                        browser.close(CloseStatus.NORMAL);
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public void handleTransportError(WebSocketSession lokiSession, Throwable exception) {
                log.debug("Loki tail 传输异常: {}", exception.getMessage());
                try {
                    if (browser.isOpen()) {
                        browser.close(CloseStatus.SERVER_ERROR);
                    }
                } catch (Exception ignored) {}
            }
        };

        WebSocketSession upstream = client.doHandshake(upstreamHandler, new WebSocketHttpHeaders(), tailUri)
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        upstreams.put(browser.getId(), upstream);
        log.info("日志 tail 已建立: session={}, loki={}", browser.getId(), tailUri.getHost());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("日志 tail 传输异常: {}", exception.getMessage());
        cleanup(session.getId());
    }

    private void cleanup(String sessionId) {
        WebSocketSession upstream = upstreams.remove(sessionId);
        if (upstream != null) {
            try {
                if (upstream.isOpen()) {
                    upstream.close(CloseStatus.NORMAL);
                }
            } catch (Exception ignored) {}
            log.info("日志 tail 已关闭: session={}", sessionId);
        }
    }

    /** 发送错误提示后关闭连接（前端按 JSON 对象识别错误帧） */
    private void closeWith(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(JSON.toJSONString(
                    java.util.Collections.singletonMap("error", message))));
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {}
    }

    private Map<String, String> parseQuery(String query) throws Exception {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        return params;
    }

    @PreDestroy
    public void destroy() {
        for (String id : upstreams.keySet()) {
            cleanup(id);
        }
    }
}
