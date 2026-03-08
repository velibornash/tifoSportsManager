package org.example.footballmanager.engines;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.dto.BallPositionDTO;
import org.example.footballmanager.dto.GameStateDTO;
import org.example.footballmanager.dto.PlayerPositionDTO;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchRuntime;
import org.example.footballmanager.model.MatchTickState;
import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.MatchTickStateRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class MatchPlaybackEngine {

    private static final int DEFAULT_TICKS_PER_MINUTE = 27;
    private static final int TICK_MS = 140; // 12 actions * 6 substeps * 140ms = ~10s per minute
    private static final int SUBSTEPS_PER_TICK = 6;

    private final Map<Long, ScheduledExecutorService> schedulers = new ConcurrentHashMap<>();
    private final BroadcastEngine broadcastEngine;
    private final MatchTickStateRepository tickStateRepository;
    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;
    private final ObjectMapper objectMapper;

    public MatchRuntime initializeRuntimeAndPositions(MatchRuntime runtime) {
        Random r = new Random();
        for (int i = 1; i <= 22; i++) {
            boolean isHome = i <= 11;
            double[] base = basePositionForId(i);
            double jitterX = (r.nextDouble() - 0.5) * 2.5;
            double jitterY = (r.nextDouble() - 0.5) * 3.5;
            runtime.players.add(new PlayerPositionDTO(
                    i,
                    isHome ? "HOME" : "AWAY",
                    Math.max(0, Math.min(100, base[0] + jitterX)),
                    Math.max(0, Math.min(100, base[1] + jitterY)),
                    0,
                    0
            ));
        }
        runtime.ball = new BallPositionDTO(50, 50);
        runtime.currentCarrier = runtime.players.stream()
                .filter(p -> p.getId() == 9)
                .findFirst()
                .orElse(runtime.players.getFirst());
        runtime.currentCarrier.setX(50);
        runtime.currentCarrier.setY(50);

        runtime.players.stream()
                .filter(p -> p.getId() == 10)
                .findFirst()
                .ifPresent(p -> {
                    p.setX(47.5);
                    p.setY(50);
                });
        return runtime;
    }

    private double[] basePositionForId(int id) {
        return switch (id) {
            case 1 -> new double[]{6, 50};    // HOME GK
            case 2 -> new double[]{20, 22};   // RB
            case 3 -> new double[]{20, 78};   // LB
            case 4 -> new double[]{24, 43};   // RCB
            case 5 -> new double[]{24, 57};   // LCB
            case 6 -> new double[]{39, 43};   // CM
            case 7 -> new double[]{43, 78};   // RW
            case 8 -> new double[]{39, 57};   // CM
            case 9 -> new double[]{48, 47};   // ST
            case 10 -> new double[]{46, 53};  // ST
            case 11 -> new double[]{43, 22};  // LW
            case 12 -> new double[]{94, 50};  // AWAY GK
            case 13 -> new double[]{80, 22};  // RB
            case 16 -> new double[]{80, 78};  // LB
            case 14 -> new double[]{76, 43};  // RCB
            case 15 -> new double[]{76, 57};  // LCB
            case 17 -> new double[]{61, 43};  // CM
            case 19 -> new double[]{57, 78};  // RW
            case 18 -> new double[]{61, 57};  // CM
            case 21 -> new double[]{52, 47};  // ST
            case 22 -> new double[]{54, 53};  // ST
            case 20 -> new double[]{57, 22};  // LW
            default -> new double[]{50, 50};
        };
    }

    public void startPlayback(long matchId, MatchRuntime rt) {
        stopPlayback(matchId);

        List<MatchRuntime.TickState> frames = loadFrames(matchId, rt);
        if (frames.isEmpty()) {
            log.warn("No tick frames available for match {}. Playback not started.", matchId);
            return;
        }

        final int ticksPerMinute = rt != null && rt.ticksPerMinute > 0 ? rt.ticksPerMinute : DEFAULT_TICKS_PER_MINUTE;
        List<MatchEvent> events = loadEvents(matchId, rt, ticksPerMinute);
        Map<Integer, List<MatchEvent>> eventsByTick = indexEventsByTick(events, ticksPerMinute);
        Match match = loadMatch(matchId, rt);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        schedulers.put(matchId, scheduler);

        final int[] frameIndex = {0};
        final int[] substep = {0};
        final int[] lastBroadcastTick = {-1};

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (frameIndex[0] >= frames.size() - 1 && substep[0] == 0) {
                    stopPlayback(matchId);
                    return;
                }

                MatchRuntime.TickState current = frames.get(frameIndex[0]);
                MatchRuntime.TickState next = frames.get(Math.min(frameIndex[0] + 1, frames.size() - 1));
                double alpha = substep[0] / (double) SUBSTEPS_PER_TICK;
                MatchRuntime.TickState frame = alpha == 0.0 ? current : interpolateFrame(current, next, alpha);
                int minute = Math.min(90, frame.tick / ticksPerMinute + 1);

                Map<String, Object> state = new HashMap<>();
                state.put("matchId", matchId);
                state.put("minute", minute);
                state.put("second", minute);
                state.put("players", frame.players);
                state.put("ball", frame.ball);
                state.put("carrierPlayerId", frame.carrierId >= 0 ? frame.carrierId : null);
                broadcastEngine.positionWsHandler.broadcast(matchId, state);

                // SYNC EVENTS WITH TICKS (instead of just minutes)
                if (frame.tick != lastBroadcastTick[0]) {
                    List<MatchEvent> tickEvents = eventsByTick.getOrDefault(frame.tick, List.of());
                    if (!tickEvents.isEmpty()) {
                        broadcastEngine.broadcastMinuteEvents(matchId, tickEvents, minute);
                    }

                    lastBroadcastTick[0] = frame.tick;
                }

                substep[0]++;
                if (substep[0] >= SUBSTEPS_PER_TICK) {
                    substep[0] = 0;
                    frameIndex[0]++;
                }
            } catch (Exception e) {
                log.error("Playback error for match {}", matchId, e);
                stopPlayback(matchId);
            }
        }, 0, TICK_MS, TimeUnit.MILLISECONDS);

        log.info("Playback started for match {} with {} frames", matchId, frames.size());
    }

    private MatchRuntime.TickState interpolateFrame(MatchRuntime.TickState a, MatchRuntime.TickState b, double alpha) {
        Map<Integer, PlayerPositionDTO> nextById = new HashMap<>();
        for (PlayerPositionDTO p : b.players) {
            nextById.put(p.getId(), p);
        }

        List<PlayerPositionDTO> interpolatedPlayers = new ArrayList<>(a.players.size());
        for (PlayerPositionDTO ap : a.players) {
            PlayerPositionDTO bp = nextById.get(ap.getId());
            double nx = bp != null ? lerp(ap.getX(), bp.getX(), alpha) : ap.getX();
            double ny = bp != null ? lerp(ap.getY(), bp.getY(), alpha) : ap.getY();
            interpolatedPlayers.add(new PlayerPositionDTO(ap.getId(), ap.getTeam(), nx, ny, 0, 0));
        }

        // Validate player counts
        if (a.players.size() != 22 || b.players.size() != 22) {
            log.warn("Player count mismatch: frame A has {}, frame B has {} (expected 22 each)", 
                    a.players.size(), b.players.size());
        }

        // Ball interpolation logic
        BallPositionDTO ball;
        int carrierId;
        
        // CRITICAL: Handle ball in transit vs with carrier
        if (a.ballInTransit && !b.ballInTransit) {
            // Ball is ARRIVING at receiver between frames
            int receiverId = a.pendingReceiverId >= 0 ? a.pendingReceiverId : b.carrierId;
            
            PlayerPositionDTO receiver = b.players.stream()
                    .filter(p -> p.getId() == receiverId)
                    .findFirst()
                    .orElse(null);
            
            if (receiver != null) {
                // Smoothly interpolate ball from its position in A to receiver's position in B
                ball = new BallPositionDTO(
                        lerp(a.ball.getX(), receiver.getX(), alpha),
                        lerp(a.ball.getY(), receiver.getY(), alpha)
                );
                
                // Carrier is only set in the very last sub-tick of the transition
                carrierId = (alpha > 0.95) ? receiverId : -1;
            } else {
                ball = new BallPositionDTO(
                        lerp(a.ball.getX(), b.ball.getX(), alpha),
                        lerp(a.ball.getY(), b.ball.getY(), alpha)
                );
                carrierId = (alpha > 0.95) ? b.carrierId : -1;
            }
        } else if (a.ballInTransit || b.ballInTransit) {
            // Ball is in transit - interpolate smoothly between the two recorded ball positions
            ball = new BallPositionDTO(
                    lerp(a.ball.getX(), b.ball.getX(), alpha),
                    lerp(a.ball.getY(), b.ball.getY(), alpha)
            );
            carrierId = -1;
        } else if (alpha < 0.5 ? (a.carrierId >= 0) : (b.carrierId >= 0)) {
            // Ball is with a player in both frames or transitioning between carriers
            carrierId = (alpha < 0.5) ? a.carrierId : b.carrierId;
            
            int finalCarrierId = carrierId;
            PlayerPositionDTO carrier = interpolatedPlayers.stream()
                    .filter(p -> p.getId() == finalCarrierId)
                    .findFirst()
                    .orElse(null);
            
            if (carrier != null) {
                ball = new BallPositionDTO(carrier.getX(), carrier.getY());
            } else {
                ball = new BallPositionDTO(
                        lerp(a.ball.getX(), b.ball.getX(), alpha),
                        lerp(a.ball.getY(), b.ball.getY(), alpha)
                );
                carrierId = -1;
            }
        } else {
            // No carrier, ball is loose
            ball = new BallPositionDTO(
                    lerp(a.ball.getX(), b.ball.getX(), alpha),
                    lerp(a.ball.getY(), b.ball.getY(), alpha)
            );
            carrierId = -1;
        }

        int tick = (int) Math.round(lerp(a.tick, b.tick, alpha));

        return new MatchRuntime.TickState(tick, interpolatedPlayers, ball, carrierId, null, false, -1);
    }

    private double lerp(double from, double to, double alpha) {
        return from + (to - from) * alpha;
    }

    public void startPlayback(long matchId) {
        startPlayback(matchId, null);
    }

    public boolean awaitActiveSessions(long matchId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
        do {
            boolean hasPosition = broadcastEngine.positionWsHandler.hasActiveSessions(matchId);
            boolean hasEvents = broadcastEngine.eventWsHandler.hasActiveSessions(matchId);
            if (hasPosition && hasEvents) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < deadline);

        return broadcastEngine.positionWsHandler.hasActiveSessions(matchId)
                && broadcastEngine.eventWsHandler.hasActiveSessions(matchId);
    }

    public void stopPlayback(long matchId) {
        ScheduledExecutorService scheduler = schedulers.remove(matchId);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("Playback finished for match {}", matchId);
    }

    private List<MatchRuntime.TickState> loadFrames(long matchId, MatchRuntime rt) {
        if (rt != null && rt.tickStates != null && !rt.tickStates.isEmpty()) {
            return rt.tickStates;
        }

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        List<MatchTickState> stored = tickStateRepository.findByMatchOrderByTickAsc(match);
        if (stored.isEmpty()) {
            return List.of();
        }

        List<MatchRuntime.TickState> frames = new ArrayList<>(stored.size());
        for (MatchTickState s : stored) {
            try {
                List<PlayerPositionDTO> players = objectMapper.readValue(
                        s.getPlayerPositionsJson(),
                        new TypeReference<List<PlayerPositionDTO>>() {}
                );
                BallPositionDTO ball = objectMapper.readValue(s.getBallPositionJson(), BallPositionDTO.class);
                int carrierId = s.getCurrentCarrierId() != null ? s.getCurrentCarrierId() : -1;
                boolean ballInTransit = s.isBallInTransit();  // NEW: Load ballInTransit from DB
                int pendingReceiverId = s.getPendingReceiverId() != null ? s.getPendingReceiverId() : -1;  // NEW: Load pendingReceiverId from DB
                frames.add(new MatchRuntime.TickState(s.getTick(), players, ball, carrierId, null, ballInTransit, pendingReceiverId));
            } catch (Exception ex) {
                log.error("Failed to parse stored tick {} for match {}", s.getTick(), matchId, ex);
            }
        }

        return frames;
    }

    private List<MatchEvent> loadEvents(long matchId, MatchRuntime rt, int fallbackTicksPerMinute) {
        if (rt != null && rt.runtimeEvents != null && !rt.runtimeEvents.isEmpty()) {
            List<MatchEvent> runtimeEvents = new ArrayList<>(rt.runtimeEvents);
            runtimeEvents.sort(buildEventComparator(fallbackTicksPerMinute));
            return runtimeEvents;
        }

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        List<MatchEvent> events = matchEventRepository.findByMatch(match);
        events.sort(buildEventComparator(fallbackTicksPerMinute));
        return events;
    }

    private Comparator<MatchEvent> buildEventComparator(int fallbackTicksPerMinute) {
        return Comparator.<MatchEvent>comparingInt(
                        event -> resolveEventTick(event, fallbackTicksPerMinute))
                .thenComparingInt(MatchEvent::getMinute)
                .thenComparing(e -> e.getId() == null ? 0L : e.getId());
    }

    private Match loadMatch(long matchId, MatchRuntime rt) {
        if (rt != null && rt.matchRef != null) {
            return rt.matchRef;
        }
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
    }

    private Map<Integer, List<MatchEvent>> indexEventsByTick(List<MatchEvent> events, int fallbackTicksPerMinute) {
        Map<Integer, List<MatchEvent>> byTick = new HashMap<>();
        for (MatchEvent event : events) {
            byTick.computeIfAbsent(resolveEventTick(event, fallbackTicksPerMinute), k -> new ArrayList<>()).add(event);
        }
        return byTick;
    }

    private int resolveEventTick(MatchEvent event, int fallbackTicksPerMinute) {
        if (event == null) {
            return 0;
        }
        if (event.getTick() > 0) {
            return event.getTick();
        }
        int safeTicksPerMinute = fallbackTicksPerMinute > 0 ? fallbackTicksPerMinute : DEFAULT_TICKS_PER_MINUTE;
        return Math.max(0, event.getMinute() * safeTicksPerMinute);
    }

    private String getTeamInPossession(MatchRuntime.TickState frame, Map<String, String> teamNames) {
        if (frame == null || frame.players == null || frame.players.isEmpty()) {
            return "In transition";
        }

        return frame.players.stream()
                .filter(p -> p.getId() == frame.carrierId)
                .map(p -> teamNames.getOrDefault(p.getTeam(), p.getTeam()))
                .findFirst()
                .orElse("In transition");
    }

    private Map<String, String> resolveTeamNames(Match match, List<MatchEvent> events) {
        String homeName = match != null && match.getHomeTeam() != null ? match.getHomeTeam().getName() : null;
        String awayName = match != null && match.getAwayTeam() != null ? match.getAwayTeam().getName() : null;

        if ((homeName == null || awayName == null) && events != null) {
            for (MatchEvent event : events) {
                if (event instanceof org.example.footballmanager.model.event.MatchStartEvent startEvent) {
                    if (homeName == null) {
                        homeName = startEvent.getHomeTeamName();
                    }
                    if (awayName == null) {
                        awayName = startEvent.getAwayTeamName();
                    }
                }
            }
        }

        Map<String, String> names = new HashMap<>();
        names.put("HOME", homeName != null ? homeName : "Home");
        names.put("AWAY", awayName != null ? awayName : "Away");
        return names;
    }

    private Map<Integer, Player> mapPlayersByPositionId(Match match) {
        Map<Integer, Player> result = new HashMap<>();
        if (match == null) {
            return result;
        }

        List<Player> home = match.getHomeLineup() != null ? match.getHomeLineup().getOrderedStartingPlayers() : List.of();
        List<Player> away = match.getAwayLineup() != null ? match.getAwayLineup().getOrderedStartingPlayers() : List.of();

        for (int i = 0; i < Math.min(11, home.size()); i++) {
            result.put(i + 1, home.get(i));
        }
        for (int i = 0; i < Math.min(11, away.size()); i++) {
            result.put(i + 12, away.get(i));
        }

        return result;
    }

    private Player getCarrierPlayer(MatchRuntime.TickState frame, Map<Integer, Player> playersByPositionId) {
        if (frame == null || frame.carrierId < 0) {
            return null;
        }
        return playersByPositionId.get(frame.carrierId);
    }
}
