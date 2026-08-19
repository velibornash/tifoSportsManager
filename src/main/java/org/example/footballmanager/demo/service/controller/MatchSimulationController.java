package org.example.footballmanager.demo.service.controller;

import org.example.footballmanager.demo.service.engine.SimulationRandom;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.PlayerSkills;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.result.MatchResult;
import org.example.footballmanager.demo.service.result.MatchSimulator;
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

    private List<Player> generateTeam(String teamSide, String teamName) {
        String[] roles = {"GK", "DEF", "DEF", "DEF", "DEF", "MID", "MID", "MID", "MID", "ATT", "ATT"};
        List<Player> players = new ArrayList<>();
        Random rng = new Random(teamName.hashCode());

        for (int i = 0; i < 11; i++) {
            String role = roles[i];
            int row, col;
            if ("GK".equals(role)) { row = 0; col = 3; }
            else if ("DEF".equals(role)) { row = 1; col = 1 + i % 4; }
            else if ("MID".equals(role)) { row = 3; col = 1 + i % 4; }
            else { row = 5; col = 2 + i % 3; }

            PlayerSkills skills = randomSkills(role, rng);
            Position pos = new Position(row, col);
            players.add(new Player(
                    teamSide.charAt(0) + "-" + (i + 1),
                    teamName + " " + (i + 1),
                    teamSide, role, pos, pos, skills, 175 + rng.nextInt(15)
            ));
        }
        return players;
    }

    private PlayerSkills randomSkills(String role, Random rng) {
        return switch (role) {
            case "GK" -> new PlayerSkills(
                    10 + rng.nextInt(8), 12 + rng.nextInt(6),
                    14 + rng.nextInt(6), 8 + rng.nextInt(8),
                    8 + rng.nextInt(8), 6 + rng.nextInt(8),
                    2 + rng.nextInt(6), 10 + rng.nextInt(8)
            );
            case "DEF" -> new PlayerSkills(
                    10 + rng.nextInt(8), 12 + rng.nextInt(6),
                    4 + rng.nextInt(6), 8 + rng.nextInt(8),
                    8 + rng.nextInt(8), 8 + rng.nextInt(8),
                    4 + rng.nextInt(6), 12 + rng.nextInt(8)
            );
            case "MID" -> new PlayerSkills(
                    10 + rng.nextInt(8), 12 + rng.nextInt(6),
                    4 + rng.nextInt(6), 10 + rng.nextInt(8),
                    12 + rng.nextInt(8), 10 + rng.nextInt(8),
                    8 + rng.nextInt(8), 8 + rng.nextInt(8)
            );
            default -> new PlayerSkills(
                    12 + rng.nextInt(8), 10 + rng.nextInt(8),
                    2 + rng.nextInt(4), 10 + rng.nextInt(8),
                    10 + rng.nextInt(8), 8 + rng.nextInt(8),
                    12 + rng.nextInt(8), 6 + rng.nextInt(6)
            );
        };
    }

    private List<Player> parseLineup(List<Map<String, Object>> lineup, String teamSide) {
        List<Player> players = new ArrayList<>();
        if (lineup == null) return generateTeam(teamSide, teamSide);

        for (int i = 0; i < lineup.size(); i++) {
            Map<String, Object> p = lineup.get(i);
            String name = (String) p.getOrDefault("name", teamSide + " " + (i + 1));
            String role = (String) p.getOrDefault("role", "MID");

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
                skills = randomSkills(role, new Random());
            }

            Position pos = new Position(3, 3);
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
                    teamSide, "MID", new Position(3, 3), new Position(3, 3),
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
