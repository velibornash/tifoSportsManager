package org.example.footballmanager.controller;

import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.repository.MatchPlayerStatsRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/match-stats")
public class MatchPlayerStatsController {

    private final MatchPlayerStatsRepository statsRepository;

    public MatchPlayerStatsController(MatchPlayerStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    @GetMapping("/{matchId}")
    public List<MatchPlayerStats> getStatsByMatch(@PathVariable Long matchId) {
        return statsRepository.findByMatchId(matchId);
    }
}