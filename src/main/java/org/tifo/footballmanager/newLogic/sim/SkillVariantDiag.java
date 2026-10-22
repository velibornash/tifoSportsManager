package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.PlayerSkills;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.recording.MatchEvent;
import org.tifo.footballmanager.newLogic.sim.result.PlayerStats;
import org.tifo.footballmanager.newLogic.sim.result.TeamStats;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the three skill variants requested for measurement:
 *   A: both sides all skills 15
 *   B: home all skills 20, away all skills 15
 *   C: home all skills 20, away all skills 12
 *
 * Usage: SkillVariantDiag [matches] [baseSeed]
 *        SkillVariantDiag 200 42
 */
public class SkillVariantDiag {

    private static final String[][] ROLES = {
            {"GK",  "2.5", "3.5"},
            {"DL",  "2.5", "1.5"}, {"DR",  "2.5", "5.5"},
            {"DCL", "3.0", "2.5"}, {"DCR", "3.0", "4.5"},
            {"ML",  "4.0", "1.5"}, {"MR",  "4.0", "5.5"},
            {"CML", "4.5", "2.5"}, {"CMR", "4.5", "4.5"},
            {"STL", "6.0", "3.0"}, {"STR", "6.0", "4.0"},
    };

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 42L;

        int[][] variants = {{15, 15}, {20, 15}, {20, 12}};
        String[] names = {"A (15 vs 15)", "B (20 vs 15)", "C (20 vs 12)"};

        for (int v = 0; v < variants.length; v++) {
            int homeSkill = variants[v][0];
            int awaySkill = variants[v][1];
            runVariant(names[v], n, baseSeed, homeSkill, awaySkill);
        }
    }

    private static void runVariant(String label, int n, long baseSeed, int homeSkill, int awaySkill) {
        Map<String, Double> home = newTotals();
        Map<String, Double> away = newTotals();
        int homeWins = 0, awayWins = 0, draws = 0, scoreless = 0, maxGoals = 0;

        for (int i = 0; i < n; i++) {
            long seed = baseSeed + i * 7 + (homeSkill * 31 + awaySkill);
            SimulationRandom.seed(seed);
            MatchState state = new MatchState();
            addTeam(state, "HOME", homeSkill);
            addTeam(state, "AWAY", awaySkill);
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

            int hg = state.getHomeGoals(), ag = state.getAwayGoals();
            maxGoals = Math.max(maxGoals, hg + ag);
            if (hg == 0 && ag == 0) scoreless++;
            if (hg > ag) homeWins++; else if (ag > hg) awayWins++; else draws++;
        }

        System.out.println();
        System.out.println("================================================================");
        System.out.printf("=== VARIANT %s  —  %d matches, base seed %d ===%n", label, n, baseSeed);
        System.out.printf("=== home skill %d, away skill %d ===%n", homeSkill, awaySkill);
        System.out.println("================================================================");
        System.out.printf("%-22s %10s %10s %12s%n", "metric", "HOME", "AWAY", "both avg");
        System.out.printf("%-22s %10s %10s %12s%n", "-".repeat(22), "-".repeat(10), "-".repeat(10), "-".repeat(12));
        String[] rows = {
                "goals", "shots", "shotsOnTarget", "shotsMissed", "saves", "blocked",
                "passes", "passesCompleted", "passAccuracy",
                "dribbles", "clearances", "throughBalls", "centers", "crosses",
                "duelsWon", "duelsLost", "interceptions", "deflections",
                "corners", "goalKicks", "throwIns", "offsides", "fouls",
                "yellowCards", "redCards", "penaltiesAwarded",
                "varReviews", "varConfirmed", "varOverturned",
                "possession",
        };
        for (String key : rows) {
            double h = home.getOrDefault(key, 0.0) / n;
            double a = away.getOrDefault(key, 0.0) / n;
            double b = (home.getOrDefault(key, 0.0) + away.getOrDefault(key, 0.0)) / (2.0 * n);
            System.out.printf("%-22s %10.2f %10.2f %12.2f%n", key, h, a, b);
        }
        System.out.printf("%nresults: HOME %d wins / AWAY %d wins / %d draws, %d scoreless (0-0), "
                        + "highest score %d goals%n",
                homeWins, awayWins, draws, scoreless, maxGoals);
    }

    public static void addTeam(MatchState state, String team, int skill) {
        double rowR = team.equals("HOME") ? 2.5 : 6.5;
        double rowM = team.equals("HOME") ? 4.0 : 5.0;
        double rowA = team.equals("HOME") ? 6.0 : 3.0;
        PlayerSkills sk = new PlayerSkills(skill, skill, skill, skill, skill, skill, skill, skill);
        for (int i = 0; i < ROLES.length; i++) {
            String role = ROLES[i][0];
            double r = Double.parseDouble(ROLES[i][1]);
            double c = Double.parseDouble(ROLES[i][2]);
            // mirror rows for away
            if (team.equals("AWAY")) {
                r = 9.0 - r;
            }
            Position pos = new Position(r, c);
            Player p = new Player(team + "-" + (i + 1), team.substring(0, 1) + (i + 1),
                    team, role, pos, pos, sk, 180);
            state.getPlayers().add(p);
        }
    }

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

        int sot = t.shotsOnTarget();
        int shots = t.shots();
        add(into, "shotsMissed", t.shots() - t.shotsOnTarget());
        add(into, "onTargetRate", shots > 0 ? 100.0 * sot / shots : 0);
        add(into, "passAccuracy", t.passesAttempted() > 0
                ? 100.0 * t.passesCompleted() / t.passesAttempted() : 0);
    }

    private static Map<String, Double> countEvents(MatchOrchestrator o, String team) {
        Map<String, Double> out = new LinkedHashMap<>();
        double missed = 0, varReviews = 0, varConfirmed = 0, varOverturned = 0, pens = 0, shotsMissed = 0;
        for (MatchEvent e : o.getRecorder().getEvents()) {
            String type = e.getType();
            boolean mine = team.equals(e.getTeam());
            if (mine) {
                if ("SHOT_MISSED".equals(type)) shotsMissed++;
                if ("PENALTY_AWARDED".equals(type)) pens++;
            }
            if (type.startsWith("VAR_")) {
                // VAR events carry the reviewing team; count for both? Count globally then attribute to neither specifically.
                if (type.endsWith("_CONFIRMED")) { varReviews++; varConfirmed++; }
                else if (type.endsWith("_OVERTURNED")) { varReviews++; varOverturned++; }
            }
        }
        out.put("shotsMissed", shotsMissed);
        out.put("penaltiesAwarded", pens);
        out.put("varReviews", varReviews);
        out.put("varConfirmed", varConfirmed);
        out.put("varOverturned", varOverturned);
        return out;
    }

    private static Map<String, Double> playerSum(MatchOrchestrator o, String team) {
        double duelsWon = 0, duelsLost = 0;
        for (PlayerStats p : o.getStats().buildPlayerStats(team)) {
            duelsWon += p.duelsWon();
            duelsLost += p.duelsLost();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("duelsWon", duelsWon);
        out.put("duelsLost", duelsLost);
        return out;
    }
}
