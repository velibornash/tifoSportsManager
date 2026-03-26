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
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.PenaltyEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.VARReviewEvent;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.MatchEventRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.MatchRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.example.footballmanager.service.SeasonService;
import org.example.footballmanager.service.AdvanceWeekAsyncService;
import org.example.footballmanager.service.RoundSimulationAsyncService;
import org.example.footballmanager.service.SimulationService;
import org.example.footballmanager.service.TrainingProgressionService;
import org.example.footballmanager.service.WeekPreparationAsyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
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
    private final RoundSimulationAsyncService roundSimulationAsyncService;
    private final AdvanceWeekAsyncService advanceWeekAsyncService;
    private final WeekPreparationAsyncService weekPreparationAsyncService;
    private final TrainingProgressionService trainingProgressionService;
    private final TeamRepository teamRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final MatchRepository matchRepository;
    private final MatchEventRepository matchEventRepository;

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

    @PostMapping("/simulation/current-round/prepare")
    public ResponseEntity<Map<String, Object>> prepareCurrentWeek(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        WeekPreparationAsyncService.WeekPreparationSnapshot snapshot =
                weekPreparationAsyncService.startOrGetRunningJob(userTeam.getId());
        return ResponseEntity.accepted().body(toWeekPreparationResponse(snapshot));
    }

    @GetMapping("/simulation/current-round/prepare/status")
    public ResponseEntity<Map<String, Object>> getCurrentWeekPreparationStatus(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        WeekPreparationAsyncService.WeekPreparationSnapshot snapshot =
                weekPreparationAsyncService.getJobSnapshot(userTeam.getId());
        return ResponseEntity.ok(toWeekPreparationResponse(snapshot));
    }

    @PostMapping("/simulation/current-round/simulate-all")
    public ResponseEntity<Map<String, Object>> simulateCurrentRoundAcrossAllLeagues(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        RoundSimulationAsyncService.RoundSimulationSnapshot snapshot =
                roundSimulationAsyncService.startOrGetRunningJob(userTeam.getId());
        return ResponseEntity.accepted().body(toRoundSimulationResponse(snapshot));
    }

    @GetMapping("/simulation/current-round/status")
    public ResponseEntity<Map<String, Object>> getCurrentRoundSimulationStatus(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }

        RoundSimulationAsyncService.RoundSimulationSnapshot snapshot =
                roundSimulationAsyncService.getJobSnapshot(userTeam.getId());
        return ResponseEntity.ok(toRoundSimulationResponse(snapshot));
    }

    @GetMapping("/simulation/current-round/feed")
    public ResponseEntity<Map<String, Object>> getCurrentRoundFeed(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        return ResponseEntity.ok(buildRoundFeedResponse(userTeam));
    }

    @PostMapping("/simulation/week/advance")
    public ResponseEntity<Map<String, Object>> advanceWeek(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }
        AdvanceWeekAsyncService.AdvanceWeekSnapshot snapshot =
                advanceWeekAsyncService.startOrGetRunningJob(userTeam.getId());
        return ResponseEntity.accepted().body(toAdvanceWeekResponse(snapshot));
    }

    @GetMapping("/simulation/week/advance/status")
    public ResponseEntity<Map<String, Object>> getAdvanceWeekStatus(@AuthenticationPrincipal User user) {
        Team userTeam = resolveUserTeamOrFallback(user);
        if (userTeam == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User or team not found"));
        }

        AdvanceWeekAsyncService.AdvanceWeekSnapshot snapshot =
                advanceWeekAsyncService.getJobSnapshot(userTeam.getId());
        return ResponseEntity.ok(toAdvanceWeekResponse(snapshot));
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

        MatchFixture userFixture = findAnyFixture(activeLeague, activeSeasonYear, currentWeek, userTeam.getId());
        Match playedUserMatch = userFixture != null ? userFixture.getPlayedMatch() : null;
        if (playedUserMatch != null) {
            return ResponseEntity.ok(buildStartMatchResponse(
                    playedUserMatch,
                    "Opening your already prepared match replay."
            ));
        }

        Match existingPreparedMatch = findPreparedUserMatch(activeLeague, activeSeasonYear, currentWeek, userTeam.getId());
        if (existingPreparedMatch != null) {
            if (!simulationService.isSimulationRunning(existingPreparedMatch.getId())) {
                log.warn("Recovering stale prepared live match {} for team {}", existingPreparedMatch.getId(), userTeam.getId());
                simulationService.recoverAndRestartRealisticSimulation(existingPreparedMatch.getId())
                        .exceptionally(throwable -> {
                            log.error("Error while recovering realistic demo simulation for match {}", existingPreparedMatch.getId(), throwable);
                            return null;
                        });
                return ResponseEntity.ok(buildStartMatchResponse(
                        existingPreparedMatch,
                        "Recovered your previous live match and restarted the simulation."
                ));
            }
            return ResponseEntity.ok(buildStartMatchResponse(
                    existingPreparedMatch,
                    "Existing live match is already prepared - opening that match."
            ));
        }

        if (userFixture == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "waiting",
                    "action", "NO_MATCH_CURRENT_WEEK",
                    "message", "Your club has no scheduled match in the current week. Prepare or review the remaining results if you want to progress manually."
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

    private MatchFixture findAnyFixture(Competition league, int seasonYear, int currentWeek, Long teamId) {
        return matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
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

    private Map<String, Object> toRoundSimulationResponse(RoundSimulationAsyncService.RoundSimulationSnapshot snapshot) {
        if (snapshot.payload() != null && ("completed".equals(snapshot.status()) || "failed".equals(snapshot.status()))) {
            return snapshot.payload();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", snapshot.status());
        payload.put("action", snapshot.action());
        payload.put("jobId", snapshot.jobId());
        payload.put("message", snapshot.message());
        payload.put("processedLeagues", snapshot.processedLeagues());
        payload.put("leaguesProcessed", snapshot.totalLeagues());
        payload.put("currentLeague", snapshot.currentLeague());
        return payload;
    }

    private Map<String, Object> toAdvanceWeekResponse(AdvanceWeekAsyncService.AdvanceWeekSnapshot snapshot) {
        if (snapshot.payload() != null && ("completed".equals(snapshot.status()) || "failed".equals(snapshot.status()))) {
            return snapshot.payload();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", snapshot.status());
        payload.put("action", snapshot.action());
        payload.put("jobId", snapshot.jobId());
        payload.put("message", snapshot.message());
        return payload;
    }

    private Map<String, Object> toWeekPreparationResponse(WeekPreparationAsyncService.WeekPreparationSnapshot snapshot) {
        if (snapshot.payload() != null && ("completed".equals(snapshot.status()) || "failed".equals(snapshot.status()))) {
            return snapshot.payload();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", snapshot.status());
        payload.put("action", snapshot.action());
        payload.put("jobId", snapshot.jobId());
        payload.put("message", snapshot.message());
        payload.put("processedLeagues", snapshot.processedLeagues());
        payload.put("leaguesProcessed", snapshot.totalLeagues());
        payload.put("currentLeague", snapshot.currentLeague());
        return payload;
    }

    private Map<String, Object> buildRoundFeedResponse(Team userTeam) {
        Competition superLiga = competitionRepository.findById(1L).orElse(null);
        if (superLiga == null) {
            return Map.of("status", "error", "message", "League not found.");
        }

        Competition activeLeague = userTeam.getCompetition() != null ? userTeam.getCompetition() : superLiga;
        int seasonYear = seasonService.getActiveSeasonYear();
        int currentWeek = seasonService.getCurrentWeek();

        List<Map<String, Object>> leagues = new ArrayList<>();
        for (Competition league : seasonService.getSerbianLeaguesInOrder()) {
            prepareLeagueForCurrentWeek(league, seasonYear, currentWeek);
            List<MatchFixture> fixtures = matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
                    league.getId(), seasonYear, currentWeek
            );
            if (fixtures.isEmpty()) {
                continue;
            }
            leagues.add(Map.of(
                    "leagueId", league.getId(),
                    "leagueName", league.getName(),
                    "userLeague", Objects.equals(league.getId(), activeLeague.getId()),
                    "matches", fixtures.stream().map(fixture -> buildFeedMatch(fixture, userTeam.getId())).toList()
            ));
        }

        leagues.sort(Comparator.comparing((Map<String, Object> item) -> !(Boolean) item.get("userLeague"))
                .thenComparing(item -> String.valueOf(item.get("leagueName"))));

        return Map.of(
                "status", "ok",
                "currentWeek", currentWeek,
                "seasonYear", seasonYear,
                "userLeague", activeLeague.getName(),
                "leagues", leagues
        );
    }

    private Map<String, Object> buildFeedMatch(MatchFixture fixture, Long userTeamId) {
        Match match = fixture.getPlayedMatch();
        boolean played = fixture.isPlayed() && match != null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fixtureId", fixture.getId());
        payload.put("matchId", match != null ? match.getId() : null);
        payload.put("homeTeam", fixture.getHomeTeam() != null ? fixture.getHomeTeam().getName() : "TBD");
        payload.put("awayTeam", fixture.getAwayTeam() != null ? fixture.getAwayTeam().getName() : "TBD");
        payload.put("homeGoals", match != null ? match.getHomeGoals() : 0);
        payload.put("awayGoals", match != null ? match.getAwayGoals() : 0);
        payload.put("played", played);
        payload.put("isUserMatch", (fixture.getHomeTeam() != null && Objects.equals(fixture.getHomeTeam().getId(), userTeamId))
                || (fixture.getAwayTeam() != null && Objects.equals(fixture.getAwayTeam().getId(), userTeamId)));
        payload.put("events", played ? buildFeedEvents(match) : List.of());
        return payload;
    }

    private List<Map<String, Object>> buildFeedEvents(Match match) {
        List<MatchEvent> events = matchEventRepository.findByMatch(match).stream()
                .sorted(Comparator.comparingInt(MatchEvent::getMinute).thenComparingInt(MatchEvent::getTick))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        int homeScore = 0;
        int awayScore = 0;

        for (MatchEvent event : events) {
            Map<String, Object> row = null;
            if (event instanceof GoalEvent goal && goal.isScored()) {
                if (goal.getTeam() != null && match.getHomeTeam() != null && Objects.equals(goal.getTeam().getId(), match.getHomeTeam().getId())) {
                    homeScore += 1;
                } else {
                    awayScore += 1;
                }
                row = baseFeedEvent(event, "GOAL", goal.getTeam() != null ? goal.getTeam().getName() : null,
                        goal.getScorer() != null ? goal.getScorer().getName() : "Unknown scorer",
                        homeScore, awayScore);
            } else if (event instanceof PenaltyEvent penalty) {
                if (penalty.isScored()) {
                    if (penalty.getTeam() != null && match.getHomeTeam() != null && Objects.equals(penalty.getTeam().getId(), match.getHomeTeam().getId())) {
                        homeScore += 1;
                    } else {
                        awayScore += 1;
                    }
                    row = baseFeedEvent(event, "PEN", penalty.getTeam() != null ? penalty.getTeam().getName() : null,
                            penalty.getTaker() != null ? penalty.getTaker().getName() : "Penalty",
                            homeScore, awayScore);
                } else {
                    row = baseFeedEvent(event, "PEN MISS", penalty.getTeam() != null ? penalty.getTeam().getName() : null,
                            penalty.getTaker() != null ? penalty.getTaker().getName() : "Penalty miss",
                            homeScore, awayScore);
                }
            } else if (event instanceof RedCardEvent red) {
                row = baseFeedEvent(event, "RC", red.getTeam() != null ? red.getTeam().getName() : null,
                        red.getPlayer() != null ? red.getPlayer().getName() : "Red card",
                        homeScore, awayScore);
            } else if (event instanceof VARReviewEvent var) {
                row = baseFeedEvent(event, "VAR",
                        var.getReviewedGoalEvent() != null && var.getReviewedGoalEvent().getTeam() != null
                                ? var.getReviewedGoalEvent().getTeam().getName()
                                : null,
                        var.getDecision() != null ? "Decision: " + var.getDecision() : "Review",
                        homeScore, awayScore);
            }

            if (row != null) {
                rows.add(row);
            }
        }

        return rows;
    }

    private Map<String, Object> baseFeedEvent(MatchEvent event, String code, String teamName, String playerName, int homeScore, int awayScore) {
        return Map.of(
                "minute", event.getMinute(),
                "code", code,
                "teamName", teamName == null ? "" : teamName,
                "playerName", playerName == null ? "" : playerName,
                "homeGoals", homeScore,
                "awayGoals", awayScore
        );
    }
}
