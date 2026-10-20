package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.recording.MatchEvent;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Offside-restart QA. Verifies that after every confirmed offside call:
 * 1. Ball is placed at the offside spot.
 * 2. Defending-team taker walks to the ball and receives it.
 * 3. Taker plays the ball.
 *
 * Per-tick history captures ball position, carrier, setPiecePending, taker label,
 * taker position, and distance-from-taker-to-ball so we can diagnose why a taker
 * failed to arrive.
 */
public class OffsideRestartDiagnostic {

    private record TickState(
            Position ballPos,
            String carrierLabel,
            boolean setPiecePending,
            String takerLabel,
            Position takerPos,
            double takerToBallDist
    ) {}

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        MatchSimulator simulator = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        Map<Long, TickState> history = new HashMap<>();
        MatchResult result = simulator.simulate(homePlayers, awayPlayers,
                "Omladinac", "Partizan",
                new TickObserver() {
                    @Override
                    public void onTick(long tick, MatchState state) {
                        Position bp = state.getBall().getPosition();
                        Player taker = state.getFreeKickTaker();
                        Position takerPos = taker == null ? null
                                : new Position(taker.getPosition().getRow(), taker.getPosition().getColumn());
                        double distToBall = takerPos == null ? -1
                                : Math.hypot(bp.getRow() - takerPos.getRow(), bp.getColumn() - takerPos.getColumn());
                        history.put(tick, new TickState(
                            new Position(bp.getRow(), bp.getColumn()),
                            state.getBall().getCarrier() == null ? null
                                    : state.getBall().getCarrier().getLabel(),
                            state.isSetPiecePending(),
                            taker == null ? null : taker.getLabel(),
                            takerPos,
                            distToBall));
                    }
                });

        List<MatchEvent> offsides = result.events().stream()
                .filter(e -> "OFFSIDE".equals(e.type()))
                .toList();
        List<MatchEvent> confirmedOffsides = offsides.stream()
                .filter(e -> e.outcome() != null
                        && (e.outcome().contains("FREE_KICK") || e.outcome().contains("YELLOW_FLAG"))
                        && e.targetPlayerId() != null)
                .toList();
        System.out.println("=== OFFSIDE RESTART QA (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println("OFFSIDE events: " + offsides.size() + " | confirmed (free kick): " + confirmedOffsides.size());
        System.out.println();

        int badSpot = 0, noArrive = 0, noKick = 0, checked = 0;
        for (MatchEvent off : confirmedOffsides) {
            double or = off.positionRow() == null ? -1 : off.positionRow();
            double oc = off.positionColumn() == null ? -1 : off.positionColumn();
            System.out.println("  [" + clock(off.tick()) + "] OFFSIDE " + off.playerName()
                    + " (" + off.team() + ") spot=(" + fmt(or) + "," + fmt(oc) + ")");
            checked++;

            // (1) Ball at offside spot right after the call
            TickState rightAfter = null;
            for (long t = off.tick(); t <= off.tick() + 3; t++) {
                if (history.containsKey(t)) { rightAfter = history.get(t); break; }
            }
            if (rightAfter == null) {
                badSpot++;
                System.out.println("     !! no state history right after the offside");
                continue;
            }
            double spotDist = hypot(rightAfter.ballPos(), or, oc);
            if (spotDist > 0.01) {
                badSpot++;
                System.out.println("     !! ball @" + fmt(rightAfter.ballPos().getRow()) + ","
                        + fmt(rightAfter.ballPos().getColumn())
                        + " vs offside spot " + fmt(or) + "," + fmt(oc)
                        + " (dist=" + fmt(spotDist) + ")");
            } else {
                System.out.println("     ball at offside spot exactly (dist=" + fmt(spotDist) + ")");
            }

            // (2) Defending-team taker receives the ball AT the spot (within 600 ticks)
            // setPiecePending becomes false at the tick the taker receives the ball,
            // so we check BOTH phases.
            PlayerArrival arrival = null;
            for (long t = off.tick(); t <= off.tick() + 600; t++) {
                TickState ts = history.get(t);
                if (ts == null || ts.carrierLabel() == null) continue;
                double d = hypot(ts.ballPos(), or, oc);
                if (d <= 0.01 && !ts.carrierLabel().equals(off.playerName())) {
                    arrival = new PlayerArrival(ts.carrierLabel(), t);
                    break;
                }
            }
            if (arrival == null) {
                for (long t = off.tick(); t <= off.tick() + 600; t++) {
                    TickState ts = history.get(t);
                    if (ts == null || ts.carrierLabel() == null) continue;
                    double d = hypot(ts.ballPos(), or, oc);
                    if (d <= 0.6 && !ts.carrierLabel().equals(off.playerName())) {
                        arrival = new PlayerArrival(ts.carrierLabel(), t);
                        break;
                    }
                }
            }
            if (arrival == null) {
                noArrive++;
                System.out.println("     !! no defending taker received the ball within 600 ticks");
            } else {
                System.out.println("     taker " + arrival.label + " received ball at tick " + arrival.tick);
            }

            // (3) Taker plays the ball within 30 ticks
            if (arrival != null) {
                boolean kicked = false;
                for (long t = arrival.tick; t <= arrival.tick + 30; t++) {
                    TickState ts = history.get(t);
                    if (ts == null) continue;
                    double d = hypot(ts.ballPos(), or, oc);
                    boolean switched = ts.carrierLabel() != null && !ts.carrierLabel().equals(arrival.label);
                    if (d > 1.5 || (switched && ts.ballPos().getRow() != or)) {
                        kicked = true;
                        break;
                    }
                }
                if (!kicked) {
                    noKick++;
                    System.out.println("     !! restart NOT executed (ball stayed at spot)");
                } else {
                    System.out.println("     restart executed (taker played the ball)");
                }
            } else {
                noKick++;
            }
        }
        System.out.println();
        System.out.printf("checked=%d badSpot=%d noArrive=%d noKick=%d%n",
                checked, badSpot, noArrive, noKick);
        boolean pass = checked > 0 && badSpot == 0 && noArrive == 0 && noKick == 0;
        System.out.println(pass
                ? "PASS"
                : "FAIL");
    }

    private record PlayerArrival(String label, long tick) {}

    private static double hypot(Position p, double row, double col) {
        return Math.hypot(p.getRow() - row, p.getColumn() - col);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    private static String clock(long tick) {
        int s = (int) (tick / 2);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
