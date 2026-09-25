package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.result.TeamStats;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

public class ProposalBatchDiag {
    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
        int goals = 0, shots = 0, sot = 0, passes = 0, comp = 0;
        int homeGoals = 0, awayGoals = 0, zeroZero = 0;
        int interceptions = 0, deflections = 0, fouls = 0, yellowCards = 0, redCards = 0;
        int homeShots = 0, awayShots = 0, homeSot = 0, awaySot = 0, homeInter = 0, awayInter = 0;
        double homePoss = 0;
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
            TeamStats home = o.getStats().buildTeamStats("HOME");
            TeamStats away = o.getStats().buildTeamStats("AWAY");
            goals += state.getHomeGoals() + state.getAwayGoals();
            homeGoals += state.getHomeGoals();
            awayGoals += state.getAwayGoals();
            shots += state.getShots();
            sot += state.getShotsOnTarget();
            passes += state.getPassAttempts();
            comp += state.getPassesCompleted();
            interceptions += home.interceptions() + away.interceptions();
            deflections += home.deflections() + away.deflections();
            fouls += home.fouls() + away.fouls();
            yellowCards += home.yellowCards() + away.yellowCards();
            redCards += home.redCards() + away.redCards();
            homeShots += home.shots(); awayShots += away.shots();
            homeSot += home.shotsOnTarget(); awaySot += away.shotsOnTarget();
            homeInter += home.interceptions(); awayInter += away.interceptions();
            homePoss += home.possessionPercent();
            if (state.getHomeGoals() == 0 && state.getAwayGoals() == 0) zeroZero++;
            System.out.printf("m%d seed=%d H%d:%d shots=%d sot=%d pass=%d/%d int=%d def=%d%n",
                    i, seed, state.getHomeGoals(), state.getAwayGoals(), state.getShots(),
                    state.getShotsOnTarget(), state.getPassesCompleted(), state.getPassAttempts(),
                    home.interceptions() + away.interceptions(),
                    home.deflections() + away.deflections());
        }
        System.out.printf("\n=== %d matches (base seed %d) ===%n", n, baseSeed);
        System.out.printf("avg goals %.2f (H %.2f / A %.2f), 0-0 count %d%n",
                goals / (double) n, homeGoals / (double) n, awayGoals / (double) n, zeroZero);
        System.out.printf("avg shots %.1f sot %.1f (%.0f%%)%n",
                shots / (double) n, sot / (double) n, 100.0 * sot / (shots > 0 ? shots : 1));
        System.out.printf("avg pass %d/%d (%.0f%%)%n",
                comp / n, passes / n, 100.0 * comp / (passes > 0 ? passes : 1));
        System.out.printf("avg interceptions %.1f deflections %.1f fouls %.1f cards %.1f/%.1f%n",
                interceptions / (double) n, deflections / (double) n, fouls / (double) n,
                yellowCards / (double) n, redCards / (double) n);
        // Side split — a mirror bug in any row comparison shows up here first
        // (the demo/service engine needed exactly this check to find its 75/25
        // possession skew).
        System.out.printf("HOME shots %.1f (sot %.1f) int %.1f | AWAY shots %.1f (sot %.1f) int %.1f | HOME possession %.0f%%%n",
                homeShots / (double) n, homeSot / (double) n, homeInter / (double) n,
                awayShots / (double) n, awaySot / (double) n, awayInter / (double) n,
                homePoss / n);
    }
}
