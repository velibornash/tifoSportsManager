package org.example.footballmanager.demo.service.proposal;

import org.example.footballmanager.demo.service.proposal.engine.MatchOrchestrator;
import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

/**
 * MatchSimulationLauncher - main entry point for the proposal engine.
 * Builds two teams, kicks off, and runs the tick loop so we can see
 * the logs and engines working.
 * Run: mvn exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher
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
        double rowR = team.equals("HOME") ? 2.5 : 6.5;   // defender line
        double rowM = team.equals("HOME") ? 4.0 : 4.0;   // midfield line (adjusted below)
        double rowA = team.equals("HOME") ? 6.0 : 3.0;   // attacker line
        rowM = team.equals("HOME") ? rowM : 5.0;

        String[][] rolesCols = {
            {"GK",  "0.0", "3.5"},
            {"DL",  String.valueOf(rowR), "1.5"},
            {"DR",  String.valueOf(rowR), "5.5"},
            {"DCL", String.valueOf(rowR + 0.5), "2.5"},
            {"DCR", String.valueOf(rowR + 0.5), "4.5"},
            {"ML",  String.valueOf(rowM), "1.5"},
            {"MR",  String.valueOf(rowM), "5.5"},
            {"CML", String.valueOf(rowM + 0.5), "2.5"},
            {"CMR", String.valueOf(rowM + 0.5), "4.5"},
            {"STL", String.valueOf(rowA), "3.0"},
            {"STR", String.valueOf(rowA), "4.0"},
        };

        for (int i = 0; i < rolesCols.length; i++) {
            String role = rolesCols[i][0];
            double expectedRow = Double.parseDouble(rolesCols[i][1]);
            double expectedCol = Double.parseDouble(rolesCols[i][2]);
            Position pos = new Position(expectedRow, expectedCol);
            PlayerSkills skills = randomSkills(role, team);
            Player p = new Player(
                    team + "-" + (i + 1),
                    team.substring(0, 1) + (i + 1),
                    team, role, pos, pos, skills,
                    178 + (i * 3) % 20);
            state.getPlayers().add(p);
        }
    }

    private static PlayerSkills randomSkills(String role, String team) {
        // Deterministic-ish, varies per role.
        int base = (team + role).hashCode() % 6 + 12; // 12..17
        double b = base;
        return switch (role) {
            case "GK" -> new PlayerSkills(b * 0.8, b * 0.9, b, b * 0.6, b * 0.5, b * 0.7, b * 0.5, b * 0.6);
            case "DL", "DR", "DCL", "DCR" -> new PlayerSkills(b * 0.9, b, b * 0.4, b * 0.6, b * 0.6, b * 0.8, b * 0.5, b);
            case "ML", "MR" -> new PlayerSkills(b, b * 0.9, b * 0.3, b * 0.8, b * 0.9, b, b * 0.6, b * 0.6);
            case "CML", "CMR" -> new PlayerSkills(b * 0.8, b, b * 0.3, b * 0.9, b, b * 0.9, b * 0.7, b * 0.7);
            case "STL", "STR" -> new PlayerSkills(b, b * 0.9, b * 0.2, b * 0.8, b * 0.6, b * 0.7, b, b * 0.4);
            default -> PlayerSkills.neutral();
        };
    }
}