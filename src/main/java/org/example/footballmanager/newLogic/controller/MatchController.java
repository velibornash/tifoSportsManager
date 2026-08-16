package org.example.footballmanager.newLogic.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.dto.MatchEventFlatDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.service.MatchDetailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchRepository matchRepository;
    private final MatchDetailService matchDetailService;
    private final MatchPlayerStatsRepository playerStatsRepository;

    @Autowired
    public MatchController(
            MatchRepository matchRepository,
            MatchDetailService matchDetailService,
            MatchPlayerStatsRepository playerStatsRepository
    ) {
        this.matchRepository = matchRepository;
        this.matchDetailService = matchDetailService;
        this.playerStatsRepository = playerStatsRepository;
    }

    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDTO> getMatch(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(MatchDTO::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/lineups")
    public ResponseEntity<Map<String, Object>> getMatchLineups(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : null);
                    payload.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : null);

                    // Parse lineupJson if available
                    List<Map<String, Object>> homeLineup = new java.util.ArrayList<>();
                    List<Map<String, Object>> awayLineup = new java.util.ArrayList<>();
                    if (match.getLineupJson() != null && !match.getLineupJson().isBlank()) {
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            Map<String, Object> lineupData = mapper.readValue(match.getLineupJson(), Map.class);
                            Object home = lineupData.get("homeLineup");
                            if (home instanceof List) homeLineup = (List<Map<String, Object>>) home;
                            Object away = lineupData.get("awayLineup");
                            if (away instanceof List) awayLineup = (List<Map<String, Object>>) away;
                        } catch (Exception e) {
                            log.warn("Failed to parse lineupJson for matchId={}", matchId, e);
                        }
                    }
                    payload.put("homeLineup", homeLineup);
                    payload.put("awayLineup", awayLineup);
                    return ResponseEntity.ok(payload);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/detail")
    public ResponseEntity<List<MatchEventFlatDTO>> getMatchDetail(@PathVariable Long matchId) {
        try {
            List<MatchEventFlatDTO> events = matchDetailService.getMatchEventsFlat(matchId);
            return ResponseEntity.ok(events);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{matchId}/player-stats")
    public ResponseEntity<Map<String, Object>> getMatchPlayerStats(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("matchId", matchId);
                    response.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : null);
                    response.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : null);

                    List<Map<String, Object>> homeStats = new java.util.ArrayList<>();
                    List<Map<String, Object>> awayStats = new java.util.ArrayList<>();

                    List<MatchPlayerStats> stats = playerStatsRepository.findByMatchId(matchId);
                    for (MatchPlayerStats ps : stats) {
                        if (ps.getPlayer() == null) continue;
                        Map<String, Object> playerData = new java.util.LinkedHashMap<>();
                        playerData.put("playerId", ps.getPlayer().getId());
                        playerData.put("name", ps.getPlayer().getName());
                        playerData.put("position", ps.getPlayer().getPosition() != null ? ps.getPlayer().getPosition().name() : "UNK");
                        playerData.put("goals", ps.getGoals());
                        playerData.put("assists", ps.getAssists());
                        playerData.put("yellowCards", ps.getYellowCards());
                        playerData.put("redCards", ps.getRedCards());
                        playerData.put("minutesPlayed", ps.getMinutesPlayed());
                        playerData.put("rating", ps.getRating());
                        playerData.put("cleanSheet", ps.isCleanSheet());

                        boolean isHome = match.getHomeTeam() != null
                                && match.getHomeTeam().getId() != null
                                && ps.getPlayer().getTeam() != null
                                && match.getHomeTeam().getId().equals(ps.getPlayer().getTeam().getId());
                        if (isHome) homeStats.add(playerData);
                        else awayStats.add(playerData);
                    }

                    response.put("homePlayers", homeStats);
                    response.put("awayPlayers", awayStats);

                    // Find man of the match
                    int bestRating = 0;
                    Map<String, Object> manOfMatch = null;
                    for (Map<String, Object> p : homeStats) {
                        int r = (int) p.getOrDefault("rating", 0);
                        if (r > bestRating) { bestRating = r; manOfMatch = p; }
                    }
                    for (Map<String, Object> p : awayStats) {
                        int r = (int) p.getOrDefault("rating", 0);
                        if (r > bestRating) { bestRating = r; manOfMatch = p; }
                    }
                    response.put("manOfTheMatch", manOfMatch != null ? manOfMatch.get("name") : "N/A");
                    response.put("manOfTheMatchRating", bestRating);

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/preview")
    public ResponseEntity<Map<String, Object>> getMatchPreview(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Map<String, Object> preview = new LinkedHashMap<>();
                    preview.put("matchId", matchId);
                    preview.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : null);
                    preview.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : null);
                    preview.put("matchDate", match.getMatchDate());
                    preview.put("prediction", Map.of());
                    preview.put("h2h", Map.of());
                    return ResponseEntity.ok(preview);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{matchId}/report")
    public ResponseEntity<Map<String, Object>> getMatchReport(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Map<String, Object> report = new LinkedHashMap<>();
                    report.put("matchId", matchId);
                    report.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : null);
                    report.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : null);
                    report.put("homeGoals", match.getHomeGoals());
                    report.put("awayGoals", match.getAwayGoals());
                    report.put("homePossession", match.getPossessionHome());
                    report.put("awayPossession", match.getPossessionAway());

                    // Parse events to build summary
                    String summary = buildMatchSummary(match);
                    report.put("summary", summary);

                    // Add key events (goals, cards, injuries)
                    List<Map<String, Object>> keyEvents = extractKeyEvents(match);
                    report.put("keyEvents", keyEvents);

                    return ResponseEntity.ok(report);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String buildMatchSummary(Match match) {
        String home = match.getHomeTeam() != null ? match.getHomeTeam().getName() : "Home";
        String away = match.getAwayTeam() != null ? match.getAwayTeam().getName() : "Away";
        int homeGoals = match.getHomeGoals();
        int awayGoals = match.getAwayGoals();

        String result;
        if (homeGoals > awayGoals) {
            result = String.format("%s pobeđuje %s sa %d-%d.", home, away, homeGoals, awayGoals);
        } else if (awayGoals > homeGoals) {
            result = String.format("%s pobeđuje %s sa %d-%d.", away, home, awayGoals, homeGoals);
        } else {
            result = String.format("%s i %s remiziraju %d-%d.", home, away, homeGoals, awayGoals);
        }
        return result;
    }

    private List<Map<String, Object>> extractKeyEvents(Match match) {
        List<Map<String, Object>> keyEvents = new java.util.ArrayList<>();
        if (match.getEventJson() == null || match.getEventJson().isBlank()) {
            return keyEvents;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<Map<String, Object>> events = mapper.readValue(match.getEventJson(), java.util.List.class);
            for (Map<String, Object> event : events) {
                String type = String.valueOf(event.get("type"));
                if (type != null && (type.contains("GOAL") || type.contains("CARD") || type.contains("INJURY") || type.contains("SUB"))) {
                    Map<String, Object> keyEvent = new java.util.LinkedHashMap<>();
                    keyEvent.put("minute", event.get("minute"));
                    keyEvent.put("type", type);
                    keyEvent.put("teamSide", event.get("teamSide"));
                    keyEvent.put("description", formatEventDescription(event));
                    keyEvents.add(keyEvent);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse events for match report, matchId={}", match.getId(), e);
        }
        return keyEvents;
    }

    private String formatEventDescription(Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        String playerName = event.get("playerName") != null ? String.valueOf(event.get("playerName")) : "Unknown";
        String teamSide = String.valueOf(event.get("teamSide"));

        return switch (type) {
            case "GOAL" -> String.format("Gol %s (%s)", playerName, teamSide);
            case "CARD", "YELLOW_CARD" -> String.format("Žuti karton %s", playerName);
            case "RED_CARD" -> String.format("Crveni karton %s", playerName);
            case "INJURY" -> String.format("Povreda %s", playerName);
            case "SUB", "SUBSTITUTION" -> String.format("Izvršena izmena");
            default -> String.format("%s - %s", type, playerName);
        };
    }

    @GetMapping("/{matchId}/stats")
    public ResponseEntity<Map<String, Object>> getMatchStats(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("matchId", matchId);
                    stats.put("homeTeam", match.getHomeTeam() != null ? match.getHomeTeam().getName() : null);
                    stats.put("awayTeam", match.getAwayTeam() != null ? match.getAwayTeam().getName() : null);
                    stats.put("homeGoals", match.getHomeGoals());
                    stats.put("awayGoals", match.getAwayGoals());
                    stats.put("homePossession", match.getPossessionHome());
                    stats.put("awayPossession", match.getPossessionAway());

                    // Compute stats from events
                    Map<String, Integer> computedStats = computeStatsFromEvents(match);
                    stats.putAll(computedStats);

                    return ResponseEntity.ok(stats);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Integer> computeStatsFromEvents(Match match) {
        Map<String, Integer> stats = new java.util.LinkedHashMap<>();
        stats.put("homeShots", 0);
        stats.put("awayShots", 0);
        stats.put("homeShotsOnTarget", 0);
        stats.put("awayShotsOnTarget", 0);
        stats.put("homeCorners", 0);
        stats.put("awayCorners", 0);
        stats.put("homeFouls", 0);
        stats.put("awayFouls", 0);
        stats.put("homeYellowCards", 0);
        stats.put("awayYellowCards", 0);
        stats.put("homeRedCards", 0);
        stats.put("awayRedCards", 0);
        stats.put("homeOffsides", 0);
        stats.put("awayOffsides", 0);
        stats.put("homePasses", 0);
        stats.put("awayPasses", 0);

        if (match.getEventJson() == null || match.getEventJson().isBlank()) {
            return stats;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.List<Map<String, Object>> events = mapper.readValue(match.getEventJson(), java.util.List.class);

            for (Map<String, Object> event : events) {
                String type = String.valueOf(event.get("type"));
                String teamSide = event.get("teamSide") != null ? String.valueOf(event.get("teamSide")) : null;
                boolean isHome = "HOME".equals(teamSide);

                switch (type) {
                    case "SHOT" -> {
                        if (isHome) stats.merge("homeShots", 1, Integer::sum);
                        else stats.merge("awayShots", 1, Integer::sum);
                    }
                    case "SHOT_MISSED" -> {
                        if (isHome) stats.merge("homeShots", 1, Integer::sum);
                        else stats.merge("awayShots", 1, Integer::sum);
                    }
                    case "SHOT_SAVED" -> {
                        if (isHome) { stats.merge("homeShots", 1, Integer::sum); stats.merge("homeShotsOnTarget", 1, Integer::sum); }
                        else { stats.merge("awayShots", 1, Integer::sum); stats.merge("awayShotsOnTarget", 1, Integer::sum); }
                    }
                    case "GOAL" -> {
                        if (isHome) { stats.merge("homeShots", 1, Integer::sum); stats.merge("homeShotsOnTarget", 1, Integer::sum); }
                        else { stats.merge("awayShots", 1, Integer::sum); stats.merge("awayShotsOnTarget", 1, Integer::sum); }
                    }
                    case "CORNER" -> {
                        if (isHome) stats.merge("homeCorners", 1, Integer::sum);
                        else stats.merge("awayCorners", 1, Integer::sum);
                    }
                    case "FOUL" -> {
                        if (isHome) stats.merge("homeFouls", 1, Integer::sum);
                        else stats.merge("awayFouls", 1, Integer::sum);
                    }
                    case "YELLOW_CARD", "CARD" -> {
                        if (isHome) stats.merge("homeYellowCards", 1, Integer::sum);
                        else stats.merge("awayYellowCards", 1, Integer::sum);
                    }
                    case "RED_CARD" -> {
                        if (isHome) stats.merge("homeRedCards", 1, Integer::sum);
                        else stats.merge("awayRedCards", 1, Integer::sum);
                    }
                    case "OFFSIDE" -> {
                        if (isHome) stats.merge("homeOffsides", 1, Integer::sum);
                        else stats.merge("awayOffsides", 1, Integer::sum);
                    }
                    case "PASS" -> {
                        if (isHome) stats.merge("homePasses", 1, Integer::sum);
                        else stats.merge("awayPasses", 1, Integer::sum);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to compute stats from events for matchId={}", match.getId(), e);
        }

        return stats;
    }

    @PostMapping("/{matchId}/reveal")
    public ResponseEntity<Map<String, Object>> revealMatchResult(@PathVariable Long matchId) {
        return matchRepository.findById(matchId)
                .map(match -> {
                    match.setHomeResultRevealed(true);
                    match.setAwayResultRevealed(true);
                    matchRepository.save(match);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("matchId", matchId);
                    result.put("revealed", true);
                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
