package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.example.footballmanager.newLogic.sim.model.*;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.example.footballmanager.newLogic.sim.util.SimUtils;

/**
 * MatchSimulationLauncher - main entry point for the proposal engine.
 * Builds two teams, kicks off, and runs the tick loop so we can see
 * the logs and engines working.
 * Run: mvn exec:java -Dexec.mainClass=org.example.footballmanager.newLogic.sim.MatchSimulationLauncher
 */
public class MatchSimulationLauncher {

    public static void main(String[] args) {
        int ticks = 480; // default 12 match-minutes
        if (args.length > 0) {
            ticks = Integer.parseInt(args[0]);
        }

        System.out.println("=== TIFO Proposal Engine Launcher ===");
        System.out.println("Simulating " + ticks + " ticks (" + (ticks / 40.0) + " match-minutes)\n");

        MatchState state = new MatchState();
        addTeam(state, "HOME");
        addTeam(state, "AWAY");

        MatchOrchestrator orchestrator = new MatchOrchestrator(state);
        // Kickoff: all players on their own half, kicker on the center spot (4.5, 4.0).
        orchestrator.getRestartManager().handleKickoff(state, "HOME");

        // Record round-start positions for pace-based movement.
        for (Player p : state.getPlayers()) {
            state.setRoundStartPosition(p.getId(), p.getPosition());
            state.setRoundPaceSkill(p.getId(), (int) Math.round(p.getSkills().pace()));
        }

        System.out.println("[0:00|LCH] KICKOFF " + state.getCarrier().getTeam()
                + " - " + state.getCarrier().getLabel()
                + "(" + state.getCarrier().getRole() + ")"
                + " at center" + SimUtils.formatPos(state.getCarrier().getPosition())
                + " ball" + SimUtils.formatPos(state.getBall().getPosition()));
        System.out.println("  HOME : " + formatTeam(state, "HOME"));
        System.out.println("  AWAY : " + formatTeam(state, "AWAY") + "\n");

        orchestrator.simulate(ticks);

        System.out.println("\n=== SUMMARY ===");
        System.out.println("Ticks simulated : " + state.getMatchTicks());
        System.out.println("Score           : HOME " + state.getHomeGoals()
                + " : " + state.getAwayGoals() + " AWAY");
        System.out.println("Shots           : " + state.getShots()
                + " (on target " + state.getShotsOnTarget() + ")");
        System.out.println("Pass attempts   : " + state.getPassAttempts()
                + " (completed " + state.getPassesCompleted() + ")");
        System.out.println("Fouls           : " + state.getFouls());
        System.out.println("Cards           : Y " + state.getYellowCards()
                + " / R " + state.getRedCards());
        System.out.println("Decision events logged: " +
                orchestrator.getEventLog().stream()
                        .filter(line -> line.contains("DECISION")).count());
        System.out.println("\nPlayer positions at end:");
        System.out.println("  HOME : " + formatTeam(state, "HOME"));
        System.out.println("  AWAY : " + formatTeam(state, "AWAY"));

        printOutcome(orchestrator);
    }

    private static void printOutcome(MatchOrchestrator orchestrator) {
        ProposalMatchOutcome outcome = orchestrator.buildOutcome();
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(outcome);
            System.out.println("\n=== MATCH OUTCOME (JSON) ===");
            System.out.println(json);
        } catch (Exception e) {
            System.err.println("Failed to serialize outcome: " + e.getMessage());
        }
    }

    private static String formatTeam(MatchState state, String team) {
        StringBuilder sb = new StringBuilder();
        for (Player p : state.getPlayers()) {
            if (!p.getTeam().equals(team)) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(p.getLabel()).append("(").append(p.getRole()).append(")")
              .append(SimUtils.formatPos(p.getPosition()));
        }
        return sb.toString();
    }

    public static void addTeam(MatchState state, String team) {
        SimTeamFactory.addTeam(state, team);
    }
}