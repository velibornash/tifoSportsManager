package org.example.footballmanager.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.service.DemoCombinedSimulationService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class DemoMatchEventWebSocketHandler extends TextWebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("[DEMO Position WS] Nova sesija: {}", session.getId());
    }
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("[DEMO Event WS] Sesija zatvorena: {}", session.getId());
    }

    public void broadcast(Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            TextMessage msg = new TextMessage(json);

            log.info("[EVENT BROADCAST] Šaljem → {} sesija | payload: {}",
                    sessions.size(), json.substring(0, Math.min(200, json.length())));

            int sent = 0;
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    s.sendMessage(msg);
                    sent++;
                }
            }
            log.info("[EVENT BROADCAST] Uspešno poslato u {} sesija", sent);
        } catch (JsonProcessingException e) {
            log.error("[EVENT] JSON greška", e);
        } catch (IOException e) {
            log.error("[EVENT] IO greška pri slanju", e);
        } catch (Exception e) {
            log.error("[EVENT BROADCAST ERROR]", e);
        }
    }
}