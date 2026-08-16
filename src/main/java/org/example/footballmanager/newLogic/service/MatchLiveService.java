package org.example.footballmanager.newLogic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.LiveTickDTO;
import org.example.footballmanager.newLogic.engine.MatchSimulator;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MatchLiveService {

    private final Map<Long, MatchLiveSession> liveSessions = new ConcurrentHashMap<>();
    private final MatchStore store;
    private final ObjectMapper objectMapper;
    private final org.example.commonmanager.util.websocket.MatchEventWSHandler wsHandler;
    private final MatchPersistenceService persistenceService;

    public MatchLiveService(MatchStore store, ObjectMapper objectMapper,
                            org.example.commonmanager.util.websocket.MatchEventWSHandler wsHandler,
                            MatchPersistenceService persistenceService) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.wsHandler = wsHandler;
        this.persistenceService = persistenceService;
    }

    public MatchLiveSession startLiveMatch(long matchId) {
        Match match = store.getMatch(matchId);
        if (match == null) {
            throw new IllegalArgumentException("Match not found: " + matchId);
        }

        MatchSimulator simulator = new MatchSimulator();
        MatchLiveSession session = new MatchLiveSession(matchId, match, simulator);
        liveSessions.put(matchId, session);

        return session;
    }

    public Optional<MatchLiveSession> getSession(long matchId) {
        return Optional.ofNullable(liveSessions.get(matchId));
    }

    @Async
    public void runLiveMatch(long matchId, int ticksPerBroadcast) {
        MatchLiveSession session = liveSessions.get(matchId);
        if (session == null) {
            log.warn("No live session for match {}", matchId);
            return;
        }

        Match match = session.match;
        MatchSimulator simulator = session.simulator;

        simulator.initializeSystems(match);
        simulator.initializeMatchState(match);

        simulator.getState().addEvent(new org.example.footballmanager.newLogic.model.event.MatchStartEvent(
            0, 0, match.homeTeam().name(), match.awayTeam().name()));
        simulator.getState().recordTick();
        broadcastTick(session, null);

        int totalStoppageTicks = 0;
        int firstHalfStoppageTicks = 0;

        try {
            for (int minute = 1; minute <= 90; minute++) {
                simulator.getState().minute = minute;

                for (int tick = 0; tick < 120; tick++) {
                    simulator.simulateTick();

                    if (simulator.getState().stoppage != null) {
                        simulator.getState().stoppageTicks--;
                        totalStoppageTicks++;
                        if (simulator.getState().stoppageTicks <= 0) {
                            handleStoppageEnd(simulator);
                        }
                    }

                    simulator.getState().recordTick();

                    if (tick % ticksPerBroadcast == 0) {
                        broadcastTick(session, getLatestEvent(simulator));
                    }
                }

                session.completedMinutes.set(minute);

                if (minute == 45) {
                    firstHalfStoppageTicks = totalStoppageTicks;
                    int extraPhases = Math.min(200, totalStoppageTicks / 3);
                    for (int e = 0; e < extraPhases; e++) {
                        simulator.simulateTick();
                        simulator.getState().recordTick();
                        if (e % ticksPerBroadcast == 0) {
                            broadcastTick(session, getLatestEvent(simulator));
                        }
                    }
                }

                org.example.footballmanager.newLogic.engine.FatigueSystem fatigue = getFatigue(simulator);
                if (fatigue != null) {
                    fatigue.updateFatigue(simulator.getState(), minute);
                    fatigue.maybeInjury(simulator.getState(), minute, "HOME");
                    fatigue.maybeInjury(simulator.getState(), minute, "AWAY");
                    fatigue.maybeSubstitution(simulator.getState(), minute, "HOME");
                    fatigue.maybeSubstitution(simulator.getState(), minute, "AWAY");
                }
            }

            int secondHalfStoppageTicks = totalStoppageTicks - firstHalfStoppageTicks;
            int extraPhases2 = Math.min(300, secondHalfStoppageTicks / 3);
            for (int e = 0; e < extraPhases2; e++) {
                simulator.simulateTick();
                simulator.getState().recordTick();
                if (e % ticksPerBroadcast == 0) {
                    broadcastTick(session, getLatestEvent(simulator));
                }
            }

            simulator.getState().addEvent(new org.example.footballmanager.newLogic.model.event.MatchEndEvent(
                90, simulator.getState().tick,
                simulator.getHomeGoals(), simulator.getAwayGoals()));
            simulator.getState().recordTick();

            MatchResult result = buildResult(match, simulator);
            session.setResult(result);
            store.storeResult(matchId, result);

            if (persistenceService != null) {
                try {
                    persistenceService.saveMatchResultAndUpdateTable(result, match);
                    log.info("Match {} persisted to DB and table updated", matchId);
                } catch (Exception e) {
                    log.error("Failed to persist match {}: {}", matchId, e.getMessage());
                }
            }

            broadcastTick(session, getLatestEvent(simulator));
            broadcastFinished(session, result);

        } catch (Exception e) {
            log.error("Error running live match {}: {}", matchId, e.getMessage(), e);
            session.broadcasting.set(false);
        }
    }

    private void handleStoppageEnd(MatchSimulator simulator) {
        org.example.footballmanager.newLogic.model.MatchState state = simulator.getState();
        if (state.stoppage == org.example.footballmanager.newLogic.model.MatchState.StoppageType.KICK_OFF) {
            simulator.startKickoffPlay();
        } else if (state.stoppage == org.example.footballmanager.newLogic.model.MatchState.StoppageType.PENALTY) {
            simulator.executePenalty();
        } else if (state.carrierId == null) {
            simulator.releaseBallAfterStoppage();
        }
        state.stoppage = null;
    }

    private MatchEvent getLatestEvent(MatchSimulator simulator) {
        List<MatchEvent> events = simulator.getState().events;
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    private org.example.footballmanager.newLogic.engine.FatigueSystem getFatigue(MatchSimulator simulator) {
        try {
            return simulator.getFatigueSystem();
        } catch (Exception e) {
            return null;
        }
    }

    private void broadcastTick(MatchLiveSession session, MatchEvent latestEvent) {
        if (!session.broadcasting.get()) return;

        try {
            MatchSimulator simulator = session.simulator;
            var state = simulator.getState();

            List<Map<String, Object>> players = new ArrayList<>();
            for (PlayerSnapshot snap : state.playerSnapshots) {
                double[] desired = snap.desiredPosition();
                players.add(Map.of(
                    "id", snap.playerId(),
                    "name", snap.name(),
                    "teamSide", snap.teamSide(),
                    "position", snap.position().name(),
                    "x", snap.x(),
                    "y", snap.y(),
                    "desiredX", desired[0],
                    "desiredY", desired[1],
                    "intent", snap.intent().name(),
                    "isCarrier", state.carrierId != null && state.carrierId == snap.playerId()
                ));
            }

            Map<String, Object> ball = Map.of(
                "x", state.ball.x(),
                "y", state.ball.y(),
                "z", state.ball.z(),
                "inTransit", state.ballInTransit,
                "carrierId", state.carrierId != null ? state.carrierId : 0
            );

            Map<String, Object> eventData = null;
            String eventType = null;
            if (latestEvent != null) {
                eventType = latestEvent.getClass().getSimpleName();
                eventData = Map.of(
                    "type", eventType,
                    "minute", latestEvent.minute(),
                    "tick", latestEvent.tick()
                );
            }

            String defendingTeam = state.possessionTeam != null && state.possessionTeam.equals("HOME") ? "AWAY" : "HOME";
            double offsideLineX = simulator.getOffsideLineX(defendingTeam);

            LiveTickDTO tick = new LiveTickDTO(
                session.matchId,
                state.tick,
                state.minute,
                state.homeGoals,
                state.awayGoals,
                state.possessionTeam,
                players,
                ball,
                eventType,
                eventData,
                false,
                state.offsideActive,
                offsideLineX,
                state.offsideTeam
            );

            wsHandler.broadcast(session.matchId, tick);

        } catch (Exception e) {
            log.warn("Failed to broadcast tick for match {}: {}", session.matchId, e.getMessage());
        }
    }

    private void broadcastFinished(MatchLiveSession session, MatchResult result) {
        try {
            LiveTickDTO tick = new LiveTickDTO(
                session.matchId,
                result.totalTicks(),
                90,
                result.homeGoals(),
                result.awayGoals(),
                null,
                List.of(),
                Map.of("x", 50.0, "y", 50.0, "z", 0.0, "inTransit", false, "carrierId", 0),
                "MatchEnd",
                Map.of("type", "MatchEnd", "homeGoals", result.homeGoals(), "awayGoals", result.awayGoals()),
                true,
                false,
                -1.0,
                null
            );

            wsHandler.broadcast(session.matchId, tick);
            liveSessions.remove(session.matchId);

        } catch (Exception e) {
            log.warn("Failed to broadcast match end for match {}: {}", session.matchId, e.getMessage());
        }
    }

    private MatchResult buildResult(Match match, MatchSimulator simulator) {
        var state = simulator.getState();
        double homePoss = state.homePossessionTicks > 0 || state.awayPossessionTicks > 0
            ? 100.0 * state.homePossessionTicks / (state.homePossessionTicks + state.awayPossessionTicks)
            : 50.0;
        double awayPoss = 100.0 - homePoss;

        return new MatchResult(
            match.id() != null ? match.id() : 0L,
            state.homeGoals,
            state.awayGoals,
            List.copyOf(state.events),
            List.copyOf(state.tickHistory),
            state.tick,
            120,
            homePoss,
            awayPoss,
            simulator.getHomeShots(),
            simulator.getAwayShots(),
            simulator.getHomeShotsOnTarget(),
            simulator.getAwayShotsOnTarget(),
            simulator.getHomeFouls(),
            simulator.getAwayFouls(),
            simulator.getHomeCorners(),
            simulator.getAwayCorners(),
            simulator.getHomeYellowCards(),
            simulator.getAwayYellowCards(),
            simulator.getHomeRedCards(),
            simulator.getAwayRedCards(),
            0.0,
            0.0
        );
    }
}