package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.ActionType;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies carry continuity: across the whole match, whenever the SAME player
 * is the ball carrier for consecutive ticks while a CARRY action is active,
 * their position must advance EVERY tick (no stationary stall at the end of a
 * carry, where the target was cleared for a tick).
 *
 * Reports:
 *   - max stationary-while-carrying run (ticks of identical position)
 *   - count of "carrier-with-no-active-action" ticks (excluding restart holds)
 *   - number of CARRY_CONTINUED events (continuous-run re-targets)
 */
public class CarryContinuityDiagnostic {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
        MatchSimulator simulator = new MatchSimulator(seed);
        var homePlayers = MatchSimulationController.generateTeam("HOME", "Omladinac");
        var awayPlayers = MatchSimulationController.generateTeam("AWAY", "Partizan");

        List<TickInfo> ticks = new ArrayList<>();
        TickObserver observer = (tick, state) -> {
            org.example.footballmanager.demo.service.model.Player c = state.getCarrier();
            Long carrierId = c != null ? Long.valueOf(c.getId().hashCode()) : null;
            ActionType at = state.getAction() != null ? state.getAction().getType() : null;
            boolean targetSet = c != null && c.getTarget() != null;
            double row = c != null ? c.getPosition().getRow() : -1;
            double col = c != null ? c.getPosition().getColumn() : -1;
            ticks.add(new TickInfo(carrierId, at, targetSet, row, col));
        };
        MatchResult result = simulator.simulate(homePlayers, awayPlayers,
                "Omladinac", "Partizan", observer);

        int carryTicks = 0;
        int stallTicks = 0;
        int maxStallRun = 0;
        int currentStallRun = 0;
        int carrierNoTargetTicks = 0;
        long lastCarrierId = -1;
        double lastRow = -1;
        double lastCol = -1;
        int continuedCarries = 0;

        for (TickInfo t : ticks) {
            if (t.carrierId == null) { currentStallRun = 0; lastCarrierId = -1; continue; }
            boolean sameCarrier = t.carrierId == lastCarrierId;
            lastCarrierId = t.carrierId;

            if (t.actionType == ActionType.CARRY && !t.targetSet) {
                carrierNoTargetTicks++; // should be impossible while carrying
            }

            if (sameCarrier && t.actionType != null && t.actionType == ActionType.CARRY) {
                carryTicks++;
                double moved = Math.abs(t.row - lastRow) + Math.abs(t.col - lastCol);
                if (moved < 1e-9) {
                    stallTicks++;
                    currentStallRun++;
                    maxStallRun = Math.max(maxStallRun, currentStallRun);
                } else {
                    currentStallRun = 0;
                }
            } else {
                currentStallRun = 0;
            }
            lastRow = t.row;
            lastCol = t.col;
            if (t.actionType == ActionType.CARRY && t.targetSet) continuedCarries++;
        }

        long carryContinuedEvents = result.events().stream()
                .filter(e -> "CARRY_CONTINUED".equals(e.type()))
                .count();

        System.out.println("=== CARRY CONTINUITY (seed=" + seed + ") ===");
        System.out.println("Final score: " + result.finalScore());
        System.out.println("Carry ticks (same carrier, CARRY active): " + carryTicks);
        System.out.println("Stationary ticks while carrying: " + stallTicks);
        System.out.println("Max consecutive stationary-carry run: " + maxStallRun);
        System.out.println("Carrier-with-null-target during CARRY action: " + carrierNoTargetTicks);
        System.out.println("CARRY_CONTINUED events: " + carryContinuedEvents);
        System.out.println();
        System.out.println(maxStallRun <= 1 && stallTicks == 0
                ? "PASS — no stationary tick between consecutive carries"
                : "FAIL — carrier stalled " + stallTicks + " ticks (max run " + maxStallRun + ")");
    }

    private static final class TickInfo {
        Long carrierId;
        ActionType actionType;
        boolean targetSet;
        double row, col;

        TickInfo(Long carrierId, ActionType actionType, boolean targetSet, double row, double col) {
            this.carrierId = carrierId;
            this.actionType = actionType;
            this.targetSet = targetSet;
            this.row = row;
            this.col = col;
        }
    }
}