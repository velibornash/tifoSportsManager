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
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long matchId = getMatchId(session);
        if (matchId == null || matchId <= 0) {
            log.warn("Nevalidan matchId u URL-u – zatvaram sesiju {}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        sessions.computeIfAbsent(matchId, id -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("Nova WS sesija {} za match {} – aktivnih sesija: {}",
                session.getId(), matchId, sessions.get(matchId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long matchId = getMatchId(session);
        if (matchId != null) {
            Set<WebSocketSession> matchSessions = sessions.get(matchId);
            if (matchSessions != null) {
                matchSessions.remove(session);
                if (matchSessions.isEmpty()) {
                    sessions.remove(matchId);
                    log.info("Poslednja sesija zatvorena za match {} – mapa uklonjena", matchId);
                }
            }
        }
        log.info("Sesija zatvorena: {} (status: {})", session.getId(), status);
    }

    public void broadcast(Long matchId, Object event) {
        Set<WebSocketSession> matchSessions = sessions.get(matchId);
        if (matchSessions == null || matchSessions.isEmpty()) {
            // log.debug("Nema aktivnih sesija za match {} – preskačem broadcast eventa", matchId);
            return;
        }

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
                    log.warn("Uklanjam neaktivnu sesiju {} za match {}", s.getId(), matchId);
                    iterator.remove();
                }
            }

            if (matchSessions.isEmpty()) {
                sessions.remove(matchId);
            }

        } catch (Exception e) {
            log.error("Broadcast error za match {}", matchId, e);
        }
    }

    public boolean hasActiveSessions(Long matchId) {
        Set<WebSocketSession> matchSessions = sessions.get(matchId);
        return matchSessions != null && !matchSessions.isEmpty();
    }

    private Long getMatchId(WebSocketSession session) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) return null;

        for (String param : query.split("&")) {
            if (param.startsWith("matchId=")) {
                try {
                    return Long.parseLong(param.split("=")[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}