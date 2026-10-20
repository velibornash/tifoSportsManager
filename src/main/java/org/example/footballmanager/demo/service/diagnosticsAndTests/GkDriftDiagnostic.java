package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.List;

public class GkDriftDiagnostic {

    public static void main(String[] args) {
        int matches = 3;
        if (args.length > 0) matches = Integer.parseInt(args[0]);
        for (int m = 0; m < matches; m++) {
            long seed = 100 + m;
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
            var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

            final double[] maxHomeRow = {0};   // max row reached by HOME GK (goal at row 1)
            final double[] maxAwayRow = {99};  // min row reached by AWAY GK (goal at row 8)
            final double[] maxHomeDist = {0};  // max distance from own goal (HOME goal at row 1)
            final double[] maxAwayDist = {0};  // max distance from own goal (AWAY goal at row 8)

            simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan", new TickObserver() {
                @Override
                public void onTick(long tick, MatchState state) {
                    for (Player p : state.getPlayers()) {
                        if (!"GK".equals(p.getRole())) continue;
                        double row = p.getPosition().getRow();
                        double col = p.getPosition().getColumn();
                        if ("HOME".equals(p.getTeam())) {
                            if (row > 1.46 && row > maxHomeRow[0]) {
                                String act = state.getAction() == null ? "none"
                                        : String.valueOf(state.getAction().getType());
                                Position t = p.getTarget();
                                System.out.printf("  [tick %d] HOME GK at row %.2f col %.2f | ball(row %.2f,col %.2f) carrier=%s action=%s gkIsCarrier=%s target=%s returning=%s status=%s setPiece=%s var=%s%n",
                                        tick, row, col,
                                        state.getBall().getPosition().getRow(),
                                        state.getBall().getPosition().getColumn(),
                                        state.getBall().getCarrier() == null ? "null"
                                                : state.getBall().getCarrier().getLabel(),
                                        act,
                                        p == state.getBall().getCarrier(),
                                        t == null ? "null" : String.format("(%.2f,%.2f)", t.getRow(), t.getColumn()),
                                        p == state.getReturningPlayer(),
                                        state.getStatus(),
                                        state.isSetPiecePending() || state.isRestartFirstTouch(),
                                        state.isVARReviewActive());
                            }
                            double dist = Math.hypot(row - 1.0, col - 3.5);
                            if (dist > maxHomeDist[0]) maxHomeDist[0] = dist;
                            if (row > maxHomeRow[0]) maxHomeRow[0] = row;
                        } else {
                            double dist = Math.hypot(8.0 - row, col - 3.5);
                            if (dist > maxAwayDist[0]) maxAwayDist[0] = dist;
                            if (row < maxAwayRow[0]) maxAwayRow[0] = row; // smaller = further from row-8 goal
                        }
                    }
                }
            });

            System.out.printf("match %d: HOME GK furthest %.2f cells (row %.2f) from own goal | AWAY GK furthest %.2f cells (row %.2f) from own goal%n",
                    m, maxHomeDist[0], maxHomeRow[0], maxAwayDist[0], maxAwayRow[0]);
        }
    }
}
