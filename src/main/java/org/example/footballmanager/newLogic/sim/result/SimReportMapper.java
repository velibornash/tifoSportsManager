package org.example.footballmanager.newLogic.sim.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.EventEntry;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.PlayerOutcome;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.TeamOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@link ProposalMatchOutcome} onto the canonical newLogic match/report
 * payloads: the statsJson map (key-set identical to MatchTeamStats.toMap so the
 * existing /api/zox report reads it verbatim), the report eventJson (timeline
 * keys consumed by ZoxApiController.buildTimeline) and the lineupJson.
 */
public final class SimReportMapper {

    private static final int TICKS_PER_MINUTE = 40;

    private SimReportMapper() {}

    public static Map<String, Object> statsMap(ProposalMatchOutcome o) {
        TeamOutcome home = o.homeStats();
        TeamOutcome away = o.awayStats();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("homePossession", round1(o.possessionHome()));
        stats.put("awayPossession", round1(o.possessionAway()));
        stats.put("homeExpectedGoals", round1(o.expectedGoalsHome()));
        stats.put("awayExpectedGoals", round1(o.expectedGoalsAway()));
        stats.put("homeShotsOnTarget", onTarget(home));
        stats.put("awayShotsOnTarget", onTarget(away));
        stats.put("homeShotsOffTarget", offTarget(home));
        stats.put("awayShotsOffTarget", offTarget(away));
        stats.put("homePassAccuracy", pct(home));
        stats.put("awayPassAccuracy", pct(away));
        stats.put("homeCorners", val(home, TeamOutcome::corners));
        stats.put("awayCorners", val(away, TeamOutcome::corners));
        stats.put("homeOffsides", val(home, TeamOutcome::offsides));
        stats.put("awayOffsides", val(away, TeamOutcome::offsides));
        stats.put("homeYellowCards", val(home, TeamOutcome::yellowCards));
        stats.put("awayYellowCards", val(away, TeamOutcome::yellowCards));
        stats.put("homeRedCards", val(home, TeamOutcome::redCards));
        stats.put("awayRedCards", val(away, TeamOutcome::redCards));
        stats.put("homePenalties", val(home, TeamOutcome::penalties));
        stats.put("awayPenalties", val(away, TeamOutcome::penalties));
        stats.put("homeFouls", val(home, TeamOutcome::fouls));
        stats.put("awayFouls", val(away, TeamOutcome::fouls));
        stats.put("homeDominance", round1(clamp(o.possessionHome())));
        stats.put("awayDominance", round1(clamp(o.possessionAway())));
        return stats;
    }

    public static String eventJson(ObjectMapper om, ProposalMatchOutcome o) {
        List<Map<String, Object>> eventList = new ArrayList<>();
        for (EventEntry ev : o.events()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tick", ev.tick());
            m.put("minute", (int) (ev.tick() / TICKS_PER_MINUTE));
            m.put("type", ev.type());
            m.put("teamSide", ev.team());
            m.put("playerId", ev.playerId());
            m.put("playerName", ev.playerName());
            if (ev.targetPlayerId() != null) m.put("targetPlayerId", ev.targetPlayerId());
            if ("GOAL".equals(ev.type())) {
                m.put("scorerName", ev.playerName());
                putIfNotNull(m, "assistantId", ev.assistantId());
                putIfNotNull(m, "assistantName", ev.assistantName());
                putIfNotNull(m, "homeScoreAfter", ev.homeScoreAfter());
                putIfNotNull(m, "awayScoreAfter", ev.awayScoreAfter());
            }
            putIfNotNull(m, "cardType", ev.cardType());
            putIfNotNull(m, "penaltyFoul", ev.penaltyFoul());
            putIfNotNull(m, "takerId", ev.takerId());
            putIfNotNull(m, "takerName", ev.takerName());
            putIfNotNull(m, "varType", ev.varType());
            putIfNotNull(m, "varDecision", ev.varDecision());
            m.put("description", ev.description());
            eventList.add(m);
        }
        return write(om, eventList, "[]");
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    public static String lineupJson(ObjectMapper om, ProposalMatchOutcome o) {
        Map<String, Object> lineup = new LinkedHashMap<>();
        lineup.put("homeLineup", lineupFor(o, o.homeTeam()));
        lineup.put("awayLineup", lineupFor(o, o.awayTeam()));
        return write(om, lineup, "{}");
    }

    private static List<Map<String, Object>> lineupFor(ProposalMatchOutcome o, String teamName) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (teamName == null) return list;
        for (PlayerOutcome p : o.players()) {
            if (!teamName.equals(p.teamName())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.playerId());
            m.put("name", p.playerName());
            m.put("position", p.role());
            m.put("rating", p.rating());
            list.add(m);
        }
        return list;
    }

    private static String write(ObjectMapper om, Object value, String fallback) {
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int val(TeamOutcome t, java.util.function.ToIntFunction<TeamOutcome> fn) {
        return t == null ? 0 : fn.applyAsInt(t);
    }

    private static int onTarget(TeamOutcome t) {
        return t == null ? 0 : Math.max(0, t.shotsOnTarget());
    }

    private static int offTarget(TeamOutcome t) {
        if (t == null) return 0;
        return Math.max(0, t.shots() - onTarget(t));
    }

    private static double pct(TeamOutcome t) {
        if (t == null || t.passesAttempted() <= 0) return 0.0;
        return Math.round(1000.0 * t.passesCompleted() / t.passesAttempted()) / 10.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(100.0, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}