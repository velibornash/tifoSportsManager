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
        int homeCorners=0, awayCorners=0, homeGK=0, awayGK=0, homeTI=0, awayTI=0;
        int penaltiesAwarded = 0, penaltyKicks = 0;
        int penaltyGoals = 0, penaltySaved = 0, penaltyMissed = 0;
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
            // Restart distribution — S1.2 (goal kicks / throw-ins / corners were inverted)
            // and S1.3 (corners skewed 7:1) could not be assessed because the batch printed
            // no restart columns at all.
            homeCorners += home.corners(); awayCorners += away.corners();
            homeGK += home.goalKicks(); awayGK += away.goalKicks();
            homeTI += home.throwIns(); awayTI += away.throwIns();
            // Penalty chain (S1.7). Before penalties were implemented a penalty was awarded
            // and never taken, which no aggregate would have revealed — the award counter looked
            // plausible. Invariant: every PENALTY_AWARDED is followed by a PENALTY_KICK, and
            // every kick ends in exactly one of scored / saved / missed.
            for (var ev : o.getRecorder().getEvents()) {
                String t = ev.getType();
                if ("PENALTY_AWARDED".equals(t)) penaltiesAwarded++;
                if ("PENALTY_KICK".equals(t)) penaltyKicks++;
                if ("PENALTY_SCORED".equals(t)) penaltyGoals++;
                if ("PENALTY_SAVED".equals(t)) penaltySaved++;
                if ("PENALTY_MISS".equals(t)) penaltyMissed++;
            }
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
        System.out.printf("avg restarts  corners %.1f (H %.1f / A %.1f, ratio %.2f)  "
                        + "goal kicks %.1f (H %.1f / A %.1f)  throw-ins %.1f (H %.1f / A %.1f)%n",
                (homeCorners+awayCorners)/(double) n, homeCorners/(double) n, awayCorners/(double) n,
                awayCorners == 0 ? 99.0 : homeCorners/(double) awayCorners,
                (homeGK+awayGK)/(double) n, homeGK/(double) n, awayGK/(double) n,
                (homeTI+awayTI)/(double) n, homeTI/(double) n, awayTI/(double) n);
        System.out.printf("avg interceptions %.1f deflections %.1f fouls %.1f cards %.1f/%.1f%n",
                interceptions / (double) n, deflections / (double) n, fouls / (double) n,
                yellowCards / (double) n, redCards / (double) n);
        // Side split — a mirror bug in any row comparison shows up here first
        // (the demo/service engine needed exactly this check to find its 75/25
        // possession skew).
        System.out.printf("avg penalties %.2f/match  kicks %.2f  scored %d  saved %d  missed %d  conversion %.0f%%%n",
                penaltiesAwarded / (double) n, penaltyKicks / (double) n,
                penaltyGoals, penaltySaved, penaltyMissed,
                penaltyKicks == 0 ? 0.0
                        : 100.0 * penaltyGoals / (penaltyGoals + penaltySaved + penaltyMissed));
        // The invariant, stated as a hard failure rather than a number to eyeball.
        if (penaltiesAwarded != penaltyKicks) {
            System.out.printf("*** PENALTY CHAIN BROKEN: %d awarded but %d taken ***%n",
                    penaltiesAwarded, penaltyKicks);
        }
        System.out.printf("HOME shots %.1f (sot %.1f) int %.1f | AWAY shots %.1f (sot %.1f) int %.1f | HOME possession %.0f%%%n",
                homeShots / (double) n, homeSot / (double) n, homeInter / (double) n,
                awayShots / (double) n, awaySot / (double) n, awayInter / (double) n,
                homePoss / n);
    }
}
