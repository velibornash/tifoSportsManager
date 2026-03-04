package org.example.footballmanager.cleanSheet.old;

import lombok.RequiredArgsConstructor;
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
public class CleanSheetControllerOld {

    private final CleanSheetServiceOld cleanSheetServiceOld;
    private final TeamRepository teamRepository;
    private final SeasonCompetitionRepository scRepo;
    private final GameClockRepository clockRepo;
    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;

    // Endpoint za jedan meč – vraća runtime rezultat (JSON)
    @PostMapping("/simulate-single")
    public ResponseEntity<SimulatedMatchResultOld> simulateSingle(@RequestParam Long homeId, @RequestParam Long awayId) {
        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        Team home = teamRepository.findById(homeId).orElseThrow();
        Team away = teamRepository.findById(awayId).orElseThrow();
        SeasonCompetition sc = scRepo.findByCompetitionAndSeasonYear(superLiga,2025).orElseThrow();
        GameClock clock = clockRepo.findById(1L).orElseThrow();
        SimulatedMatchResultOld result = cleanSheetServiceOld.simulateSingleMatch(home, away, sc, clock);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/simulate-matchday")
    public ResponseEntity<List<SimulatedMatchResultOld>> simulateMatchDay(
            @RequestParam Long leagueId,
            @RequestParam Integer seasonYear) {

        Competition league = competitionRepository.findById(leagueId).orElseThrow();
        Season currentSeason = seasonRepository.findBySeasonYear(seasonYear).orElseThrow();

        List<SimulatedMatchResultOld> results = cleanSheetServiceOld.simulateMatchDay(league, currentSeason, null, null);
        return ResponseEntity.ok(results);
    }
}
