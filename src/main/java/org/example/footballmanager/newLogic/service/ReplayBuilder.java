package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.dto.ReplayChunkDTO;
import org.example.footballmanager.newLogic.dto.ReplayMetadataDTO;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.model.event.*;

import java.util.*;

public final class ReplayBuilder {

    private static final int CHUNK_SIZE = 120;

    public ReplayMetadataDTO buildMetadata(long matchId, Match match, MatchResult result) {
        List<Map<String, Object>> eventSummaries = result.events().stream()
            .filter(e -> !(e instanceof MatchStartEvent) && !(e instanceof MatchEndEvent))
            .map(e -> summarizeEvent(e, match))
            .toList();

        List<Map<String, Object>> goalSummaries = result.events().stream()
            .filter(e -> e instanceof GoalEvent)
            .map(e -> summarizeEvent(e, match))
            .toList();

        long totalDurationMs = (long) result.totalTicks() * 60_000L / Math.max(1, result.ticksPerMinute());
        int chunkCount = Math.max(1, (int) Math.ceil((double) result.totalTicks() / CHUNK_SIZE));

        List<Map<String, Object>> players = new ArrayList<>();
        collectPlayers(match.homeTeam(), true, players);
        collectPlayers(match.awayTeam(), false, players);

        String homeFormation = match.getHomeFormation() != null ? match.getHomeFormation() : "4-3-3";
        String awayFormation = match.getAwayFormation() != null ? match.getAwayFormation() : "4-3-3";

        return new ReplayMetadataDTO(
            matchId,
            result.totalTicks(),
            90,
            result.ticksPerMinute(),
            match.homeTeam().name(),
            match.awayTeam().name(),
            result.homeGoals(),
            result.awayGoals(),
            eventSummaries,
            "READY",
            totalDurationMs,
            60_000,
            chunkCount,
            homeFormation,
            awayFormation,
            players,
            goalSummaries,
            eventSummaries
        );
    }

    private void collectPlayers(Team team, boolean isHome, List<Map<String, Object>> out) {
        if (team == null) return;
        List<Player> squad = team.getPlayers();
        if (squad == null) return;

        List<Player> starters = team.startingXI() != null ? team.startingXI() : squad.subList(0, Math.min(11, squad.size()));
        for (Player p : starters) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("playerId", p.getId());
            map.put("id", p.getId());
            map.put("name", p.getName());
            map.put("position", p.getPosition() != null ? p.getPosition().name() : "N/A");
            map.put("squadNumber", p.getSquadNumber());
            map.put("teamSide", isHome ? "HOME" : "AWAY");
            map.put("is_home", isHome);
            map.put("starter", true);
            map.put("is_starter", true);
            out.add(map);
        }

        List<Player> bench = team.substitutes() != null ? team.substitutes() : List.of();
        for (Player p : bench) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("playerId", p.getId());
            map.put("id", p.getId());
            map.put("name", p.getName());
            map.put("position", p.getPosition() != null ? p.getPosition().name() : "N/A");
            map.put("squadNumber", p.getSquadNumber());
            map.put("teamSide", isHome ? "HOME" : "AWAY");
            map.put("is_home", isHome);
            map.put("starter", false);
            map.put("is_starter", false);
            out.add(map);
        }
    }

    public List<ReplayChunkDTO> buildChunks(long matchId, MatchResult result) {
        List<ReplayChunkDTO> chunks = new ArrayList<>();
        List<TickSnapshot> ticks = result.tickHistory();
        int totalTicks = ticks.size();
        int ticksPerMinute = result.ticksPerMinute();

        List<Map<String, Object>> allEventSummaries = result.events().stream()
            .filter(e -> !(e instanceof MatchStartEvent) && !(e instanceof MatchEndEvent))
            .map(e -> summarizeEvent(e, null))
            .toList();

        for (int i = 0; i < totalTicks; i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, totalTicks);
            List<TickSnapshot> chunkTicks = ticks.subList(i, end);
            int startTick = ticks.get(i).tick();
            int endTick = ticks.get(end - 1).tick();

            Map<String, List<Map<String, Object>>> playerSeries = new LinkedHashMap<>();
            List<Map<String, Object>> ballSeries = new ArrayList<>();
            List<Map<String, Object>> eventSeries = new ArrayList<>();

            for (TickSnapshot tick : chunkTicks) {
                long timestampMs = (long) tick.tick() * 60_000L / Math.max(1, ticksPerMinute);

                for (PlayerSnapshot ps : tick.players()) {
                    String key = String.valueOf(ps.playerId());
                    playerSeries.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(buildPoint(timestampMs, ps.x(), ps.y()));
                }

                BallState ball = tick.ball();
                if (ball != null) {
                    Map<String, Object> ballPoint = new LinkedHashMap<>();
                    ballPoint.put("timestamp", timestampMs);
                    ballPoint.put("x", ball.x());
                    ballPoint.put("y", ball.y());
                    ballPoint.put("z", ball.z());
                    if (tick.carrierId() != null) {
                        ballPoint.put("carrierPlayerId", tick.carrierId());
                    }
                    ballPoint.put("ballInTransit", tick.ballInTransit());
                    ballSeries.add(ballPoint);
                }
            }

            int finalStartTick = startTick;
            int finalEndTick = endTick;
            eventSeries.addAll(allEventSummaries.stream()
                .filter(ev -> {
                    Object tickVal = ev.get("tick");
                    if (tickVal instanceof Number n) {
                        int t = n.intValue();
                        return t >= finalStartTick && t <= finalEndTick;
                    }
                    Object tsVal = ev.get("timestamp");
                    if (tsVal instanceof Number tsNum) {
                        long tsMs = tsNum.longValue();
                        long startMs = (long) startTick * 60_000L / Math.max(1, ticksPerMinute);
                        long endMs = (long) endTick * 60_000L / Math.max(1, ticksPerMinute);
                        return tsMs >= startMs && tsMs <= endMs;
                    }
                    return false;
                })
                .toList());

            chunks.add(new ReplayChunkDTO(
                matchId,
                chunks.size(),
                startTick,
                endTick,
                playerSeries,
                ballSeries,
                eventSeries
            ));
        }

        return chunks;
    }

    private Map<String, Object> buildPoint(long timestampMs, double x, double y) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("timestamp", timestampMs);
        point.put("x", x);
        point.put("y", y);
        return point;
    }

    private Map<String, Object> summarizeEvent(MatchEvent event, Match match) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minute", event.minute());
        map.put("tick", event.tick());

        long timestampMs = (long) event.tick() * 60_000L / 120L;
        map.put("timestamp", timestampMs);

        switch (event) {
            case GoalEvent g -> {
                map.put("type", "GOAL");
                map.put("playerName", g.scorerName());
                map.put("scorerName", g.scorerName());
                map.put("teamSide", g.teamSide());
                map.put("teamName", resolveTeamName(g.teamSide(), match));
                map.put("homeScore", g.homeScoreAfter());
                map.put("awayScore", g.awayScoreAfter());
                map.put("homeGoals", g.homeScoreAfter());
                map.put("awayGoals", g.awayScoreAfter());
            }
            case ShotEvent s -> {
                map.put("type", s.onTarget() ? (s.saved() ? "SHOT_SAVED" : "SHOT_ON_TARGET") : "SHOT_MISSED");
                map.put("playerName", s.shooterName());
                map.put("shooterName", s.shooterName());
                map.put("teamSide", s.teamSide());
                map.put("xG", s.xG());
            }
            case PassEvent p -> {
                map.put("type", p.intercepted() ? "INTERCEPTION" : "PASS");
                map.put("playerName", p.passerName());
                map.put("passerName", p.passerName());
                map.put("targetPlayerName", p.receiverName());
                map.put("receiverName", p.receiverName());
                map.put("teamSide", p.teamSide());
            }
            case FoulEvent f -> {
                map.put("type", "FOUL");
                map.put("playerName", f.takerName());
                map.put("takerName", f.takerName());
                map.put("secondaryPlayerName", f.victimName());
                map.put("victimName", f.victimName());
                map.put("teamSide", f.teamSide());
                map.put("penaltyFoul", f.penaltyFoul());
            }
            case CardEvent c -> {
                map.put("type", c.cardType() == CardEvent.CardType.YELLOW ? "YELLOW_CARD" : "RED_CARD");
                map.put("playerName", c.playerName());
                map.put("teamSide", c.teamSide());
            }
            case OffsideEvent o -> {
                map.put("type", "OFFSIDE");
                map.put("playerName", o.playerName());
                map.put("teamSide", o.teamSide());
            }
            case SetPieceEvent sp -> {
                map.put("type", sp.setPieceType().name());
                map.put("teamSide", sp.teamSide());
                map.put("playerName", sp.takerName());
                map.put("takerName", sp.takerName());
            }
            case DuelEvent d -> {
                map.put("type", "DUEL");
                map.put("playerName", d.player1Name());
                map.put("player1Name", d.player1Name());
                map.put("secondaryPlayerName", d.player2Name());
                map.put("player2Name", d.player2Name());
                map.put("duelType", d.duelType());
            }
            case InjuryEvent i -> {
                map.put("type", "INJURY");
                map.put("playerName", i.playerName());
                map.put("teamSide", i.teamSide());
            }
            case SubstitutionEvent s -> {
                map.put("type", "SUBSTITUTION");
                map.put("playerOutName", s.playerOutName());
                map.put("playerInName", s.playerInName());
                map.put("teamSide", s.teamSide());
            }
            case PenaltyEvent p -> {
                map.put("type", "PENALTY");
                map.put("playerName", p.takerName());
                map.put("takerName", p.takerName());
                map.put("teamSide", p.teamSide());
                map.put("scored", p.scored());
            }
            default -> map.put("type", event.type().name());
        }

        return map;
    }

    private String resolveTeamName(String teamSide, Match match) {
        if (match == null) return teamSide;
        if ("HOME".equals(teamSide) && match.homeTeam() != null) return match.homeTeam().name();
        if ("AWAY".equals(teamSide) && match.awayTeam() != null) return match.awayTeam().name();
        return teamSide;
    }
}
