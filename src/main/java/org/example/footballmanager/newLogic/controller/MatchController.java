package org.example.footballmanager.newLogic.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.MatchDTO;
import org.example.footballmanager.newLogic.dto.MatchEventFlatDTO;
import org.example.footballmanager.newLogic.model.Match;
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

    @Autowired
    public MatchController(
            MatchRepository matchRepository,
            MatchDetailService matchDetailService
    ) {
        this.matchRepository = matchRepository;
        this.matchDetailService = matchDetailService;
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
                    // TODO: Add actual lineup data when available
                    payload.put("homeLineup", List.of());
                    payload.put("awayLineup", List.of());
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
                    report.put("summary", "Match report not yet implemented");
                    return ResponseEntity.ok(report);
                })
                .orElse(ResponseEntity.notFound().build());
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
