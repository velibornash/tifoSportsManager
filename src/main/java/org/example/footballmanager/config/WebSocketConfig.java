package org.example.footballmanager.config;

import org.example.footballmanager.util.websocket.MatchEventWSHandler;
import org.example.footballmanager.util.websocket.PositionWSHandler;
import org.example.footballmanager.util.old.MatchEventWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final MatchEventWebSocketHandler matchEventHandler;
    private final PositionWSHandler demoPositionHandler;
    private final MatchEventWSHandler demoEventHandler;

    public WebSocketConfig(
            MatchEventWebSocketHandler matchEventHandler,
            @Lazy PositionWSHandler demoPositionHandler,
            @Lazy MatchEventWSHandler demoEventHandler) {
        this.matchEventHandler = matchEventHandler;
        this.demoPositionHandler = demoPositionHandler;
        this.demoEventHandler = demoEventHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(matchEventHandler, "/match-events").setAllowedOriginPatterns("*");
        registry.addHandler(demoPositionHandler, "/demo-position-updates").setAllowedOrigins("*");
        registry.addHandler(demoEventHandler, "/demo-match-events").setAllowedOrigins("*");
    }
}