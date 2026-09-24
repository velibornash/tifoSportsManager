package org.example.footballmanager.newLogic.sim.util;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;

/**
 * Builds the two synthetic 4-4-2 squads the sim engine plays. Team ids stay
 * HOME / AWAY (the engine keys every side by those ids); display names are set
 * separately on the stats collector so the real DB team names reach the report.
 */
public final class SimTeamFactory {

    private SimTeamFactory() {}

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

    public static PlayerSkills randomSkills(String role, String team) {
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