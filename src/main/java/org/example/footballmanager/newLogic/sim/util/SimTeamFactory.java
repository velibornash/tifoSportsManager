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

    /**
     * Squad skills for a role — an EXPLICIT profile, identical for both teams.
     *
     * Two bugs lived here.
     *
     * 1. The base skill was derived from {@code (team + role).hashCode()}, so
     *    the two squads were not mirror images: HOME's wide midfielders came out
     *    at base 12 and AWAY's at 15 (HOME's fullbacks 15 vs AWAY's 12). Since
     *    {@code DuelEngine} resolves a duel by comparing one power number,
     *    mirrored midfield matchups became "AWAY wins 100% / HOME wins 0%",
     *    which produced the measured 35%/65% possession split and the entire
     *    downstream goals cascade (AWAY 7.3 shots but 0.08 goals/match).
     *
     * 2. The per-role numbers were a magic string hash, so the resulting skill
     *    profile was accidental: swapping the hash to role-only produced strong
     *    strikers (15) against weak centre-backs (13) and a weak keeper (14),
     *    which blew the match up to 128 shots / 18.6 goals per match. A skill
     *    profile that decides the match must be written down, not hashed.
     *
     * The profile below is a competent, unremarkable 4-4-2 side: a real keeper,
     * real defenders, midfielders who pass, strikers who finish. Both teams get
     * exactly the same numbers, so the engine decides the match, not the squad
     * generator. Per-player variety inside a team comes from the match seed
     * (skill rolls in the engine), not from a different profile per side.
     */
    public static PlayerSkills randomSkills(String role, String team) {
        return switch (role) {
            case "GK" -> new PlayerSkills(11, 13, 17, 9, 9, 11, 6, 8);
            case "DL", "DR" -> new PlayerSkills(14, 15, 5, 12, 10, 12, 7, 16);
            case "DCL", "DCR" -> new PlayerSkills(12, 15, 5, 11, 10, 13, 6, 17);
            case "ML", "MR" -> new PlayerSkills(16, 14, 4, 14, 13, 13, 9, 11);
            case "CML", "CMR" -> new PlayerSkills(12, 15, 4, 14, 14, 16, 10, 12);
            case "STL", "STR" -> new PlayerSkills(15, 14, 4, 14, 12, 11, 16, 8);
            default -> PlayerSkills.neutral();
        };
    }
}