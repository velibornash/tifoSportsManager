package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.recording.MatchEvent;
import org.example.footballmanager.newLogic.sim.result.PlayerStats;
import org.example.footballmanager.newLogic.sim.result.TeamStats;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full per-team season report over N deterministically seeded matches.
 *
 * Everything reported here is read from the engine's own counters
 * ({@link TeamStats}, per-player stats, penalty counters) or counted from the
 * recorder's typed event stream — nothing is inferred or estimated, so a
 * "not tracked" metric is reported as such instead of being invented.
 *
 * Usage: {@code ProposalSeasonDiag [matches] [baseSeed]}
 *        {@code ProposalSeasonDiag 150 42}
 */
public class ProposalSeasonDiag {

    /** metric key -> human label, in report order. */
    private static final String[] ROWS = {
            "goals", "shots", "shotsOnTarget", "shotsMissed", "saves", "blocked",
            "onTargetRate", "shotConversion",
            "passes", "passesCompleted", "passAccuracy",
            "dribbles", "clearances", "throughBalls", "centers", "crosses",
            "duelsWon", "tackles", "interceptions", "deflections",
            "corners", "goalKicks", "throwIns", "offsides", "fouls",
            "yellowCards", "redCards", "penaltiesAwarded",
            "varReviews", "varConfirmed", "varOverturned",
            "possession",
    };

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 42L;

        Map<String, Double> home = newTotals();
        Map<String, Double> away = newTotals();
        Map<String, Double> all = newTotals();
        int scoreless = 0;
        int homeWins = 0, awayWins = 0, draws = 0;
        int maxGoals = 0;

        for (int i = 0; i < n; i++) {
            long seed = baseSeed + i;
            SimulationRandom.seed(seed);
            MatchState state = new MatchState();
            MatchSimulationLauncher.addTeam(state, "HOME");
            MatchSimulationLauncher.addTeam(state, "AWAY");
            MatchOrchestrator o = new MatchOrchestrator(state);
            o.getRestartManager().handleKickoff(state, "HOME");
            for (Player p : state.getPlayers()) {
                state.setRoundStartPosition(p.getId(), p.getPosition());
                state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
            }
            o.simulate(3600);

            TeamStats h = o.getStats().buildTeamStats("HOME");
            TeamStats a = o.getStats().buildTeamStats("AWAY");
            Map<String, Double> hEv = countEvents(o, "HOME");
            Map<String, Double> aEv = countEvents(o, "AWAY");
            Map<String, Double> duelH = playerSum(o, "HOME");
            Map<String, Double> duelA = playerSum(o, "AWAY");

            accumulate(home, h, hEv, duelH);
            accumulate(away, a, aEv, duelA);
            accumulate(all, h, hEv, duelH);
            accumulate(all, a, aEv, duelA);

            int hg = state.getHomeGoals(), ag = state.getAwayGoals();
            maxGoals = Math.max(maxGoals, hg + ag);
            if (hg == 0 && ag == 0) scoreless++;
            if (hg > ag) homeWins++; else if (ag > hg) awayWins++; else draws++;
        }

        System.out.printf("=== PROPOSAL SEASON REPORT — %d matches, base seed %d ===%n%n", n, baseSeed);
        System.out.printf("%-20s %10s %10s %12s%n", "metric", "HOME", "AWAY", "both avg");
        System.out.printf("%-20s %10s %10s %12s%n", "-".repeat(20), "-".repeat(10), "-".repeat(10), "-".repeat(12));
        for (String key : ROWS) {
            double h = home.getOrDefault(key, 0.0) / n;
            double a = away.getOrDefault(key, 0.0) / n;
            double b = all.getOrDefault(key, 0.0) / n;
            System.out.printf("%-20s %10.1f %10.1f %12.1f%n", key, h, a, b);
        }

        System.out.printf("%nresults: HOME %d wins / AWAY %d wins / %d draws, %d scoreless (0-0), "
                        + "highest score %d goals%n",
                homeWins, awayWins, draws, scoreless, maxGoals);
        System.out.println("throughBalls / centers / crosses: now real ActionTypes (THRU / CENTER / CROSS), "
                + "counted as passes for accuracy AND with their own per-team counters.");
    }

    // ==================== accumulation ====================

    private static Map<String, Double> newTotals() {
        return new LinkedHashMap<>();
    }

    private static void add(Map<String, Double> into, String key, double v) {
        into.merge(key, v, Double::sum);
    }

    private static void accumulate(Map<String, Double> into, TeamStats t,
                                   Map<String, Double> ev, Map<String, Double> players) {
        add(into, "goals", t.goals());
        add(into, "shots", t.shots());
        add(into, "shotsOnTarget", t.shotsOnTarget());
        add(into, "saves", t.saves());
        add(into, "blocked", t.blocks());
        add(into, "passes", t.passesAttempted());
        add(into, "passesCompleted", t.passesCompleted());
        add(into, "dribbles", t.dribbles());
        add(into, "clearances", t.clearances());
        add(into, "interceptions", t.interceptions());
        add(into, "deflections", t.deflections());
        add(into, "corners", t.corners());
        add(into, "goalKicks", t.goalKicks());
        add(into, "throwIns", t.throwIns());
        add(into, "offsides", t.offsides());
        add(into, "fouls", t.fouls());
        add(into, "yellowCards", t.yellowCards());
        add(into, "redCards", t.redCards());
        add(into, "possession", t.possessionPercent());
        add(into, "throughBalls", t.throughBalls());
        add(into, "centers", t.centers());
        add(into, "crosses", t.crosses());

        for (Map.Entry<String, Double> e : ev.entrySet()) {
            add(into, e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Double> e : players.entrySet()) {
            add(into, e.getKey(), e.getValue());
        }

        // derived
        int sot = t.shotsOnTarget();
        int shots = t.shots();
        add(into, "onTargetRate", shots > 0 ? 100.0 * sot / shots : 0);
        add(into, "shotConversion", sot > 0 ? 100.0 * t.goals() / sot : 0);
        add(into, "passAccuracy", t.passesAttempted() > 0
                ? 100.0 * t.passesCompleted() / t.passesAttempted() : 0);
    }

    /** Count the per-team metrics that only exist in the typed event stream. */
    private static Map<String, Double> countEvents(MatchOrchestrator o, String team) {
        Map<String, Double> out = new LinkedHashMap<>();
        double missed = 0, varReviews = 0, varConfirmed = 0, varOverturned = 0, pens = 0;
        for (MatchEvent e : o.getRecorder().getEvents()) {
            String type = e.getType();
            boolean mine = team.equals(e.getTeam());
            if (mine) {
                if ("SHOT_MISSED".equals(type)) missed++;
                if ("PENALTY_AWARDED".equals(type)) pens++;
                if ("GOAL".equals(type)) {
                    // goal already counted from TeamStats
                }
            }
            if (type.startsWith("VAR_")) {
                if (type.endsWith("_CONFIRMED")) { varReviews++; varConfirmed++; }
                else if (type.endsWith("_OVERTURNED")) { varReviews++; varOverturned++; }
            }
        }
        out.put("shotsMissed", missed);
        out.put("penaltiesAwarded", pens);
        out.put("varReviews", varReviews);
        out.put("varConfirmed", varConfirmed);
        out.put("varOverturned", varOverturned);
        return out;
    }

    /**
     * Duels won and tackles, summed from the per-player counters.
     * Reads {@code buildPlayerStats(team)} directly — the flattened JSON carries
     * the DISPLAY team name ("Home FC"), so filtering that by "HOME" silently
     * returns nothing.
     */
    private static Map<String, Double> playerSum(MatchOrchestrator o, String team) {
        double duelsWon = 0, tackles = 0;
        for (PlayerStats p : o.getStats().buildPlayerStats(team)) {
            duelsWon += p.duelsWon();
            tackles += p.tackles();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("duelsWon", duelsWon);
        out.put("tackles", tackles);
        return out;
    }
}
