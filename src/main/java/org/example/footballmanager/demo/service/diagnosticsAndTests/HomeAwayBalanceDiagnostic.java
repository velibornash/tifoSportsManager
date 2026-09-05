package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.List;

/**
 * Deterministic balance check: both teams get IDENTICAL uniform skills, so any
 * systematic HOME/AWAY bias in territory, possession, shots or goals is purely
 * from engine asymmetry — not team strength.
 */
public class HomeAwayBalanceDiagnostic {

    public static void main(String[] args) {
        int matches = 12;
        if (args.length > 0) matches = Integer.parseInt(args[0]);
        int skill = 14;
        if (args.length > 1) skill = Integer.parseInt(args[1]);

        long totHomePoss = 0, totAwayPoss = 0;
        long totHomeAtt = 0, totAwayAtt = 0, totMid = 0;
        int totHomeGoals = 0, totAwayGoals = 0;
        long totHomeShots = 0, totAwayShots = 0;

        for (int m = 0; m < matches; m++) {
            long seed = 1000 + m;
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Omladinac", skill);
            var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Partizan", skill);
            final long[] hPoss = {0}, aPoss = {0};
            final long[] hAtt = {0}, aAtt = {0}, mid = {0};
            final long[] hShot = {0}, aShot = {0};
            final long[] hRowSum = {0}, aRowSum = {0}, hCount = {0}, aCount = {0};
            final int[] hGoal = {0}, aGoal = {0};

            MatchResult result = simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan",
                    new TickObserver() {
                        @Override
                        public void onTick(long tick, MatchState state) {
                            Position bp = state.getBall().getPosition();
                            double br = bp.getRow();
                            Player carrier = state.getBall().getCarrier();
                            if (carrier != null) {
                                if ("HOME".equals(carrier.getTeam())) {
                                    hPoss[0]++; hShot[0]++; hRowSum[0] += bp.getRow(); hCount[0]++;
                                } else {
                                    aPoss[0]++; aShot[0]++; aRowSum[0] += bp.getRow(); aCount[0]++;
                                }
                            }
                            if (br >= 5.0) hAtt[0]++;       // HOME attacking third (high rows)
                            else if (br <= 3.0) aAtt[0]++;  // AWAY attacking third (low rows)
                            else mid[0]++;
                        }
                    });

            // Count goals from MatchResult
            int hg = 0, ag = 0;
            for (var goal : result.goals()) {
                if ("HOME".equals(goal.scorerTeam())) hg++; else ag++;
            }
            hGoal[0] = hg; aGoal[0] = ag;
            totHomeGoals += hg; totAwayGoals += ag;
            totHomePoss += hPoss[0]; totAwayPoss += aPoss[0];
            totHomeAtt += hAtt[0]; totAwayAtt += aAtt[0]; totMid += mid[0];
            totHomeShots += hShot[0]; totAwayShots += aShot[0];

            System.out.printf("match %2d: %d-%d | possession HOME %d (%4.1f%%) AWAY %d | HOME-att-third %4.1f%% AWAY-att-third %4.1f%% | avg carrier row HOME %.2f AWAY %.2f%n",
                    m, hGoal[0], aGoal[0],
                    hPoss[0], 100.0 * hPoss[0] / (hPoss[0] + aPoss[0]), aPoss[0],
                    100.0 * hAtt[0] / (hAtt[0] + aAtt[0] + mid[0]),
                    100.0 * aAtt[0] / (hAtt[0] + aAtt[0] + mid[0]),
                    1.0 * hRowSum[0] / hCount[0], 1.0 * aRowSum[0] / aCount[0]);
        }

        long tot = totHomeAtt + totAwayAtt + totMid;
        long poss = totHomePoss + totAwayPoss;
        System.out.printf("%n=== TOTALS (equal skill %d, %d matches) ===%n", skill, matches);
        System.out.printf("Goals:         HOME %d  AWAY %d%n", totHomeGoals, totAwayGoals);
        System.out.printf("Possession:    HOME %5.1f%%  AWAY %5.1f%%%n",
                100.0 * totHomePoss / poss, 100.0 * totAwayPoss / poss);
        System.out.printf("Ball in att3:  HOME %5.1f%%  AWAY %5.1f%% (middle %5.1f%%)%n",
                100.0 * totHomeAtt / tot, 100.0 * totAwayAtt / tot, 100.0 * totMid / tot);
        System.out.printf("Ball attempts: HOME %d  AWAY %d%n", totHomeShots, totAwayShots);
    }
}
