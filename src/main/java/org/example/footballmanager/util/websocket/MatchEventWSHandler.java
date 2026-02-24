package org.example.footballmanager.util.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MatchEventWSHandler extends TextWebSocketHandler {
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public MatchEventWSHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long matchId = getMatchId(session);
        sessions
                .computeIfAbsent(matchId, id -> ConcurrentHashMap.newKeySet())
                .add(session);

        log.info("Nova WS sesija {} za match {}", session.getId(), matchId);
    }
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.values().forEach(set -> set.remove(session));
        log.info("Sesija zatvorena: {}", session.getId());
    }
    public void broadcast(Long matchId, Object event) {
        Set<WebSocketSession> matchSessions = sessions.get(matchId);
        if (matchSessions == null) return;

        try {
            String json = objectMapper.writeValueAsString(event);
            TextMessage msg = new TextMessage(json);

            Iterator<WebSocketSession> iterator = matchSessions.iterator();
            while (iterator.hasNext()) {
                WebSocketSession s = iterator.next();
                try {
                    if (s.isOpen()) {
                        s.sendMessage(msg);
                    } else {
                        iterator.remove();
                    }
                } catch (IOException e) {
                    log.warn("Uklanjam neaktivnu sesiju {}", s.getId());
                    iterator.remove();
                }
            }

        } catch (Exception e) {
            log.error("Broadcast error", e);
        }
    }
    private Long getMatchId(WebSocketSession session) {
        String query = session.getUri().getQuery(); // matchId=5
        if (query == null) return -1L;

        for (String param : query.split("&")) {
            if (param.startsWith("matchId=")) {
                return Long.parseLong(param.split("=")[1]);
            }
        }
        return -1L;
    }
}