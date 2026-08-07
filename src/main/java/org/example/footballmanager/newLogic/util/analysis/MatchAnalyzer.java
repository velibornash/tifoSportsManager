package org.example.footballmanager.newLogic.util.analysis;

import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TickSnapshot;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.BallState;
import org.example.footballmanager.newLogic.model.event.MatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lightweight analyzer that computes match realism metrics from Match + MatchResult.
 * Designed to be non-invasive: reads results and tick snapshots, logs metrics.
 */
public final class MatchAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MatchAnalyzer.class);
    private static final int TICKS_PER_MINUTE = 120; // matches MatchSimulator constant

    private MatchAnalyzer() {}

    public static Map<String, Object> analyze(Match match, MatchResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (result == null) return out;

        List<MatchEvent> events = result.events() != null ? result.events() : List.of();
        List<TickSnapshot> ticks = result.tickHistory() != null ? result.tickHistory() : List.of();
        int totalTicks = result.totalTicks();
        if (totalTicks <= 0 && !ticks.isEmpty()) totalTicks = ticks.get(ticks.size()-1).tick();

        // Map playerId -> list of possession run lengths (in ticks)
        Map<Long, List<Integer>> playerRuns = new HashMap<>();
        Map<Long, Integer> playerTotalTicks = new HashMap<>();
        Map<String, Integer> teamPossessionTicks = new HashMap<>();
        teamPossessionTicks.put("HOME", 0);
        teamPossessionTicks.put("AWAY", 0);

        // possession sequences: when tick.carrierId changes we start/stop runs
        Long currentCarrier = null;
        int runStart = -1;
        String currentCarrierTeam = null;

        Map<Integer, TickSnapshot> tickByTick = ticks.stream().collect(Collectors.toMap(TickSnapshot::tick, t -> t, (a,b) -> a));

        for (TickSnapshot snap : ticks) {
            Long carrier = snap.carrierId();
            if (Objects.equals(carrier, currentCarrier)) {
                // continuing
            } else {
                // close previous run
                if (currentCarrier != null && runStart >= 0) {
                    int len = snap.tick() - runStart; // number of ticks the previous carrier held (approx)
                    playerRuns.computeIfAbsent(currentCarrier, k -> new ArrayList<>()).add(len);
                    playerTotalTicks.put(currentCarrier, playerTotalTicks.getOrDefault(currentCarrier, 0) + len);
                    if (currentCarrierTeam != null) teamPossessionTicks.put(currentCarrierTeam, teamPossessionTicks.getOrDefault(currentCarrierTeam, 0) + len);
                }
                // start new run if carrier not null
                if (carrier != null) {
                    currentCarrier = carrier;
                    runStart = snap.tick();
                    // determine team side from match teams
                    currentCarrierTeam = determineTeamSide(match, carrier);
                } else {
                    currentCarrier = null;
                    runStart = -1;
                    currentCarrierTeam = null;
                }
            }
            // accumulate per-tick when carrier present
            if (carrier != null) {
                playerTotalTicks.put(carrier, playerTotalTicks.getOrDefault(carrier, 0) + 1);
            }
        }
        // Close final run if ended by end of ticks
        if (currentCarrier != null && runStart >= 0) {
            int len = (ticks.get(ticks.size()-1).tick() + 1) - runStart;
            playerRuns.computeIfAbsent(currentCarrier, k -> new ArrayList<>()).add(len);
            playerTotalTicks.put(currentCarrier, playerTotalTicks.getOrDefault(currentCarrier, 0) + len);
            if (currentCarrierTeam != null) teamPossessionTicks.put(currentCarrierTeam, teamPossessionTicks.getOrDefault(currentCarrierTeam, 0) + len);
        }

        // Convert ticks -> seconds
        double secondsPerTick = 60.0 / TICKS_PER_MINUTE;

        // Average possession duration per player: average of run lengths converted to seconds
        Map<Long, Double> avgPossessionPerPlayerSec = new HashMap<>();
        for (var e : playerRuns.entrySet()) {
            List<Integer> runs = e.getValue();
            double avgTicks = runs.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            avgPossessionPerPlayerSec.put(e.getKey(), avgTicks * secondsPerTick);
        }

        // Average possession duration per team: overall possession seconds / number of possessions
        List<Integer> possessionLengths = new ArrayList<>();
        // Build possession lengths by scanning runs across players but grouped by contiguous team possession
        Long lastPossTeamId = null; // not used, instead use tick scan
        int possStartTick = -1;
        String possTeam = null;
        for (TickSnapshot snap : ticks) {
            String team = snap.carrierId() != null ? determineTeamSide(match, snap.carrierId()) : null;
            if (!Objects.equals(team, possTeam)) {
                if (possTeam != null && possStartTick >= 0) {
                    possessionLengths.add(snap.tick() - possStartTick);
                }
                possTeam = team;
                possStartTick = team != null ? snap.tick() : -1;
            }
        }
        if (possTeam != null && possStartTick >= 0) {
            possessionLengths.add((ticks.get(ticks.size()-1).tick() + 1) - possStartTick);
        }

        double avgPossTeamSec = possessionLengths.stream().mapToInt(Integer::intValue).average().orElse(0.0) * secondsPerTick;

        // Count event types
        Map<String, Integer> eventCounts = new TreeMap<>();
        for (MatchEvent ev : events) {
            String t = ev.type().name();
            eventCounts.put(t, eventCounts.getOrDefault(t, 0) + 1);
        }

        // Loose-ball time: ticks where carrierId == null and not ballInTransit
        int looseBallTicks = 0;
        int ballInFlightTicks = 0;
        for (TickSnapshot snap : ticks) {
            if (snap.carrierId() == null && !snap.ballInTransit()) looseBallTicks++;
            if (snap.ballInTransit() || (snap.ball() != null && snap.ball().z() > 0.1)) ballInFlightTicks++;
        }

        double looseBallPct = ticks.isEmpty() ? 0.0 : (100.0 * looseBallTicks / ticks.size());
        double ballInFlightPct = ticks.isEmpty() ? 0.0 : (100.0 * ballInFlightTicks / ticks.size());

        // Action distribution by field zone — map events to zones using tick of event
        int[] zoneCounts = new int[25];
        for (MatchEvent ev : events) {
            int tick = ev.tick();
            TickSnapshot snap = tickByTick.get(tick);
            if (snap == null) continue;
            BallState b = snap.ball();
            int zone = ballToZone(b);
            if (zone >= 0 && zone < 25) zoneCounts[zone]++;
        }

        // Build readable zone map
        Map<String, Integer> zoneMap = new LinkedHashMap<>();
        for (int i = 0; i < 25; i++) zoneMap.put("CELL_" + (i/5) + "_" + (i%5), zoneCounts[i]);

        // Carries per possession: approximate by counting possessions where no PASS/SHOT occurred and carrier ticks > 0
        int carryOnlyPossessions = 0;
        int totalPossessions = possessionLengths.size();
        // For each possession, check if any PASS/SHOT/CROSS/THROUGH in that possession ticks
        List<Integer> eventTicks = events.stream().map(MatchEvent::tick).collect(Collectors.toList());
        // This is approximate — we iterate possession boundaries and detect any passing/shot events in range
        int cursor = 0;
        possTeam = null; possStartTick = -1;
        for (TickSnapshot snap : ticks) {
            String team = snap.carrierId() != null ? determineTeamSide(match, snap.carrierId()) : null;
            if (!Objects.equals(team, possTeam)) {
                if (possTeam != null && possStartTick >= 0) {
                    int possEnd = snap.tick();
                    final int s = possStartTick;
                    final int pe = possEnd;
                    boolean hasAction = events.stream().anyMatch(ev -> ev.tick() >= s && ev.tick() < pe && (
                        ev.type() == MatchEvent.MatchEventType.PASS || ev.type() == MatchEvent.MatchEventType.SHOT_ON_TARGET || ev.type() == MatchEvent.MatchEventType.SHOT_OFF_TARGET || ev.type() == MatchEvent.MatchEventType.CROSS || ev.type() == MatchEvent.MatchEventType.THROUGH_BALL || ev.type() == MatchEvent.MatchEventType.LONG_BALL
                    ));
                    if (!hasAction) carryOnlyPossessions++;
                }
                possTeam = team;
                possStartTick = team != null ? snap.tick() : -1;
            }
        }
        if (possTeam != null && possStartTick >= 0) {
            int possEnd = ticks.get(ticks.size()-1).tick() + 1;
            final int s2 = possStartTick;
            final int pe2 = possEnd;
            boolean hasAction = events.stream().anyMatch(ev -> ev.tick() >= s2 && ev.tick() < pe2 && (
                ev.type() == MatchEvent.MatchEventType.PASS || ev.type() == MatchEvent.MatchEventType.SHOT_ON_TARGET || ev.type() == MatchEvent.MatchEventType.SHOT_OFF_TARGET || ev.type() == MatchEvent.MatchEventType.CROSS || ev.type() == MatchEvent.MatchEventType.THROUGH_BALL || ev.type() == MatchEvent.MatchEventType.LONG_BALL
            ));
            if (!hasAction) carryOnlyPossessions++;
        }

        double carriesPerPossession = totalPossessions == 0 ? 0.0 : ((double) carryOnlyPossessions) / totalPossessions;

        // Passes per possession: use total PASS events / totalPossessions
        int totalPasses = eventCounts.getOrDefault("PASS", 0) + eventCounts.getOrDefault("PASS_LONG",0) + eventCounts.getOrDefault("PASS_SHORT",0);
        double passesPerPossession = totalPossessions == 0 ? 0.0 : ((double) totalPasses) / totalPossessions;

        // Shots, crosses, through balls, dribbles, tackles, interceptions, clearances, throw-ins, corners, goal kicks, fouls, offsides
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("shots", eventCounts.getOrDefault("SHOT_ON_TARGET",0) + eventCounts.getOrDefault("SHOT_OFF_TARGET",0) + eventCounts.getOrDefault("SHOT_SAVED",0) + eventCounts.getOrDefault("SHOT_MISSED",0) + eventCounts.getOrDefault("SHOT_BLOCKED",0));
        summary.put("crosses", eventCounts.getOrDefault("CROSS",0));
        summary.put("through_balls", eventCounts.getOrDefault("THROUGH_BALL",0) + eventCounts.getOrDefault("LONG_BALL",0));
        summary.put("dribbles", eventCounts.getOrDefault("DRIBBLE",0));
        summary.put("tackles", eventCounts.getOrDefault("TACKLE",0));
        summary.put("interceptions", eventCounts.getOrDefault("INTERCEPTION",0));
        summary.put("clearances", eventCounts.getOrDefault("CLEARANCE",0));
        summary.put("throw_ins", eventCounts.getOrDefault("THROW_IN",0));
        summary.put("corners", eventCounts.getOrDefault("CORNER",0));
        summary.put("goal_kicks", eventCounts.getOrDefault("GOAL_KICK",0));
        summary.put("fouls", eventCounts.getOrDefault("FOUL",0));
        summary.put("offsides", eventCounts.getOrDefault("OFFSIDE",0));

        // Build final output
        out.put("matchId", result.matchId());
        out.put("homeGoals", result.homeGoals());
        out.put("awayGoals", result.awayGoals());
        out.put("totalTicks", totalTicks);
        out.put("totalSeconds", totalTicks * secondsPerTick);
        out.put("ticksPerMinute", TICKS_PER_MINUTE);

        out.put("avgPossessionDurationPerTeamSec", avgPossTeamSec);
        out.put("avgPossessionDurationPerPlayerSec", avgPossessionPerPlayerSec);
        out.put("possessionCount", totalPossessions);
        out.put("carriesPerPossessionApprox", carriesPerPossession);
        out.put("passesPerPossession", passesPerPossession);
        out.put("eventCounts", eventCounts);
        out.put("summary", summary);
        out.put("looseBallTicks", looseBallTicks);
        out.put("looseBallPct", looseBallPct);
        out.put("ballInFlightTicks", ballInFlightTicks);
        out.put("ballInFlightPct", ballInFlightPct);
        out.put("actionByZone", zoneMap);

        // Log a concise human-readable report
        StringBuilder report = new StringBuilder();
        report.append("=== Match Analyzer Report (matchId=").append(result.matchId()).append(") ===\n");
        report.append("Score: ").append(result.homeGoals()).append(" - ").append(result.awayGoals()).append("\n");
        report.append(String.format("Possessions: %d, Avg poss (team): %.2fs\n", totalPossessions, avgPossTeamSec));
        report.append(String.format("Loose ball: %d ticks (%.2f%%), Ball in flight: %d ticks (%.2f%%)\n", looseBallTicks, looseBallPct, ballInFlightTicks, ballInFlightPct));
        report.append("Event summary: " + summary + "\n");
        report.append("Avg possession per player (sec): sample_count=" + avgPossessionPerPlayerSec.size() + "\n");
        // list top 5 players by possession time
        List<Map.Entry<Long,Integer>> byPoss = playerTotalTicks.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue(), a.getValue())).limit(8).toList();
        for (var e : byPoss) {
            double secs = e.getValue() * secondsPerTick;
            report.append(String.format("Player %d: %.2fs total (avg run %.2fs)\n", e.getKey(), secs, avgPossessionPerPlayerSec.getOrDefault(e.getKey(), 0.0)));
        }
        report.append("Action distribution by 5x5 cell: " + zoneMap + "\n");

        log.info(report.toString());

        return out;
    }

    private static String determineTeamSide(Match match, Long playerId) {
        if (match == null || playerId == null) return null;
        try {
            if (match.homeTeam() != null && match.homeTeam().startingXI() != null) {
                for (var p : match.homeTeam().startingXI()) if (p.id() == playerId) return "HOME";
            }
            if (match.awayTeam() != null && match.awayTeam().startingXI() != null) {
                for (var p : match.awayTeam().startingXI()) if (p.id() == playerId) return "AWAY";
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int ballToZone(BallState b) {
        if (b == null) return -1;
        double minX = 4.0, maxX = 96.0, minY = 6.0, maxY = 94.0;
        double spanX = maxX - minX; double spanY = maxY - minY;
        int px = (int) Math.floor(((b.x() - minX) / spanX) * 5.0);
        int py = (int) Math.floor(((b.y() - minY) / spanY) * 5.0);
        if (px < 0) px = 0; if (px > 4) px = 4; if (py < 0) py = 0; if (py > 4) py = 4;
        return px * 5 + py;
    }
}
