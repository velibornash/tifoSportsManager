package org.example.footballmanager.config;

import org.example.footballmanager.util.MatchEventWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final MatchEventWebSocketHandler matchEventWebSocketHandler;

    public WebSocketConfig(MatchEventWebSocketHandler matchEventWebSocketHandler) {
        this.matchEventWebSocketHandler = matchEventWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(matchEventWebSocketHandler, "/match-events")
                .setAllowedOriginPatterns("*");
    }

}