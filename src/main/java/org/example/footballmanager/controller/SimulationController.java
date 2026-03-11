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
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.repository.UserRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;

    @SneakyThrows
    @GetMapping("/start-demo")
    public ResponseEntity<Map<String, String>> startDemo(@AuthenticationPrincipal User user) {
        return startDemoInternal(user, true, false);
    }

    @SneakyThrows
    @GetMapping("/start-demo-key-events")
    public ResponseEntity<Map<String, String>> startDemoKeyEvents(@AuthenticationPrincipal User user) {
        return startDemoInternal(user, true, true);
    }

    @SneakyThrows
    @GetMapping("/start-realistic-demo")
    public ResponseEntity<Map<String, Object>> startRealisticDemo(@AuthenticationPrincipal User user) {
        if (user == null || user.getTeam() == null) {
            // Fallback: use Omladinac for backward compatibility if no user is authenticated
            Team omladinacFallback = teamRepository.findByName("OFK Omladinac").orElse(null);
            if (omladinacFallback == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
            }
            return startRealisticDemoInternal(omladinacFallback);
        }

        Team freshTeam = teamRepository.findById(user.getTeam().getId()).orElse(user.getTeam());
        return startRealisticDemoInternal(freshTeam);
    }

    private ResponseEntity<Map<String, Object>> startRealisticDemoInternal(Team userTeam) {
        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        if (superLiga == null) {
            log.error("Cannot find league");
            return ResponseEntity.badRequest().body(Map.of("error", "League not found"));
        }
        Competition activeLeague = userTeam.getCompetition() != null ? userTeam.getCompetition() : superLiga;
        int activeSeasonYear = seasonService.getActiveSeasonYear();
        Season currentSeason = seasonRepository.findBySeasonYear(activeSeasonYear)
                .orElseGet(seasonService::ensureActiveSeasonEntity);

        seasonService.ensureEntriesForSeasonCompetition(superLiga, activeSeasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(superLiga, activeSeasonYear);
        seasonService.ensureEntriesForSeasonCompetition(activeLeague, activeSeasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(activeLeague, activeSeasonYear);

        int currentWeek = seasonService.getCurrentWeek();
        if (currentWeek == SeasonService.PLAYOFF_WEEK) {
            matchEngine.simulateRestOfMatchDay(superLiga, currentSeason, null, null);
            Map<String, Object> playoffSummary = seasonService.buildPlayoffSummary(superLiga, activeSeasonYear);
            seasonService.advanceWeekAndHandleSeasonTransition(superLiga);
            return ResponseEntity.ok(Map.of(
                    "status", "prepared",
                    "action", "SHOW_PLAYOFF_SUMMARY",
                    "message", "Playoff results are ready. Next click starts the friendly week.",
                    "summary", playoffSummary
            ));
        }

        // Create match using user's actual team (not assuming home)
        Match demoMatch = matchEngine.createMatch(userTeam);

        // Determine user's position in the match
        Team homeTeam = demoMatch.getHomeTeam();
        Team awayTeam = demoMatch.getAwayTeam();

        log.info("Realistic demo: User team '{}' is {}", userTeam.getName(), 
                userTeam.getId().equals(homeTeam.getId()) ? "HOME" : "AWAY");

        simulationService.startRealisticSimulation(demoMatch.getId())
                .thenAccept(played -> {
                    if (played == null) {
                        log.warn("Realistic demo simulation did not produce a played match for ID: {}", demoMatch.getId());
                        return;
                    }
                    log.info("Realistic demo simulation completed for match ID: {}", demoMatch.getId());
                    matchEngine.simulateRestOfMatchDay(activeLeague, currentSeason, homeTeam, awayTeam);
                    matchStatisticEngine.updateLeagueTableForMatchDay(activeLeague, currentSeason);
                    try {
                        trainingProgressionService.runWeeklyTraining(userTeam.getId());
                        log.info("Auto weekly training completed for team {}", userTeam.getId());
                    } catch (Exception ex) {
                        log.warn("Auto weekly training failed for team {}", userTeam.getId(), ex);
                    }
                    seasonService.advanceWeekAndHandleSeasonTransition(superLiga);
                })
                .exceptionally(throwable -> {
                    log.error("Error while running realistic demo simulation for match {}", demoMatch.getId(), throwable);
                    return null;
                });

        return ResponseEntity.ok(Map.of(
                "status", "prepared",
                "action", "START_MATCH",
                "message", "Realistic simulation started - replay data will be available shortly",
                "position_socket", "/demo-position-updates",
                "event_socket", "/demo-match-events",
                "replay_metadata", "/api/zox/replay/" + demoMatch.getId() + "/metadata",
                "replay_chunk_template", "/api/zox/replay/" + demoMatch.getId() + "/chunks/{chunkIndex}",
                "matchId", demoMatch.getId().toString()
        ));
    }

    private ResponseEntity<Map<String, String>> startDemoInternal(User user, boolean advanceWeekAfterSimulation, boolean autoRunTrainingAfterSimulation) {
        Team userTeam;
        
        if (user == null || user.getTeam() == null) {
            // Fallback: use Omladinac for backward compatibility if no user is authenticated
            userTeam = teamRepository.findByName("OFK Omladinac").orElse(null);
            if (userTeam == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
            }
            log.info("No authenticated user - using fallback team: {}", userTeam.getName());
        } else {
            userTeam = user.getTeam();
        }

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
        
        // Create match using user's actual team (not assuming home)
        Match demoMatch = matchEngine.createMatch(userTeam);

        // Determine user's position in the match
        Team homeTeam = demoMatch.getHomeTeam();
        Team awayTeam = demoMatch.getAwayTeam();

        log.info("Demo: User team '{}' is {}", userTeam.getName(), 
                userTeam.getId().equals(homeTeam.getId()) ? "HOME" : "AWAY");

        // Simulate rest of matchday for other teams
        matchEngine.simulateRestOfMatchDay(superLiga, currentSeason, homeTeam, awayTeam);

        simulationService.startSimulation(demoMatch.getId())
                .thenAccept(played -> {
                    log.info("Demo simulation completed for match ID: {}", demoMatch.getId());
                    matchStatisticEngine.updateLeagueTableForMatchDay(superLiga, currentSeason);
                    if (autoRunTrainingAfterSimulation) {
                        try {
                            trainingProgressionService.runWeeklyTraining(userTeam.getId());
                            log.info("Auto weekly training completed for team {}", userTeam.getId());
                        } catch (Exception ex) {
                            log.warn("Auto weekly training failed for team {}", userTeam.getId(), ex);
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
