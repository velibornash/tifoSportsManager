package org.example.footballmanager.newLogic.sim.probe;

import org.example.footballmanager.newLogic.sim.MatchSimulationLauncher;
import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * TEAM STRENGTH DIFFERENTIAL — does the engine actually respect skill?
 *
 * <p>Replaces every player's skills with one flat number per team and runs full
 * matches, so the only thing that differs between the two sides is squad quality.
 * The question it answers: with a 10-point gap (18 vs 8) does the strong side
 * produce a correspondingly larger scoreline, or does the engine produce the same
 * football regardless — which would mean skill barely reaches the outcome.
 *
 * <p>A flat 18/18 for every skill is the "super team" case; 8/8 the "pub team"
 * case. Both are useful controls: a super team should still be held to ~2-3 goals
 * by a real defence, and two pub teams should not produce 8 goals each.
 *
 * <p>Run: {@code mvn exec:java -Dexec.mainClass=
 * org.example.footballmanager.newLogic.sim.probe.TeamStrengthProbe}
 */
public final class TeamStrengthProbe {

    /** One pairing to test. */
    private record Pairing(String label, double homeSkill, double awaySkill) {}

    public static void main(String[] args) {
        int matches = args.length > 0 ? Integer.parseInt(args[0]) : 30;

        List<Pairing> pairings = List.of(
                new Pairing("mirror   14 vs 14", 14, 14),
                new Pairing("mirror   18 vs 18", 18, 18),
                new Pairing("mirror    8 vs  8", 8, 8),
                new Pairing("strong   18 vs  8", 18, 8),
                new Pairing("strong   16 vs 12", 16, 12),
                new Pairing("strong   20 vs  6", 20, 6));

        System.out.println("TEAM STRENGTH DIFFERENTIAL — " + matches + " matches per pairing, seed base 900");
        System.out.println("every player of a side gets the SAME value for all 8 skills");
        System.out.println();
        System.out.printf("  %-17s %-15s %-15s %-13s %-11s %s%n",
                "pairing", "goals H/A", "shots H/A", "SOT H/A", "poss H/A", "results");
        System.out.println("  " + "-".repeat(94));

        for (Pairing pr : pairings) {
            run(pr, matches);
        }

        System.out.println();
        System.out.println("Reading it: a strong side should win more, score more, and shoot more.");
        System.out.println("If goals barely move across an 18-vs-8 gap, skill is not reaching the outcome.");
    }

    private static void run(Pairing pr, int matches) {
        int homeWins = 0, awayWins = 0, draws = 0, scoreless = 0, maxGoals = 0;
        double homeGoals = 0, awayGoals = 0, homeShots = 0, awayShots = 0;
        double homeSot = 0, awaySot = 0, homePoss = 0;
        List<String> bigScores = new ArrayList<>();

        for (int i = 0; i < matches; i++) {
            long seed = 900 + i;
            SimulationRandom.seed(seed);
            MatchState state = new MatchState();
            MatchSimulationLauncher.addTeam(state, "HOME");
            MatchSimulationLauncher.addTeam(state, "AWAY");
            flatten(state, "HOME", pr.homeSkill());
            flatten(state, "AWAY", pr.awaySkill());

            MatchOrchestrator o = new MatchOrchestrator(state);
            o.getRestartManager().handleKickoff(state, "HOME");
            for (Player p : state.getPlayers()) {
                state.setRoundStartPosition(p.getId(), p.getPosition());
                state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
            }
            o.simulate(3600);

            int hg = state.getHomeGoals(), ag = state.getAwayGoals();
            homeGoals += hg; awayGoals += ag;
            maxGoals = Math.max(maxGoals, hg + ag);
            if (hg == 0 && ag == 0) scoreless++;
            if (hg > ag) homeWins++; else if (ag > hg) awayWins++; else draws++;
            if (hg + ag >= 6) bigScores.add(hg + "-" + ag);

            var h = o.getStats().buildTeamStats("HOME");
            var a = o.getStats().buildTeamStats("AWAY");
            homeShots += h.shots(); awayShots += a.shots();
            homeSot += h.shotsOnTarget(); awaySot += a.shotsOnTarget();
            double hp = h.possessionPercent();
            homePoss += Double.isNaN(hp) ? 50 : hp;
        }

        System.out.printf("  %-17s %5.1f /%-7.1f %5.1f /%-7.1f %5.1f /%-6.1f %5.1f/%-5.1f %d-%d-%d  max %d, %d x 0-0%s%n",
                pr.label(),
                homeGoals / matches, awayGoals / matches,
                homeShots / matches, awayShots / matches,
                homeSot / matches, awaySot / matches,
                homePoss / matches, 100 - homePoss / matches,
                homeWins, awayWins, draws, maxGoals, scoreless,
                bigScores.isEmpty() ? "" : ", big: " + bigScores);
    }

    /**
     * Rebuild every player of one team with a flat skill value.
     *
     * {@code Player.skills} is final with no setter, so the player objects are
     * replaced in the list, preserving id, label, role, position and height so
     * the rest of the engine (which looks players up by id) is unaffected.
     */
    private static void flatten(MatchState state, String team, double value) {
        PlayerSkills flat = new PlayerSkills(value, value, value, value, value, value, value, value);
        for (int i = 0; i < state.getPlayers().size(); i++) {
            Player p = state.getPlayers().get(i);
            if (!p.getTeam().equals(team)) continue;
            state.getPlayers().set(i, new Player(
                    p.getId(), p.getLabel(), p.getTeam(), p.getRole(),
                    p.getPosition(), p.getAlternativePosition(), flat, p.getHeightCm()));
        }
    }
}
