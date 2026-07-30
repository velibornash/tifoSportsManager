package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.dto.MatchLineupPlayerDTO;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/match-stats")
public class MatchPlayerStatsController {

    private final MatchPlayerStatsRepository statsRepository;
    private final MatchRepository matchRepository;

    public MatchPlayerStatsController(MatchPlayerStatsRepository statsRepository, MatchRepository matchRepository) {
        this.statsRepository = statsRepository;
        this.matchRepository = matchRepository;
    }

    @GetMapping("/{matchId}")
    public List<MatchPlayerStats> getStatsByMatch(@PathVariable Long matchId) {
        return statsRepository.findByMatchId(matchId);
    }

    @GetMapping("/player/{playerId}")
    public ResponseEntity<Map<String, Object>> getStatsByPlayer(@PathVariable Long playerId) {
        List<MatchPlayerStats> stats = statsRepository.findByPlayerId(playerId);
        double averageRating100 = stats.stream()
                .mapToInt(MatchPlayerStats::getRating)
                .average()
                .orElse(0.0);
        double averageRating10 = averageRating100 / 10.0;

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerId);
        payload.put("matchesPlayed", stats.size());
        payload.put("averageRating100", Math.round(averageRating100 * 100.0) / 100.0);
        payload.put("averageRating10", stats.isEmpty() ? null : Math.round(averageRating10 * 10.0) / 10.0);
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/lineups/{matchId}")
    public ResponseEntity<Map<String, Object>> getLineupsByMatch(@PathVariable Long matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found"));

        String homeTeam = match.getHomeTeam().getName();
        String awayTeam = match.getAwayTeam().getName();

        List<MatchPlayerStats> stats = statsRepository.findByMatchId(matchId);

        List<MatchLineupPlayerDTO> home = stats.stream()
                .filter(s -> s.getPlayer() != null
                        && s.getPlayer().getTeam() != null
                        && homeTeam.equals(s.getPlayer().getTeam().getName()))
                .map(this::toLineupDto)
                .toList();

        List<MatchLineupPlayerDTO> away = stats.stream()
                .filter(s -> s.getPlayer() != null
                        && s.getPlayer().getTeam() != null
                        && awayTeam.equals(s.getPlayer().getTeam().getName()))
                .map(this::toLineupDto)
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("homeTeam", homeTeam);
        payload.put("awayTeam", awayTeam);
        payload.put("homeTeamId", match.getHomeTeam() != null ? match.getHomeTeam().getId() : null);
        payload.put("awayTeamId", match.getAwayTeam() != null ? match.getAwayTeam().getId() : null);
        payload.put("homeLineup", home);
        payload.put("awayLineup", away);
        return ResponseEntity.ok(payload);
    }

    private MatchLineupPlayerDTO toLineupDto(MatchPlayerStats stats) {
        double grade = stats.getRating();
        if (grade > 10.0) {
            grade = grade / 10.0;
        }
        grade = Math.max(1.0, Math.min(10.0, grade));

        return new MatchLineupPlayerDTO(
                stats.getPlayer().getId(),
                stats.getPlayer().getName(),
                stats.getPlayer().getPosition().name(),
                stats.getPlayer().getTeam().getName(),
                Math.round(grade * 10.0) / 10.0,
                stats.getGoals(),
                stats.getAssists(),
                stats.getYellowCards(),
                stats.getRedCards(),
                stats.getMinutesPlayed()
        );
    }
}
