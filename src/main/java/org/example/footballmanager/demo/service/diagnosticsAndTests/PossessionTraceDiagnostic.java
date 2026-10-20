package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

/**
 * Measures true possession by attributing ticks to a team only while that
 * team actually has control (Opta-style):
 *  - carrier != null  → carrier team wins the tick
 *  - ball in transit  → lastTouchTeam (passer) wins the tick
 *  - ball loose       → credited to nobody (contested)
 */
public class PossessionTraceDiagnostic {

    public static void main(String[] args) {
        int matches = 6;
        if (args.length > 0) matches = Integer.parseInt(args[0]);
        int skill = 14;
        if (args.length > 1) skill = Integer.parseInt(args[1]);

        long carrierHome = 0, carrierAway = 0;
        long transitHome = 0, transitAway = 0;
        long noTouch = 0;   // lastTouchTeam null/unset => unattributed
        long looseTicks = 0, transitTicks = 0;
        int homeGoals = 0, awayGoals = 0;

        for (int m = 0; m < matches; m++) {
            long seed = 2000 + m;
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Omladinac", skill);
            var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Partizan", skill);
            final long[] ch = {0}, ca = {0}, th = {0}, ta = {0}, loose = {0}, transit = {0}, nolast = {0};

            MatchResult result = simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan",
                    new TickObserver() {
                        @Override
                        public void onTick(long tick, MatchState state) {
                            Player carrier = state.getBall().getCarrier();
                            if (carrier != null) {
                                if ("HOME".equals(carrier.getTeam())) ch[0]++; else ca[0]++;
                            } else {
                                boolean inFlight = state.getBall().getTarget() != null;
                                if (inFlight) {
                                    transit[0]++;
                                    String lt = state.getLastTouchTeam();
                                    if (lt != null) {
                                        if ("HOME".equals(lt)) th[0]++; else ta[0]++;
                                    } else {
                                        nolast[0]++;
                                    }
                                } else {
                                    loose[0]++;
                                }
                            }
                        }
                    });

            int hg = 0, ag = 0;
            for (var goal : result.goals()) {
                if ("HOME".equals(goal.scorerTeam())) hg++; else ag++;
            }
            homeGoals += hg; awayGoals += ag;

            long hCarry = ch[0], aCarry = ca[0], hTrans = th[0], aTrans = ta[0];
            System.out.printf("match %2d: %d-%d | CARRIER HOME %6d AWAY %6d | LASTTOUCH(transit+loose) HOME %6d AWAY %6d | loose=%d transit=%d unattributed=%d%n",
                    m, hg, ag, hCarry, aCarry, hTrans, aTrans, loose[0], transit[0], nolast[0]);

            carrierHome += ch[0]; carrierAway += ca[0];
            transitHome += th[0]; transitAway += ta[0];
            looseTicks += loose[0]; transitTicks += transit[0]; noTouch += nolast[0];
        }

        long total = carrierHome + carrierAway + transitHome + transitAway + noTouch;
        System.out.printf("%n=== TRUE POSSESSION (equal skill %d, %d matches) ===%n", skill, matches);
        System.out.printf("control ticks (carrier+transit): HOME %5.1f%%  AWAY %5.1f%%%n",
                100.0 * (carrierHome + transitHome) / (carrierHome + carrierAway + transitHome + transitAway),
                100.0 * (carrierAway + transitAway) / (carrierHome + carrierAway + transitHome + transitAway));
        System.out.printf("carrier-only: HOME %5.1f%%  AWAY %5.1f%% (the legacy metric)%n",
                100.0 * carrierHome / (carrierHome + carrierAway),
                100.0 * carrierAway / (carrierHome + carrierAway));
        System.out.printf("tick mix: carrier %5.1f%% | in-transit %5.1f%% | loose %5.1f%% | unattributed %5.1f%%%n",
                100.0 * (carrierHome + carrierAway) / total,
                100.0 * transitTicks / total,
                100.0 * looseTicks / total,
                100.0 * noTouch / total);
        System.out.printf("goals: HOME %d  AWAY %d%n", homeGoals, awayGoals);
    }
}