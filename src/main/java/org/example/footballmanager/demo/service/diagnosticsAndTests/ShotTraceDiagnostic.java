package org.example.footballmanager.demo.service.diagnosticsAndTests;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.controller.MatchSimulationController;
import org.example.footballmanager.demo.service.model.ActionType;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.TickObserver;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;

/** Temporary: trace every SHOT action and whether it arrives on target. */
public class ShotTraceDiagnostic {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 1000;
        MatchSimulator simulator = new MatchSimulator(seed);
        var home = MatchSimulationController.generateTeamWithSkill("HOME", "Omladinac", 14);
        var away = MatchSimulationController.generateTeamWithSkill("AWAY", "Partizan", 14);

        final int[] shots = {0};
        MatchResult result = simulator.simulate(home, away, "Omladinac", "Partizan",
                new TickObserver() {
                    @Override
                    public void onTick(long tick, MatchState state) {
                        if (state.getAction() != null
                                && state.getAction().getType() == ActionType.SHOT) {
                            Position ball = state.getBall().getPosition();
                            Position target = state.getAction().getActualTarget();
                            Player carrier = state.getCarrier();
                            shots[0]++;
                            if (shots[0] <= 15) {
                                Position bt = state.getBall().getTarget();
                                System.out.println("SHOT-TICK t=" + tick
                                        + " ball=(" + String.format("%.2f", ball.getRow())
                                        + "," + String.format("%.2f", ball.getColumn()) + ")"
                                        + " ballTarget=" + (bt == null ? "NULL"
                                        : String.format("%.2f,%.2f", bt.getRow(), bt.getColumn()))
                                        + " actionActualTarget=(" + (target == null ? "null"
                                        : String.format("%.2f,%.2f", target.getRow(), target.getColumn())) + ")"
                                        + " carrier=" + (carrier == null ? "NULL" : carrier.getLabel() + "(" + carrier.getTeam() + ")")
                                        + " onTarget(goal)=" + state.getAction().isGoodExecution());
                            }
                        }
                    }
                });
        System.out.println("=== TOTAL SHOT ticks observed: " + shots[0] + " ===");
        System.out.println("Goals: " + result.goals().size());
    }
}
