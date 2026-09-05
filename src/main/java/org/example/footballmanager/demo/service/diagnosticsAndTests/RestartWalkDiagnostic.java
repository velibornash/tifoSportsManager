package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.List;

public class RestartWalkDiagnostic {

    public static void main(String[] args) {
        int matches = 2;
        if (args.length > 0) matches = Integer.parseInt(args[0]);
        for (int m = 0; m < matches; m++) {
            final int matchIdx = m;
            long seed = 500 + m;
            MatchSimulator simulator = new MatchSimulator(seed);
            var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
            var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");
            final long[] restartStartTick = {-1};
            final Player[] taker = {null};
            final Position[] lastBallPos = {null};
            final int[] walkTicks = {0};
            final int[] arriveHoldTicks = {0};
            final boolean[] takerArrived = {false};
            final int[] issues = {0};
            final long[] homeZoneTicks = {0};   // ball row 5.0-8.0 (HOME attacking third)
            final long[] awayZoneTicks = {0};   // ball row 0.0-3.0 (AWAY attacking third)
            final long[] middleTicks = {0};     // ball row 3.0-5.0
            final long[] homePossTicks = {0};   // HOME player carrying
            final long[] awayPossTicks = {0};   // AWAY player carrying

            List<Player> snapshotPlayers = null;
            simulator.simulate(homePlayers, awayPlayers, "Omladinac", "Partizan", new TickObserver() {
                @Override
                public void onTick(long tick, MatchState state) {
                    boolean setPiece = state.isSetPiecePending();
                    Player ft = state.getFreeKickTaker();
                    Position ballPos = state.getBall().getPosition();
                    Player carrier = state.getBall().getCarrier();

                    double br = ballPos.getRow();
                    if (br >= 5.0) homeZoneTicks[0]++;
                    else if (br <= 3.0) awayZoneTicks[0]++;
                    else middleTicks[0]++;
                    if (carrier != null) {
                        if ("HOME".equals(carrier.getTeam())) homePossTicks[0]++;
                        else awayPossTicks[0]++;
                    }

                    if (setPiece && ft != null && restartStartTick[0] < 0) {
                        // Restart begins
                        restartStartTick[0] = tick;
                        taker[0] = ft;
                        takerArrived[0] = false;
                        walkTicks[0] = 0;
                        arriveHoldTicks[0] = 0;
                        lastBallPos[0] = new Position(ballPos.getRow(), ballPos.getColumn());
                        System.out.printf("[match %d] tick %d RESTART BEGIN setPiece ball=(%.2f,%.2f) taker=%s%n",
                                matchIdx, tick, ballPos.getRow(), ballPos.getColumn(), ft.getLabel());
                    }

                    if (restartStartTick[0] >= 0 && taker[0] != null) {
                        double dist = Math.hypot(ballPos.getRow() - taker[0].getPosition().getRow(),
                                ballPos.getColumn() - taker[0].getPosition().getColumn());
                        // Ball movement detection
                        if (lastBallPos[0] != null) {
                            double ballMoved = Math.hypot(ballPos.getRow() - lastBallPos[0].getRow(),
                                    ballPos.getColumn() - lastBallPos[0].getColumn());
                            if (ballMoved > 0.05 && dist > 1.2 && carrier != taker[0] && !takerArrived[0]) {
                                issues[0]++;
                                System.out.printf("  !! tick %d BALL MOVED %.2f while taker=%s %.2f cells away (ballTo(%.2f,%.2f), carrier=%s)%n",
                                        tick, ballMoved, taker[0].getLabel(), dist,
                                        ballPos.getRow(), ballPos.getColumn(),
                                        carrier == null ? "null" : carrier.getLabel());
                            }
                            lastBallPos[0] = new Position(ballPos.getRow(), ballPos.getColumn());
                        }
                        boolean takerIsCarrier = carrier == taker[0];
                        if (takerIsCarrier && !takerArrived[0]) {
                            takerArrived[0] = true;
                            arriveHoldTicks[0] = 0;
                            System.out.printf("  tick %d taker ARRIVED (carrier), dist=%.2f status=%s%n",
                                    tick, dist, state.getStatus());
                        }
                        if (takerArrived[0]) {
                            arriveHoldTicks[0]++;
                            if (dist > 1.5 && carrier != taker[0]) {
                                // taker once arrived; if ball left the taker, restart taken
                                System.out.printf("  tick %d RESTART TAKEN (dist=%.2f, hold=%d) status=%s%n",
                                        tick, dist, arriveHoldTicks[0], state.getStatus());
                                restartStartTick[0] = -1;
                                taker[0] = null;
                                arriveHoldTicks[0] = 0;
                                return;
                            }
                        } else {
                            walkTicks[0]++;
                        }
                    } else {
                        restartStartTick[0] = -1;
                    }
                }
            });
            System.out.printf("match %d: free-kick/set-piece walk issues=%d%n", m, issues[0]);
            long total = homeZoneTicks[0] + awayZoneTicks[0] + middleTicks[0];
            System.out.printf("  ball in HOME att third (row>=5): %d (%.1f%%)  | AWAY att third (row<=3): %d (%.1f%%)  | middle: %d%n",
                    homeZoneTicks[0], 100.0 * homeZoneTicks[0] / total,
                    awayZoneTicks[0], 100.0 * awayZoneTicks[0] / total, middleTicks[0]);
            long poss = homePossTicks[0] + awayPossTicks[0];
            System.out.printf("  possession ticks: HOME %d (%.1f%%)  AWAY %d (%.1f%%)%n",
                    homePossTicks[0], 100.0 * homePossTicks[0] / poss,
                    awayPossTicks[0], 100.0 * awayPossTicks[0] / poss);
        }
    }
}
