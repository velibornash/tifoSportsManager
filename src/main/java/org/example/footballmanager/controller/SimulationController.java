package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Season;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SimulationController {

    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final SimulationService simulationService;
    private final MatchEngine matchEngine;
    private final MatchStatisticEngine matchStatisticEngine;
    private final SeasonService seasonService;
    private final TrainingProgressionService trainingProgressionService;

    @SneakyThrows
    @GetMapping("/start-demo")
    public ResponseEntity<Map<String, String>> startDemo() {
        return startDemoInternal(true, false);
    }

    @SneakyThrows
    @GetMapping("/start-demo-key-events")
    public ResponseEntity<Map<String, String>> startDemoKeyEvents() {
        return startDemoInternal(true, true);
    }

    private ResponseEntity<Map<String, String>> startDemoInternal(boolean advanceWeekAfterSimulation, boolean autoRunTrainingAfterSimulation) {
        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        if (superLiga == null) {
            log.error("Cannot find league");
            return ResponseEntity.badRequest().body(Map.of("error", "League not found"));
        }
        int activeSeasonYear = seasonService.getActiveSeasonYear();
        Season currentSeason = seasonRepository.findBySeasonYear(activeSeasonYear)
                .orElseGet(seasonService::ensureActiveSeasonEntity);

        seasonService.ensureEntriesForSeasonCompetition(superLiga, activeSeasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(superLiga, activeSeasonYear);
        Match demoMatch = matchEngine.createMatch();

        Team omladinac = demoMatch.getHomeTeam();
        Team opponent = demoMatch.getAwayTeam();

        matchEngine.simulateRestOfMatchDay(superLiga, currentSeason, omladinac, opponent);

        simulationService.startSimulation(demoMatch.getId())
                .thenAccept(played -> {
                    log.info("Demo simulation completed for match ID: {}", demoMatch.getId());
                    matchStatisticEngine.updateLeagueTableForMatchDay(superLiga, currentSeason);
                    if (autoRunTrainingAfterSimulation) {
                        try {
                            trainingProgressionService.runWeeklyTraining(omladinac.getId());
                            log.info("Auto weekly training completed for team {}", omladinac.getId());
                        } catch (Exception ex) {
                            log.warn("Auto weekly training failed for team {}", omladinac.getId(), ex);
                        }
                    }
                    if (advanceWeekAfterSimulation) {
                        seasonService.advanceWeekAndHandleSeasonTransition(superLiga);
                    }
                })
                .exceptionally(throwable -> {
                    log.error("Error while running demo simulation for match {}", demoMatch.getId(), throwable);
                    return null;
                });

        return ResponseEntity.ok(Map.of(
                "status", "prepared",
                "message", "Simulation started - data should stream shortly",
                "position_socket", "/demo-position-updates",
                "event_socket", "/demo-match-events",
                "matchId", demoMatch.getId().toString()
        ));
    }
}
