package com.easy.cd.config;

import com.easy.cd.observability.LogTailWebSocketHandler;
import com.easy.cd.terminal.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：
 *   /terminal   Web 终端进入容器 / 服务聚合日志（SSH 链路）
 *   /logs/tail  Loki 实时日志推送代理
 * 实际连接地址 = ws://host:8080/api/xxx（context-path 会拼在前面）
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final LogTailWebSocketHandler logTailWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalWebSocketHandler, "/terminal")
                .setAllowedOrigins("*");
        registry.addHandler(logTailWebSocketHandler, "/logs/tail")
                .setAllowedOrigins("*");
    }
}
