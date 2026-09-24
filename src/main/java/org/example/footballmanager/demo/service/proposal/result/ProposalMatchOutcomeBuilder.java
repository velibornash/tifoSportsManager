package org.example.footballmanager.demo.service.proposal.result;

import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.model.MatchState;
import org.example.footballmanager.demo.service.proposal.model.Player;
import org.example.footballmanager.demo.service.proposal.recording.MatchEvent;
import org.example.footballmanager.demo.service.proposal.recording.MatchRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds a {@link ProposalMatchOutcome} from a finished {@link MatchOrchestrator}.
 * Derives the report items the engine does not track directly:
 *   - expected goals (per-shot distance table, same bands as newLogic)
 *   - offsides (recorder OFFSIDE events — the collector has no count yet)
 *   - formations (derived from role counts)
 * Fouls / cards stay 0 until DisciplineService is wired into the orchestrator.
 */
public class ProposalMatchOutcomeBuilder {

    public static final int TICKS_PER_MINUTE = 40;

    private static final Set<String> SHOT_OUTCOME_TYPES = Set.of(
            "GOAL", "SHOT_SAVED", "SHOT_BLOCKED", "SHOT_POST", "SHOT_MISSED");

    public ProposalMatchOutcome build(MatchOrchestrator orchestrator) {
        MatchState state = orchestrator.getState();
        MatchRecorder recorder = orchestrator.getRecorder();
        ProposalStatsCollector stats = orchestrator.getStats();

        String homeName = stats.getHomeName();
        String awayName = stats.getAwayName();

        int[] offsides = countOffsides(recorder);
        ProposalMatchOutcome.TeamOutcome home = toTeamOutcome(stats, "HOME", offsides[0]);
        ProposalMatchOutcome.TeamOutcome away = toTeamOutcome(stats, "AWAY", offsides[1]);

        List<ProposalMatchOutcome.PlayerOutcome> homePlayers = toPlayerOutcomes(stats, "HOME");
        List<ProposalMatchOutcome.PlayerOutcome> awayPlayers = toPlayerOutcomes(stats, "AWAY");
        List<ProposalMatchOutcome.PlayerOutcome> allPlayers = new ArrayList<>();
        allPlayers.addAll(homePlayers);
        allPlayers.addAll(awayPlayers);

        double[] expected = computeExpectedGoals(recorder);
        String motmId = null;
        String motmName = null;
        String motmTeam = null;
        double motmRating = -1.0;
        for (ProposalMatchOutcome.PlayerOutcome po : allPlayers) {
            if (po.rating() > motmRating) {
                motmRating = po.rating();
                motmId = po.playerId();
                motmName = po.playerName();
                motmTeam = po.teamName();
            }
        }

        List<ProposalMatchOutcome.EventEntry> events = new ArrayList<>();
        long matchTicks = state.getMatchTicks();
        for (MatchEvent ev : recorder.getEvents()) {
            events.add(new ProposalMatchOutcome.EventEntry(
                    ev.getTick(),
                    (int) (ev.getTick() / TICKS_PER_MINUTE),
                    ev.getType(),
                    ev.getTeam(),
                    ev.getPlayerId(),
                    ev.getPlayerName(),
                    ev.getDescription()));
        }

        return new ProposalMatchOutcome(
                homeName, awayName,
                state.getHomeGoals(), state.getAwayGoals(),
                matchTicks,
                (int) (matchTicks / TICKS_PER_MINUTE),
                deriveFormation(state, "HOME"), deriveFormation(state, "AWAY"),
                round1(stats.getHomePossessionPct()),
                round1(100.0 - stats.getHomePossessionPct()),
                expected[0], expected[1],
                home, away,
                allPlayers,
                events,
                motmId, motmName, motmTeam
        );
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private ProposalMatchOutcome.TeamOutcome toTeamOutcome(ProposalStatsCollector stats, String side, int offsides) {
        TeamStats ts = stats.buildTeamStats(side);
        if (ts == null) return null;
        double avgRating = averageRating(stats, side);
        return new ProposalMatchOutcome.TeamOutcome(
                ts.teamName(), ts.goals(), ts.shots(), ts.shotsOnTarget(),
                ts.passesAttempted(), ts.passesCompleted(),
                ts.dribbles(), ts.clearances(), ts.interceptions(), ts.deflections(),
                ts.blocks(), ts.saves(), ts.corners(), ts.goalKicks(), ts.throwIns(),
                offsides, ts.fouls(), ts.yellowCards(), ts.redCards(),
                ts.possessionPercent(), avgRating
        );
    }

    private List<ProposalMatchOutcome.PlayerOutcome> toPlayerOutcomes(ProposalStatsCollector stats, String side) {
        List<ProposalMatchOutcome.PlayerOutcome> out = new ArrayList<>();
        for (PlayerStats ps : stats.buildPlayerStats(side)) {
            out.add(new ProposalMatchOutcome.PlayerOutcome(
                    ps.playerId(), ps.playerName(), ps.teamName(), ps.role(),
                    ps.goals(), ps.assists(), ps.shots(), ps.shotsOnTarget(),
                    ps.passesAttempted(), ps.passesCompleted(),
                    ps.dribbles(), ps.clearances(), ps.interceptions(), ps.deflections(),
                    ps.blocks(), ps.saves(), ps.tackles(), ps.duelsWon(),
                    ps.foulsCommitted(), ps.yellowCards(), ps.redCards(),
                    ps.minutesPlayed(), ps.rating()
            ));
        }
        return out;
    }

    private int[] countOffsides(MatchRecorder recorder) {
        int home = 0, away = 0;
        for (MatchEvent ev : recorder.getEvents()) {
            if (!"OFFSIDE".equals(ev.getType())) continue;
            if ("HOME".equals(ev.getTeam())) home++;
            else if ("AWAY".equals(ev.getTeam())) away++;
        }
        return new int[]{home, away};
    }

    private double averageRating(ProposalStatsCollector stats, String side) {
        List<PlayerStats> list = stats.buildPlayerStats(side);
        if (list.isEmpty()) return 0.0;
        double sum = 0.0;
        for (PlayerStats ps : list) sum += ps.rating();
        return Math.round(10.0 * sum / list.size()) / 10.0;
    }

    /**
     * Expected goals from recorder shot outcomes. Each shot attempt emits exactly
     * ONE event of {GOAL, SHOT_SAVED, SHOT_BLOCKED, SHOT_POST, SHOT_MISSED} with
     * the shooter's position. Distance-to-goal bands mirror newLogic
     * {@code MatchSimulator.calculateXG} (cell = 14 m).
     */
    private double[] computeExpectedGoals(MatchRecorder recorder) {
        double homeXg = 0.0;
        double awayXg = 0.0;
        for (MatchEvent ev : recorder.getEvents()) {
            if (!SHOT_OUTCOME_TYPES.contains(ev.getType())) continue;
            if (ev.getPositionRow() == null || ev.getPositionColumn() == null) continue;
            boolean home = "HOME".equals(ev.getTeam());
            if (!home && !"AWAY".equals(ev.getTeam())) continue;

            double goalRow = home ? 8.0 : 1.0; // HOME attacks row 8, AWAY attacks row 1
            double cellDist = Math.hypot(goalRow - ev.getPositionRow(), 3.5 - ev.getPositionColumn());
            double meters = cellDist * 14.0;
            double xg = xgFromMeters(meters);
            if (home) homeXg += xg;
            else awayXg += xg;
        }
        return new double[]{
                Math.round(homeXg * 10.0) / 10.0,
                Math.round(awayXg * 10.0) / 10.0
        };
    }

    private static double xgFromMeters(double meters) {
        if (meters < 6.0) return 0.75;
        if (meters < 10.0) return 0.60;
        if (meters < 14.0) return 0.45;
        if (meters < 20.0) return 0.30;
        if (meters < 28.0) return 0.20;
        return 0.12;
    }

    /** Compact formation string (e.g. "4-3-3") derived from role counts. */
    private String deriveFormation(MatchState state, String side) {
        int def = 0, mid = 0, att = 0;
        for (Player p : state.getPlayers()) {
            if (!side.equals(p.getTeam())) continue;
            String role = p.getRole() == null ? "" : p.getRole().toUpperCase();
            if (role.equals("GK")) continue;
            if (role.startsWith("D") || role.startsWith("WB")) def++;
            else if (role.startsWith("M") || role.startsWith("C") || role.startsWith("DM")) mid++;
            else att++;
        }
        return def + "-" + mid + "-" + att;
    }
}