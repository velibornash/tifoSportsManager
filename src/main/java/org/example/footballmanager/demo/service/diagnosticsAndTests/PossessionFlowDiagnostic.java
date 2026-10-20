package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

public class PossessionFlowDiagnostic {

    public static void main(String[] args) {
        int matches = 6;
        if (args.length > 0) matches = Integer.parseInt(args[0]);

        long[] totals = new long[8]; // homeTicks, awayTicks, homeSess, awaySess, homeMax, awayMax, kickoffHome, kickoffAway

        for (int m = 0; m < matches; m++) {
            long seed = 5000 + m;
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Home", 14);
            var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Away", 14);

            final String[] holder = {"?"};           // current carrier team
            final long[] hTick = {0}, aTick = {0};  // current session length
            final long[] hSess = {0}, aSess = {0};  // session count
            final long[] hMax = {0}, aMax = {0};    // longest session
            final long[] hTotal = {0}, aTotal = {0}; // total carrier ticks
            final long[] kickoffHome = {0}, kickoffAway = {0};

            MatchResult result = simulator.simulate(homePlayers, awayPlayers, "Home", "Away",
                    new TickObserver() {
                        @Override
                        public void onTick(long tick, MatchState state) {
                            Player carrier = state.getBall().getCarrier();
                            String now = carrier != null ? carrier.getTeam() : null;

                            // Detect kickoff: carrier just appeared from null (set at kickoff or restart)
                            if (now != null && "?".equals(holder[0])) {
                                if ("HOME".equals(now)) kickoffHome[0]++;
                                else kickoffAway[0]++;
                            }

                            // Possession change
                            if (holder[0] != null && !"none".equals(holder[0]) && !"?".equals(holder[0]) && !holder[0].equals(now)) {
                                if ("HOME".equals(holder[0])) {
                                    hSess[0]++;
                                    long len = hTick[0];
                                    hTotal[0] += len;
                                    if (len > hMax[0]) hMax[0] = len;
                                } else if ("AWAY".equals(holder[0])) {
                                    aSess[0]++;
                                    long len = aTick[0];
                                    aTotal[0] += len;
                                    if (len > aMax[0]) aMax[0] = len;
                                }
                                hTick[0] = 0;
                                aTick[0] = 0;
                            }

                            if (now != null) {
                                holder[0] = now;
                                if ("HOME".equals(now)) hTick[0]++;
                                else aTick[0]++;
                            } else {
                                holder[0] = "none";
                            }
                        }
                    });

            // Flush final session
            if ("HOME".equals(holder[0])) {
                hTotal[0] += hTick[0];
                hSess[0]++;
                if (hTick[0] > hMax[0]) hMax[0] = hTick[0];
            } else if ("AWAY".equals(holder[0])) {
                aTotal[0] += aTick[0];
                aSess[0]++;
                if (aTick[0] > aMax[0]) aMax[0] = aTick[0];
            }

            totals[0] += hTotal[0]; totals[1] += aTotal[0];
            totals[2] += hSess[0];  totals[3] += aSess[0];
            totals[4] = Math.max(totals[4], hMax[0]);
            totals[5] = Math.max(totals[5], aMax[0]);
            totals[6] += kickoffHome[0]; totals[7] += kickoffAway[0];

            long tot = hTotal[0] + aTotal[0];
            System.out.printf("match %d: poss HOME %5d (%.1f%%) AWAY %5d (%.1f%%) | sess %3d/%3d | max %4d/%4d%n",
                    m, hTotal[0], 100.0 * hTotal[0] / Math.max(1, tot),
                    aTotal[0], 100.0 * aTotal[0] / Math.max(1, tot),
                    hSess[0], aSess[0], hMax[0], aMax[0]);
        }

        long gt = totals[0] + totals[1];
        long homeAvgSession = totals[2] > 0 ? totals[0] / totals[2] : 0;
        long awayAvgSession = totals[3] > 0 ? totals[1] / totals[3] : 0;
        System.out.printf("%n=== TOTALS (%d matches) ===%n", matches);
        System.out.printf("carrier ticks: HOME %d (%.1f%%)  AWAY %d (%.1f%%)%n",
                totals[0], 100.0 * totals[0] / Math.max(1, gt),
                totals[1], 100.0 * totals[1] / Math.max(1, gt));
        System.out.printf("sessions:      HOME %d  AWAY %d%n", totals[2], totals[3]);
        System.out.printf("avg session:   HOME %d  AWAY %d ticks%n", homeAvgSession, awayAvgSession);
        System.out.printf("longest:       HOME %d  AWAY %d ticks%n", totals[4], totals[5]);
        System.out.printf("kickoffs:      HOME %d  AWAY %d%n", totals[6], totals[7]);
    }
}
