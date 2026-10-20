package com.gamestock.backend.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final MarketSocketHandler handler;
    private final SubscriptionSocketHandler subscriptions;
    private final String[] allowedOrigins;

    public WebSocketConfig(MarketSocketHandler handler, SubscriptionSocketHandler subscriptions,
                           @Value("${gamestock.websocket.allowed-origins}") String configuredOrigins) {
        this.handler = handler;
        this.subscriptions = subscriptions;
        this.allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        if (allowedOrigins.length == 0) {
            throw new IllegalArgumentException("At least one WebSocket origin must be configured");
        }
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/market").setAllowedOriginPatterns(allowedOrigins);
        registry.addHandler(subscriptions, "/ws/stream").setAllowedOriginPatterns(allowedOrigins);
    }
}
