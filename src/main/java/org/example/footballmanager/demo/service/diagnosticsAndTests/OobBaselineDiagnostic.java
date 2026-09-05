package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.result.TeamMatchStats;

import java.util.List;

/**
 * Prints per-match set-piece / OOB counts (corners, throw-ins, goal-kicks,
 * pass-out-of-bounds, loose-balls) plus goals, to baseline how often the ball
 * currently leaves the pitch. Both teams get identical uniform skill so we can
 * also see balance while measuring OOB frequency.
 */
public class OobBaselineDiagnostic {

    public static void main(String[] args) {
        int matches = 3;
        if (args.length > 0) matches = Integer.parseInt(args[0]);
        int skill = 14;
        if (args.length > 1) skill = Integer.parseInt(args[1]);

        long th = 0, ta = 0, corners = 0, gk = 0, poob = 0, loose = 0;
        long hg = 0, ag = 0;

        for (int m = 0; m < matches; m++) {
            long seed = 1000 + m;
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Omladinac", skill);
            var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Partizan", skill);
            MatchResult result = simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan",
                    new TickObserver() {
                        @Override
                        public void onTick(long tick, MatchState state) {
                        }
                    });

            TeamMatchStats hs = result.homeStats();
            TeamMatchStats as = result.awayStats();
            int hg0 = result.goals().stream().filter(g -> "HOME".equals(g.scorerTeam())).toList().size();
            int ag0 = result.goals().size() - hg0;
            hg += hg0; ag += ag0;
            th += hs.throwInCount() + as.throwInCount();
            ta += hs.goalKickCount() + as.goalKickCount();
            corners += hs.corners() + as.corners();
            poob += hs.passOutOfBoundsCount() + as.passOutOfBoundsCount();
            loose += hs.looseBallPasses() + as.looseBallPasses();

            System.out.printf("match %2d: %d-%d | H{goalKicks=%-3d throwIn=%-3d corner=%-3d} A{goalKicks=%-3d throwIn=%-3d corner=%-3d} | passOOB=%d loose=%d%n",
                    m, hg0, ag0,
                    hs.goalKickCount(), hs.throwInCount(), hs.corners(),
                    as.goalKickCount(), as.throwInCount(), as.corners(),
                    hs.passOutOfBoundsCount() + as.passOutOfBoundsCount(),
                    hs.looseBallPasses() + as.looseBallPasses());
        }

        System.out.printf("%n=== OOB TOTALS (equal skill %d, %d matches) ===%n", skill, matches);
        System.out.printf("Goals:            HOME %d  AWAY %d%n", hg, ag);
        System.out.printf("Throw-ins:        %d (%.2f/match)%n", th, 1.0 * th / matches);
        System.out.printf("Goal-kicks:       %d (%.2f/match)%n", ta, 1.0 * ta / matches);
        System.out.printf("Corners:          %d (%.2f/match)%n", corners, 1.0 * corners / matches);
        System.out.printf("Pass out-of-play: %d (%.2f/match)%n", poob, 1.0 * poob / matches);
        System.out.printf("Loose balls:      %d (%.2f/match)%n", loose, 1.0 * loose / matches);
    }
}
