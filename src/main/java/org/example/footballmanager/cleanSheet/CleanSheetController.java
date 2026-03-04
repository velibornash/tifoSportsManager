package org.example.footballmanager.cleanSheet;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.cleanSheet.dto.SimulatedMatchResult;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.model.*;
import org.example.footballmanager.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/clean-sheet")
@RequiredArgsConstructor
public class CleanSheetController {

    private final CleanSheetService cleanSheetService;
    private final TeamRepository teamRepository;
    private final SeasonCompetitionRepository scRepo;
    private final GameClockRepository clockRepo;
    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;

    // Endpoint za jedan meč – vraća runtime rezultat (JSON)
    @PostMapping("/simulate-single")
    public ResponseEntity<SimulatedMatchResult> simulateSingle(@RequestParam Long homeId, @RequestParam Long awayId) {
        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        Team home = teamRepository.findById(homeId).orElseThrow();
        Team away = teamRepository.findById(awayId).orElseThrow();
        SeasonCompetition sc = scRepo.findByCompetitionAndSeasonYear(superLiga,2025).orElseThrow();
        GameClock clock = clockRepo.findById(1L).orElseThrow();
        SimulatedMatchResult result = cleanSheetService.simulateSingleMatch(home, away, sc, clock);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/simulate-matchday")
    public ResponseEntity<List<SimulatedMatchResult>> simulateMatchDay(
            @RequestParam Long leagueId,
            @RequestParam Integer seasonYear) {

        Competition league = competitionRepository.findById(leagueId).orElseThrow();
        Season currentSeason = seasonRepository.findBySeasonYear(seasonYear).orElseThrow();

        List<SimulatedMatchResult> results = cleanSheetService.simulateMatchDay(league, currentSeason, null, null);
        return ResponseEntity.ok(results);
    }
}
