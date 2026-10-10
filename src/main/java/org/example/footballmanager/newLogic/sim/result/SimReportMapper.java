package org.example.footballmanager.newLogic.sim.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.footballmanager.newLogic.model.event.MatchEvent.MatchEventType;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.EventEntry;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.PlayerOutcome;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.TeamOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

        // Passes attempted, and the defensive counts (T0-UI-6).
        //
        // The engine has counted every one of these all along — they are on `TeamOutcome` and were being
        // computed on every match — and none of them reached the payload. So PPDA could not be formed from
        // a stored match at all: the numerator was a percentage and the denominator did not exist. These are
        // writes, not new measurements.
        stats.put("homePassesAttempted", val(home, TeamOutcome::passesAttempted));
        stats.put("awayPassesAttempted", val(away, TeamOutcome::passesAttempted));
        stats.put("homePassesCompleted", val(home, TeamOutcome::passesCompleted));
        stats.put("awayPassesCompleted", val(away, TeamOutcome::passesCompleted));
        stats.put("homeClearances", val(home, TeamOutcome::clearances));
        stats.put("awayClearances", val(away, TeamOutcome::clearances));
        stats.put("homeInterceptions", val(home, TeamOutcome::interceptions));
        stats.put("awayInterceptions", val(away, TeamOutcome::interceptions));
        stats.put("homeBlocks", val(home, TeamOutcome::blocks));
        stats.put("awayBlocks", val(away, TeamOutcome::blocks));
        stats.put("homeDeflections", val(home, TeamOutcome::deflections));
        stats.put("awayDeflections", val(away, TeamOutcome::deflections));
        return stats;
    }

    /**
     * The event types a page can actually show. Everything else is not written at all.
     *
     * <p><b>Why this list exists.</b> {@code event_json} used to hold every tick of the match: 2,726
     * events and <b>742 KB to 1,035 KB</b> on a single match, of which <b>1.92 entries are goals</b>.
     * Two request paths read it — {@code MatchDetailService} and {@code ZoxApiController} — and
     * <b>both already discard most of it</b>: {@code mapEventToDTO} is a {@code switch} that returns
     * null for a type it does not know, and {@code buildTimeline} only adds an item inside type
     * checks. So roughly <b>98% of every blob was parsed and thrown away</b>, and the top-scorers page
     * was parsing <b>44 MB</b> to list five goals a match. The complete per-tick log is not lost: it
     * is written to a file by {@code SimReplayStore}, which is the replay viewer's own source.
     *
     * <p><b>Derived from the readers, not from what seems tidy</b>, and the two readers do not want the
     * same things — {@code buildTimeline} wants {@code OFFSIDE} and every {@code VAR_*} entry, which
     * {@code MatchDetailService} drops. Reading only one of them would have quietly emptied part of the
     * match report.
     *
     * <p><b>A goal VAR ruled out is written but never credited.</b> {@code GOAL_DISALLOWED} and
     * {@code VAR_GOAL_OVERTURNED} stay in the blob because they are the audit trail — a match report
     * shows the overturn as a key moment, and a reader asking why a goal is missing from a scorer's total
     * deserves an answer. {@link MatchEventType#countsAsGoal} is what keeps them off the scorer's total,
     * which used to be a {@code contains("GOAL")} substring and credited all 30 of them across the
     * shipped matches. <b>Written and counted are different questions</b>, and the fix separated them
     * rather than narrowing the blob to whatever the scorer happens to read.
     */
    private static final Set<String> REPORTABLE_TYPES = Set.of(
            "GOAL",
            "YELLOW_CARD", "RED_CARD", "CARD",
            "PENALTY", "PENALTY_AWARDED", "PENALTY_GOAL",
            "SHOT", "SHOT_ON_TARGET", "SHOT_OFF_TARGET", "SHOT_SAVED", "SHOT_BLOCKED",
            "SHOT_POST", "SHOT_MISSED",
            "CORNER", "FREE_KICK", "OFFSIDE",
            "SUB", "SUBSTITUTION", "INJURY",
            "MATCH_START", "MATCH_END", "VAR", "VAR_REVIEW");

    /**
     * Whether a page can use this event, and therefore whether it is written.
     *
     * <p>Package-private so {@code SimReportMapperReportableTypesTest} can hold the list to the
     * readers' vocabulary in both directions: everything a reader wants must be kept, and everything
     * kept must be wanted. A keep-list that drifts either way fails that test rather than quietly
     * emptying a page or quietly carrying 742 KB.
     */
    static boolean isReportable(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        // A goal, or a goal VAR ruled out: both belong in the report, and the second is the reason the
        // first one's absence from a scorer's total is explicable. The shared definition is on
        // MatchEventType, because three call sites had each invented their own answer to this question.
        if (MatchEventType.isGoalRelated(type)) {
            return true;
        }
        // buildTimeline accepts anything starting with VAR_, and VAR review is a real part of a report.
        if (MatchEventType.isVarDecision(type)) {
            return true;
        }
        String normalised = type.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return REPORTABLE_TYPES.contains(normalised);
    }

    public static String eventJson(ObjectMapper om, ProposalMatchOutcome o) {
        List<Map<String, Object>> eventList = new ArrayList<>();
        for (EventEntry ev : o.events()) {
            if (!isReportable(ev.type())) {
                continue;
            }
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