package org.example.footballmanager.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.util.events.MatchEventMapper;
import org.example.footballmanager.util.websocket.MatchEventWSHandler;
import org.example.footballmanager.util.websocket.PositionWSHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@Slf4j
@RequiredArgsConstructor
public class BroadcastEngine {

    public final PositionWSHandler positionWsHandler;
    public final MatchEventWSHandler eventWsHandler;
    private final MatchEventMapper mapper;

    public void broadcastState(long matchId, MatchRuntime rt) {
        // KLJUČNA PROVERA: da li ima ikoga ko gleda?
        if (!positionWsHandler.hasActiveSessions(matchId)) {
            // log.debug("Nema aktivnih sesija za match {} – preskačem broadcast pozicija", matchId);
            return;
        }

        GameStateDTO state = new GameStateDTO(
                rt.tick / 10,
                new ArrayList<>(rt.players),
                rt.ball
        );

        positionWsHandler.broadcast(matchId, state);
    }

    public void broadcastMinuteEvents(long matchId, MatchRuntime rt, int minute) {
        // KLJUČNA PROVERA: da li ima ikoga ko gleda?
        if (!eventWsHandler.hasActiveSessions(matchId)) {
            // log.debug("Nema aktivnih sesija za match {} – preskačem broadcast eventa", matchId);
            return;
        }

        rt.runtimeEvents.stream()
                .filter(e -> e.getMinute() == minute)
                .forEach(e -> {
                    log.info("[{}'] Event: {}", minute, e.getDescription());
                    eventWsHandler.broadcast(
                            matchId,
                            mapper.toDto(e)
                    );
                });
    }
}