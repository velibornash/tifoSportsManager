package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.Season;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.model.User;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final TeamRepository teamRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final MatchRepository matchRepository;

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
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        return startRealisticDemoInternal(userTeam);
    }

    @PostMapping("/simulation/current-round/simulate-all")
    public ResponseEntity<Map<String, Object>> simulateCurrentRoundAcrossAllLeagues(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }

        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        if (superLiga == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "League not found"));
        }

        Competition activeLeague = userTeam.getCompetition() != null ? userTeam.getCompetition() : superLiga;
        int seasonYear = seasonService.getActiveSeasonYear();
        int currentWeek = seasonService.getCurrentWeek();
        Season currentSeason = seasonRepository.findBySeasonYear(seasonYear)
                .orElseGet(seasonService::ensureActiveSeasonEntity);

        MatchFixture userFixture = findUserFixture(activeLeague, seasonYear, currentWeek, userTeam.getId());
        Match preparedUserMatch = findPreparedUserMatch(activeLeague, seasonYear, currentWeek, userTeam.getId());
        Team excludedHome = preparedUserMatch != null ? preparedUserMatch.getHomeTeam() : (userFixture != null ? userFixture.getHomeTeam() : null);
        Team excludedAway = preparedUserMatch != null ? preparedUserMatch.getAwayTeam() : (userFixture != null ? userFixture.getAwayTeam() : null);

        List<Competition> leagues = seasonService.getSerbianLeaguesInOrder();
        List<Map<String, Object>> leagueResults = new ArrayList<>();
        int simulatedCount = 0;

        for (Competition league : leagues) {
            prepareLeagueForCurrentWeek(league, seasonYear, currentWeek);
            int pendingBefore = countRemainingFixtures(league, seasonYear, currentWeek);
            Team skipHome = Objects.equals(league.getId(), activeLeague.getId()) ? excludedHome : null;
            Team skipAway = Objects.equals(league.getId(), activeLeague.getId()) ? excludedAway : null;

            matchEngine.simulateRestOfMatchDay(league, currentSeason, skipHome, skipAway);

            int pendingAfter = countRemainingFixtures(league, seasonYear, currentWeek);
            int simulatedForLeague = Math.max(0, pendingBefore - pendingAfter);
            if (currentWeek <= SeasonService.LEAGUE_ROUNDS && (pendingBefore > 0 || simulatedForLeague > 0)) {
                matchStatisticEngine.updateLeagueTableForMatchDay(league, currentSeason);
            }
            simulatedCount += simulatedForLeague;

            if (pendingBefore > 0 || simulatedForLeague > 0) {
                leagueResults.add(Map.of(
                        "league", league.getName(),
                        "remainingBefore", pendingBefore,
                        "remainingAfter", pendingAfter,
                        "simulated", simulatedForLeague
                ));
            }
        }

        boolean playoffWeekComplete = currentWeek == SeasonService.PLAYOFF_WEEK
                && countRemainingFixtures(superLiga, seasonYear, currentWeek) == 0;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("action", playoffWeekComplete ? "SHOW_PLAYOFF_SUMMARY" : "ROUND_SIMULATED");
        payload.put("simulatedCount", simulatedCount);
        payload.put("leaguesProcessed", leagues.size());
        payload.put("leagueResults", leagueResults);
        payload.put(
                "message",
                simulatedCount > 0
                        ? "Simulated remaining fixtures across all Serbian leagues for the current round."
                        : "No other remaining fixtures were found for the current round."
        );
        if (playoffWeekComplete) {
            payload.put("summary", seasonService.buildPlayoffSummary(superLiga, seasonYear));
        }
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/simulation/week/advance")
    public ResponseEntity<Map<String, Object>> advanceWeek(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }

        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        if (superLiga == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "League not found"));
        }

        int currentWeek = seasonService.getCurrentWeek();
        int currentSeasonYear = seasonService.getActiveSeasonYear();
        int remainingFixtures = countRemainingFixturesAcrossLeagues(currentSeasonYear, currentWeek);
        if (remainingFixtures > 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "blocked",
                    "action", "ROUND_NOT_COMPLETE",
                    "message", "Current week still has unfinished fixtures. Play your match and simulate the remaining results before advancing the calendar.",
                    "remainingFixtures", remainingFixtures
            ));
        }

        boolean trainingRan = currentWeek != SeasonService.PLAYOFF_WEEK && userTeam.getId() != null;
        if (trainingRan) {
            trainingProgressionService.runWeeklyTraining(userTeam.getId());
        }
        seasonService.advanceWeekAndHandleSeasonTransition(superLiga);

        int nextWeek = seasonService.getCurrentWeek();
        int nextSeasonYear = seasonService.getActiveSeasonYear();
        String message = nextSeasonYear != currentSeasonYear
                ? "Week advanced and a new season has started."
                : "Week advanced successfully.";
        if (trainingRan) {
            message = "Weekly training completed. " + message;
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "action", "WEEK_ADVANCED",
                "message", message,
                "currentWeek", nextWeek,
                "seasonYear", nextSeasonYear
        ));
    }

    private ResponseEntity<Map<String, Object>> startRealisticDemoInternal(Team userTeam) {
        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        if (superLiga == null) {
            log.error("Cannot find league");
            return ResponseEntity.badRequest().body(Map.of("error", "League not found"));
        }

        Competition activeLeague = userTeam.getCompetition() != null ? userTeam.getCompetition() : superLiga;
        int activeSeasonYear = seasonService.getActiveSeasonYear();
        int currentWeek = seasonService.getCurrentWeek();

        prepareLeagueForCurrentWeek(activeLeague, activeSeasonYear, currentWeek);

        Match existingPreparedMatch = findPreparedUserMatch(activeLeague, activeSeasonYear, currentWeek, userTeam.getId());
        if (existingPreparedMatch != null) {
            return ResponseEntity.ok(buildStartMatchResponse(
                    existingPreparedMatch,
                    "Existing live match is already prepared - opening that match."
            ));
        }

        MatchFixture userFixture = findUserFixture(activeLeague, activeSeasonYear, currentWeek, userTeam.getId());
        if (userFixture == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "waiting",
                    "action", "NO_MATCH_CURRENT_WEEK",
                    "message", "Your club has no scheduled match in the current week. Use Simulate Other Results if you want to progress the round manually."
            ));
        }

        Match demoMatch = matchEngine.createMatch(userTeam);
        Team homeTeam = demoMatch.getHomeTeam();

        log.info("Realistic demo: User team '{}' is {}",
                userTeam.getName(),
                homeTeam != null && Objects.equals(userTeam.getId(), homeTeam.getId()) ? "HOME" : "AWAY");

        simulationService.startRealisticSimulation(demoMatch.getId())
                .exceptionally(throwable -> {
                    log.error("Error while running realistic demo simulation for match {}", demoMatch.getId(), throwable);
                    return null;
                });

        return ResponseEntity.ok(buildStartMatchResponse(
                demoMatch,
                "Realistic simulation started - replay data will be available shortly"
        ));
    }

    private ResponseEntity<Map<String, String>> startDemoInternal(User user, boolean advanceWeekAfterSimulation, boolean autoRunTrainingAfterSimulation) {
        Team userTeam;

        if (user == null || user.getTeam() == null) {
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

        Match demoMatch = matchEngine.createMatch(userTeam);
        Team homeTeam = demoMatch.getHomeTeam();
        Team awayTeam = demoMatch.getAwayTeam();

        log.info("Demo: User team '{}' is {}",
                userTeam.getName(),
                userTeam.getId().equals(homeTeam.getId()) ? "HOME" : "AWAY");

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

    private Team resolveUserTeamOrFallback(User user) {
        if (user == null || user.getTeam() == null) {
            return teamRepository.findByName("OFK Omladinac").orElse(null);
        }
        return teamRepository.findById(user.getTeam().getId()).orElse(user.getTeam());
    }

    private void prepareLeagueForCurrentWeek(Competition league, int seasonYear, int currentWeek) {
        seasonService.ensureEntriesForSeasonCompetition(league, seasonYear);
        seasonService.ensureDoubleRoundRobinSchedule(league, seasonYear);
        if (currentWeek == SeasonService.PLAYOFF_WEEK && Objects.equals(league.getTier(), 1)) {
            seasonService.ensurePlayoffWeekFixtures(league, seasonYear);
        } else if (currentWeek == SeasonService.FRIENDLY_WEEK) {
            seasonService.ensureFriendlyWeekFixtures(league, seasonYear);
        }
    }

    private MatchFixture findUserFixture(Competition league, int seasonYear, int currentWeek, Long teamId) {
        return matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(
                        league.getId(), seasonYear, currentWeek
                ).stream()
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .filter(fixture -> Objects.equals(fixture.getHomeTeam().getId(), teamId)
                        || Objects.equals(fixture.getAwayTeam().getId(), teamId))
                .findFirst()
                .orElse(null);
    }

    private Match findPreparedUserMatch(Competition league, int seasonYear, int currentWeek, Long teamId) {
        return matchRepository.findPreparedMatchesForTeamInRound(league.getId(), seasonYear, currentWeek, teamId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private int countRemainingFixtures(Competition league, int seasonYear, int currentWeek) {
        return matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(
                league.getId(), seasonYear, currentWeek
        ).size();
    }

    private int countRemainingFixturesAcrossLeagues(int seasonYear, int currentWeek) {
        int remaining = 0;
        for (Competition league : seasonService.getSerbianLeaguesInOrder()) {
            prepareLeagueForCurrentWeek(league, seasonYear, currentWeek);
            remaining += countRemainingFixtures(league, seasonYear, currentWeek);
        }
        return remaining;
    }

    private Map<String, Object> buildStartMatchResponse(Match match, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "prepared");
        payload.put("action", "START_MATCH");
        payload.put("message", message);
        payload.put("position_socket", "/demo-position-updates");
        payload.put("event_socket", "/demo-match-events");
        payload.put("replay_metadata", "/api/zox/replay/" + match.getId() + "/metadata");
        payload.put("replay_chunk_template", "/api/zox/replay/" + match.getId() + "/chunks/{chunkIndex}");
        payload.put("matchId", match.getId().toString());
        return payload;
    }
}
