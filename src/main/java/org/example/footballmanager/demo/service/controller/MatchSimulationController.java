package org.example.footballmanager.demo.service.controller;

import org.example.footballmanager.demo.service.engine.SimulationRandom;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.PlayerSkills;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;
import org.example.footballmanager.demo.service.tactics.TacticalPerspectiveTransformer;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for match simulation.
 * POST /api/service/match/simulate — runs a full match with generated players.
 */
@RestController
@RequestMapping("/api/service/match")
@CrossOrigin(origins = "*")
public class MatchSimulationController {

    /**
     * Simulate a full match between two teams.
     * Players are generated with random skills if not provided.
     */
    @PostMapping("/simulate")
    public Map<String, Object> simulateMatch(@RequestBody(required = false) Map<String, Object> request) {
        long seed = System.nanoTime();
        if (request != null && request.containsKey("seed")) {
            seed = ((Number) request.get("seed")).longValue();
        }

        String homeTeam = "Home FC";
        String awayTeam = "Away United";
        if (request != null) {
            if (request.containsKey("homeTeam")) homeTeam = (String) request.get("homeTeam");
            if (request.containsKey("awayTeam")) awayTeam = (String) request.get("awayTeam");
        }

        List<Player> homePlayers = generateTeam("HOME", homeTeam);
        List<Player> awayPlayers = generateTeam("AWAY", awayTeam);

        MatchSimulator simulator = new MatchSimulator(seed);
        MatchResult result = simulator.simulate(homePlayers, awayPlayers, homeTeam, awayTeam);

        return resultToMap(result);
    }

    /**
     * Simulate with custom lineups.
     */
    @PostMapping("/simulate-custom")
    public Map<String, Object> simulateCustom(@RequestBody Map<String, Object> request) {
        long seed = System.nanoTime();
        if (request.containsKey("seed")) {
            seed = ((Number) request.get("seed")).longValue();
        }

        String homeTeam = (String) request.getOrDefault("homeTeam", "Home FC");
        String awayTeam = (String) request.getOrDefault("awayTeam", "Away United");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> homeLineup = (List<Map<String, Object>>) request.get("homeLineup");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> awayLineup = (List<Map<String, Object>>) request.get("awayLineup");

        List<Player> homePlayers = parseLineup(homeLineup, "HOME");
        List<Player> awayPlayers = parseLineup(awayLineup, "AWAY");

        MatchSimulator simulator = new MatchSimulator(seed);
        MatchResult result = simulator.simulate(homePlayers, awayPlayers, homeTeam, awayTeam);

        return resultToMap(result);
    }

    public static List<Player> generateTeam(String teamSide, String teamName) {
        return generateTeam(teamSide, teamName, teamName.hashCode());
    }

    /** Generate a team where all players have maximum skills (20). Used for testing. */
    public static List<Player> generateMaxSkillTeam(String teamSide, String teamName) {
        String[] roles = {"GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CMR", "MR", "STL", "STR"};
        // Cell-center tactical positions (matching FormationSlotCatalog CELL_xx_xx):
        // GK(1.5,3.5), DEF(2.5,1.5-5.5), MID(3.5,1.5-5.5), ATT(5.5,2.5-4.5).
        // Mirror formula 9-row / 7-col correctly maps cell centers:
        //   GK 1.5->7.5, DEF 2.5->6.5, MID 3.5->5.5, ATT 5.5->3.5 — all on-field.
        double[] tacticalRows = {1.5, 2.5, 2.5, 2.5, 2.5, 3.5, 3.5, 3.5, 3.5, 5.5, 5.5};
        double[] tacticalCols = {3.5, 1.5, 2.5, 4.5, 5.5, 1.5, 2.5, 4.5, 5.5, 2.5, 4.5};
        List<Player> players = new ArrayList<>();
        PlayerSkills maxSkills = new PlayerSkills(20, 20, 20, 20, 20, 20, 20, 20);
        for (int i = 0; i < 11; i++) {
            String role = roles[i];
            double tacticalRow = tacticalRows[i];
            double tacticalCol = tacticalCols[i];
            Position tacticalPos = new Position(tacticalRow, tacticalCol);
            Position pos = TacticalPerspectiveTransformer.toPhysical(tacticalPos, teamSide);
            players.add(new Player(
                    teamSide.charAt(0) + "-" + (i + 1),
                    teamName + " " + (i + 1),
                    teamSide, role, pos, pos, maxSkills, 180
            ));
        }
        return players;
    }

    /** Generate a team where all players have the same skill level. Used for controlled tests. */
    public static List<Player> generateTeamWithSkill(String teamSide, String teamName, int skill) {
        String[] roles = {"GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CMR", "MR", "STL", "STR"};
        // Cell-center tactical positions (matching FormationSlotCatalog).
        double[] tacticalRows = {1.5, 2.5, 2.5, 2.5, 2.5, 3.5, 3.5, 3.5, 3.5, 5.5, 5.5};
        double[] tacticalCols = {3.5, 1.5, 2.5, 4.5, 5.5, 1.5, 2.5, 4.5, 5.5, 2.5, 4.5};
        List<Player> players = new ArrayList<>();
        PlayerSkills uniformSkills = new PlayerSkills(skill, skill, skill, skill, skill, skill, skill, skill);
        for (int i = 0; i < 11; i++) {
            String role = roles[i];
            double tacticalRow = tacticalRows[i];
            double tacticalCol = tacticalCols[i];
            Position tacticalPos = new Position(tacticalRow, tacticalCol);
            Position pos = TacticalPerspectiveTransformer.toPhysical(tacticalPos, teamSide);
            players.add(new Player(
                    teamSide.charAt(0) + "-" + (i + 1),
                    teamName + " " + (i + 1),
                    teamSide, role, pos, pos, uniformSkills, 180
            ));
        }
        return players;
    }

    public static List<Player> generateTeam(String teamSide, String teamName, long skillSeed) {
        // Positioning mirrors DemoScenario.standard() — the authoritative kickoff layout.
        // HOME: GK(1.5,3.5), DEF(2.5,1.5-5.5), MID(3.5,1.5-5.5), ATT(5.5,2.5-4.5)
        // AWAY: mirrored via TacticalPerspectiveTransformer (9-row, 7-col).
        // Cell-center positions (1.5, 2.5, 3.5, 5.5) mirror to on-field positions:
        //   GK 1.5->7.5, DEF 2.5->6.5, MID 3.5->5.5, ATT 5.5->3.5.
        // Attackers at row 5.5 (just past center) — symmetric with AWAY at 3.5,
        // prevents instant offside when AWAY defenders push up from row 6.5.
        String[] roles = {"GK", "DL", "DCL", "DCR", "DR", "ML", "CML", "CMR", "MR", "STL", "STR"};
        String[] roleLines = {"GK", "DEF", "DEF", "DEF", "DEF", "MID", "MID", "MID", "MID", "ATT", "ATT"};
        double[] tacticalRows = {1.5, 2.5, 2.5, 2.5, 2.5, 3.5, 3.5, 3.5, 3.5, 5.5, 5.5};
        double[] tacticalCols = {3.5, 1.5, 2.5, 4.5, 5.5, 1.5, 2.5, 4.5, 5.5, 2.5, 4.5};
        List<Player> players = new ArrayList<>();
        Random rng = new Random(skillSeed);

        for (int i = 0; i < 11; i++) {
            String role = roles[i];
            String roleLine = roleLines[i];
            double tacticalRow = tacticalRows[i];
            double tacticalCol = tacticalCols[i];

            PlayerSkills skills = randomSkills(roleLine, rng);
            Position tacticalPos = new Position(tacticalRow, tacticalCol);
            Position pos = TacticalPerspectiveTransformer.toPhysical(tacticalPos, teamSide);
            players.add(new Player(
                    teamSide.charAt(0) + "-" + (i + 1),
                    teamName + " " + (i + 1),
                    teamSide, role, pos, pos, skills, 175 + rng.nextInt(15)
            ));
        }
        return players;
    }

    private static PlayerSkills randomSkills(String roleLine, Random rng) {
        // Default skill baseline for MatchViewer: all players start at 14 with
        // ±2 random variation per attribute, plus role-specific bonuses so the
        // match feels balanced (GKs better at keeper, ATT better at striker, etc.).
        return switch (roleLine) {
            case "GK" -> new PlayerSkills(
                    14 + rng.nextInt(5) - 2, 14 + rng.nextInt(5) - 2,
                    16 + rng.nextInt(5) - 2, 12 + rng.nextInt(5) - 2,
                    12 + rng.nextInt(5) - 2, 10 + rng.nextInt(5) - 2,
                    4 + rng.nextInt(4), 14 + rng.nextInt(5) - 2
            );
            case "DEF" -> new PlayerSkills(
                    14 + rng.nextInt(5) - 2, 14 + rng.nextInt(5) - 2,
                    6 + rng.nextInt(4), 12 + rng.nextInt(5) - 2,
                    12 + rng.nextInt(5) - 2, 12 + rng.nextInt(5) - 2,
                    6 + rng.nextInt(4), 16 + rng.nextInt(5) - 2
            );
            case "MID" -> new PlayerSkills(
                    14 + rng.nextInt(5) - 2, 14 + rng.nextInt(5) - 2,
                    6 + rng.nextInt(4), 14 + rng.nextInt(5) - 2,
                    16 + rng.nextInt(5) - 2, 14 + rng.nextInt(5) - 2,
                    10 + rng.nextInt(5) - 2, 10 + rng.nextInt(5) - 2
            );
            default /* ATT */ -> new PlayerSkills(
                    14 + rng.nextInt(5) - 2, 14 + rng.nextInt(5) - 2,
                    4 + rng.nextInt(4), 14 + rng.nextInt(5) - 2,
                    14 + rng.nextInt(5) - 2, 12 + rng.nextInt(5) - 2,
                    16 + rng.nextInt(5) - 2, 8 + rng.nextInt(4)
            );
        };
    }

    private static String normalizeRole(String role) {
        if (role == null) return "CML";
        return switch (role.toUpperCase()) {
            case "GK", "GOALIE" -> "GK";
            case "DL" -> "DL";
            case "DCL", "DC" -> "DCL";
            case "DCR" -> "DCR";
            case "DR" -> "DR";
            case "DEF", "LB", "CB", "RB" -> "DCR";
            case "ML" -> "ML";
            case "CML" -> "CML";
            case "CM" -> "CML";
            case "CMR" -> "CMR";
            case "MR" -> "MR";
            case "MID", "LM", "RM" -> "CML";
            case "STL" -> "STL";
            case "STR", "ST", "ATT", "FW" -> "STR";
            default -> role;
        };
    }

    private static String roleLineFor(String roleKey) {
        if (roleKey == null) return "MID";
        return switch (roleKey) {
            case "GK" -> "GK";
            case "DL", "DCL", "DCR", "DR" -> "DEF";
            case "ML", "CML", "CMR", "MR" -> "MID";
            case "STL", "STR" -> "ATT";
            default -> "ATT";
        };
    }

    private List<Player> parseLineup(List<Map<String, Object>> lineup, String teamSide) {
        List<Player> players = new ArrayList<>();
        if (lineup == null) return generateTeam(teamSide, teamSide);

        for (int i = 0; i < lineup.size(); i++) {
            Map<String, Object> p = lineup.get(i);
            String name = (String) p.getOrDefault("name", teamSide + " " + (i + 1));
            String role = normalizeRole((String) p.getOrDefault("role", "CML"));

            PlayerSkills skills;
            if (p.containsKey("skills")) {
                @SuppressWarnings("unchecked")
                Map<String, Number> s = (Map<String, Number>) p.get("skills");
                skills = new PlayerSkills(
                        s.getOrDefault("pace", 10).intValue(),
                        s.getOrDefault("stamina", 12).intValue(),
                        s.getOrDefault("keeper", 5).intValue(),
                        s.getOrDefault("technique", 10).intValue(),
                        s.getOrDefault("playmaking", 10).intValue(),
                        s.getOrDefault("passing", 10).intValue(),
                        s.getOrDefault("striker", 8).intValue(),
                        s.getOrDefault("defender", 8).intValue()
                );
            } else {
                skills = randomSkills(roleLineFor(role), new Random());
            }

            Position pos = TacticalPerspectiveTransformer.toPhysical(new Position(3, 3), teamSide);
            players.add(new Player(
                    teamSide.charAt(0) + "-" + (i + 1), name,
                    teamSide, role, pos, pos, skills
            ));
        }

        // Pad to 11 if needed
        while (players.size() < 11) {
            int i = players.size();
            players.add(new Player(
                    teamSide.charAt(0) + "-" + (i + 1),
                    teamSide + " Player " + (i + 1),
                    teamSide, "CML",
                    TacticalPerspectiveTransformer.toPhysical(new Position(3, 3), teamSide),
                    TacticalPerspectiveTransformer.toPhysical(new Position(3, 3), teamSide),
                    randomSkills("MID", new Random())
            ));
        }

        return players;
    }

    private Map<String, Object> resultToMap(MatchResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("homeTeam", result.homeTeamName());
        map.put("awayTeam", result.awayTeamName());
        map.put("homeGoals", result.homeGoals());
        map.put("awayGoals", result.awayGoals());
        map.put("finalScore", result.finalScore());
        map.put("seed", result.seed());
        map.put("matchId", result.matchId());

        // Replay data for the web match viewer (display only)
        map.put("events", result.events());
        map.put("snapshots", result.snapshots());

        // Lineups
        map.put("homeLineup", result.homeLineup().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.name());
            m.put("role", l.role());
            m.put("number", l.number());
            m.put("skills", Map.of(
                    "pace", l.skills().pace(),
                    "stamina", l.skills().stamina(),
                    "keeper", l.skills().keeper(),
                    "technique", l.skills().technique(),
                    "playmaking", l.skills().playmaking(),
                    "passing", l.skills().passing(),
                    "striker", l.skills().striker(),
                    "defender", l.skills().defender()
            ));
            return m;
        }).toList());

        map.put("awayLineup", result.awayLineup().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", l.name());
            m.put("role", l.role());
            m.put("number", l.number());
            m.put("skills", Map.of(
                    "pace", l.skills().pace(),
                    "stamina", l.skills().stamina(),
                    "keeper", l.skills().keeper(),
                    "technique", l.skills().technique(),
                    "playmaking", l.skills().playmaking(),
                    "passing", l.skills().passing(),
                    "striker", l.skills().striker(),
                    "defender", l.skills().defender()
            ));
            return m;
        }).toList());

        // Team stats
        map.put("homeStats", teamStatsToMap(result.homeStats()));
        map.put("awayStats", teamStatsToMap(result.awayStats()));

        // Goals
        map.put("goals", result.goals().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("minute", g.minute());
            m.put("scorer", g.scorerName());
            m.put("team", g.scorerTeam());
            m.put("assistant", g.assistantName());
            m.put("score", g.scoreString());
            m.put("description", g.description());
            return m;
        }).toList());

        // Player stats
        map.put("homePlayerStats", result.homePlayerStats().stream().map(this::playerStatsToMap).toList());
        map.put("awayPlayerStats", result.awayPlayerStats().stream().map(this::playerStatsToMap).toList());

        // Report
        map.put("report", Map.of(
                "headline", result.report().headline(),
                "summary", result.report().summary(),
                "manOfTheMatch", result.report().manOfTheMatch(),
                "keyEvents", result.report().keyEvents(),
                "homePossession", result.report().homePossession(),
                "awayPossession", result.report().awayPossession()
        ));

        return map;
    }

    private Map<String, Object> teamStatsToMap(org.example.footballmanager.demo.service.result.TeamMatchStats stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team", stats.teamName());
        m.put("goals", stats.goals());
        m.put("shots", stats.shots());
        m.put("shotsOnTarget", stats.shotsOnTarget());
        m.put("passesAttempted", stats.passesAttempted());
        m.put("passesCompleted", stats.passesCompleted());
        m.put("passAccuracy", stats.passAccuracy());
        m.put("fouls", stats.fouls());
        m.put("penalties", stats.penalties());
        m.put("yellowCards", stats.yellowCards());
        m.put("redCards", stats.redCards());
        m.put("corners", stats.corners());
        m.put("offsides", stats.offsides());
        m.put("possession", stats.possessionPercent());
        return m;
    }

    private Map<String, Object> playerStatsToMap(org.example.footballmanager.demo.service.result.PlayerMatchStats stats) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", stats.playerName());
        m.put("role", stats.role());
        m.put("goals", stats.goals());
        m.put("assists", stats.assists());
        m.put("shots", stats.shots());
        m.put("shotsOnTarget", stats.shotsOnTarget());
        m.put("passesAttempted", stats.passesAttempted());
        m.put("passesCompleted", stats.passesCompleted());
        m.put("passAccuracy", stats.passAccuracy());
        m.put("tackles", stats.tackles());
        m.put("interceptions", stats.interceptions());
        m.put("foulsCommitted", stats.foulsCommitted());
        m.put("yellowCards", stats.yellowCards());
        m.put("redCards", stats.redCards());
        m.put("minutesPlayed", stats.minutesPlayed());
        m.put("rating", stats.rating());
        return m;
    }
}
