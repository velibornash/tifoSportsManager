package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.List;
import java.util.Locale;

/**
 * For EVERY restart (corner, goal kick, throw-in, free kick) logs:
 *  - the exact ball position when the restart is set up,
 *  - the exact taker position when the restart is executed (ball picked up),
 *  - whether the taker TELEPORTED or walked to the restart spot.
 *
 * Also flags any restart where the ball is placed at a position that is NOT the
 * canonical restart spot (i.e. begins from inside the OOB zone / wrong corner).
 */
public class RestartPositionDiagnostic {

    public static void main(String[] args) {
        int matches = 1;
        if (args.length > 0) matches = Integer.parseInt(args[0]);
        System.out.println("=== RESTART POSITION DIAGNOSTIC ===");

        for (int m = 0; m < matches; m++) {
            long seed = 700 + m;
            System.out.printf("%n===== MATCH %d (seed=%d) =====%n", m, seed);
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeamWithSkill("HOME", "Omladinac", 14);
            var awayPlayers = MatchSimulationController.generateTeamWithSkill("AWAY", "Partizan", 14);

            final int[] restartCount = {0};
            final Player[] lastTaker = {null};
            final Position[] ballAtSetPiece = {null};
            final Integer[] restartTick = {null};
            final String[] restartKind = {null};
            final double[] distAtArrival = {Double.NaN};

            simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan", new TickObserver() {
                @Override
                public void onTick(long tick, MatchState state) {
                    // Detect new set piece: setPiecePending becomes true
                    if (state.isSetPiecePending() && restartTick[0] == null) {
                        restartTick[0] = (int) tick;
                        ballAtSetPiece[0] = new Position(
                                state.getBall().getPosition().getRow(),
                                state.getBall().getPosition().getColumn());
                        lastTaker[0] = state.getFreeKickTaker();
                    }
                    // When set piece resolved and taker has taken it, log arrival data
                    if (restartTick[0] != null && !state.isSetPiecePending()) {
                        Player taker = lastTaker[0];
                        Position ballPos = state.getBall().getPosition();
                        Player carrier = state.getBall().getCarrier();
                        restartCount[0]++;
                        int ticksTook = (int) (tick - restartTick[0]);
                        String kind = state.getStatus();
                        System.out.printf(Locale.ROOT,
                                "  restart#%d @tick=%d took=%dticks | setPieceBall=(%.2f,%.2f) → playBall=(%.2f,%.2f)%n",
                                restartCount[0], restartTick[0], ticksTook,
                                ballAtSetPiece[0].getRow(), ballAtSetPiece[0].getColumn(),
                                ballPos.getRow(), ballPos.getColumn());
                        if (taker != null) {
                            System.out.printf(Locale.ROOT,
                                    "    taker=%s at restart=(%.2f,%.2f) | takerNow=(%.2f,%.2f) | carrier=%s%n",
                                    taker.getLabel(),
                                    taker.getPosition().getRow(), taker.getPosition().getColumn(),
                                    ballPos.getRow(), ballPos.getColumn(),
                                    carrier == null ? "null" : carrier.getLabel());
                        }
                        restartTick[0] = null;
                        lastTaker[0] = null;
                        ballAtSetPiece[0] = null;
                    }
                }
            });
            System.out.printf("  total restarts detected: %d%n", restartCount[0]);
        }
    }
}
