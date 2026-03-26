package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.engines.MatchEngine;
import org.example.footballmanager.engines.MatchStatisticEngine;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchFixture;
import org.example.footballmanager.model.Season;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.MatchFixtureRepository;
import org.example.footballmanager.repository.SeasonRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class WeekPreparationAsyncService {

    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonService seasonService;
    private final TeamRepository teamRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final MatchEngine matchEngine;
    private final MatchStatisticEngine matchStatisticEngine;
    private final SimulationService simulationService;
    private final TransactionTemplate transactionTemplate;

    private final Map<Long, WeekPreparationJob> jobsByTeamId = new ConcurrentHashMap<>();

    public WeekPreparationSnapshot startOrGetRunningJob(Long teamId) {
        WeekPreparationJob job = jobsByTeamId.compute(teamId, (id, existing) -> {
            if (existing != null && existing.isRunning()) {
                return existing;
            }

            WeekPreparationJob created = WeekPreparationJob.running();
            CompletableFuture.runAsync(() -> runPreparation(id, created));
            return created;
        });
        return job.toSnapshot();
    }

    public WeekPreparationSnapshot getJobSnapshot(Long teamId) {
        WeekPreparationJob job = jobsByTeamId.get(teamId);
        return job == null ? WeekPreparationSnapshot.idle() : job.toSnapshot();
    }

    private void runPreparation(Long teamId, WeekPreparationJob job) {
        try {
            Team userTeam = transactionTemplate.execute(status -> teamRepository.findById(teamId).orElse(null));
            if (userTeam == null) {
                job.fail("User team not found.");
                return;
            }

            Competition superLiga = competitionRepository.findById(1L).orElse(null);
            if (superLiga == null) {
                job.fail("League not found.");
                return;
            }

            Competition activeLeague = userTeam.getCompetition() != null ? userTeam.getCompetition() : superLiga;
            int seasonYear = seasonService.getActiveSeasonYear();
            int currentWeek = seasonService.getCurrentWeek();
            Season currentSeason = seasonRepository.findBySeasonYear(seasonYear)
                    .orElseGet(seasonService::ensureActiveSeasonEntity);

            transactionTemplate.executeWithoutResult(status -> prepareLeagueForCurrentWeek(activeLeague, seasonYear, currentWeek));
            MatchFixture userFixture = transactionTemplate.execute(status -> findUserFixture(activeLeague, seasonYear, currentWeek, userTeam.getId()));
            Match existingUserMatch = userFixture != null ? userFixture.getPlayedMatch() : null;

            int pendingBeforeAll = countRemainingFixturesAcrossLeagues(seasonYear, currentWeek);
            int simulatedCount = 0;

            if (existingUserMatch == null && userFixture != null && !userFixture.isPlayed()) {
                job.updateProgress(0, 0, "Preparing your match replay...");
                Match createdMatch = transactionTemplate.execute(status -> {
                    Team managedUserTeam = teamRepository.findById(teamId).orElseThrow();
                    return matchEngine.createMatch(managedUserTeam);
                });
                Match preparedMatch = simulationService.startRealisticSimulation(createdMatch.getId()).join();
                existingUserMatch = preparedMatch != null ? preparedMatch : createdMatch;
                simulatedCount += 1;
            }

            List<Competition> leagues = seasonService.getSerbianLeaguesInOrder();
            List<Map<String, Object>> leagueResults = new ArrayList<>();
            for (int index = 0; index < leagues.size(); index++) {
                Competition league = leagues.get(index);
                job.updateProgress(index + 1, leagues.size(), league.getName());

                prepareLeagueForCurrentWeek(league, seasonYear, currentWeek);
                int pendingBefore = countRemainingFixtures(league, seasonYear, currentWeek);
                Team skipHome = null;
                Team skipAway = null;
                if (userFixture != null && Objects.equals(league.getId(), activeLeague.getId())) {
                    skipHome = userFixture.getHomeTeam();
                    skipAway = userFixture.getAwayTeam();
                }

                Team finalSkipHome = skipHome;
                Team finalSkipAway = skipAway;
                transactionTemplate.executeWithoutResult(status ->
                        matchEngine.simulateRestOfMatchDay(league, currentSeason, finalSkipHome, finalSkipAway)
                );

                int pendingAfter = countRemainingFixtures(league, seasonYear, currentWeek);
                int simulatedForLeague = Math.max(0, pendingBefore - pendingAfter);
                if (currentWeek <= SeasonService.LEAGUE_ROUNDS && (pendingBefore > 0 || simulatedForLeague > 0)) {
                    transactionTemplate.executeWithoutResult(status ->
                            matchStatisticEngine.updateLeagueTableForMatchDay(league, currentSeason)
                    );
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

            Match finalUserMatch = transactionTemplate.execute(status ->
                    findPlayedUserMatch(activeLeague, seasonYear, currentWeek, userTeam.getId())
            );
            int pendingAfterAll = countRemainingFixturesAcrossLeagues(seasonYear, currentWeek);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("action", "WEEK_PREPARED");
            payload.put("message", pendingBeforeAll == 0
                    ? "This match week was already prepared."
                    : "All current-week fixtures have been simulated and stored. Choose your next view.");
            payload.put("simulatedCount", simulatedCount);
            payload.put("leaguesProcessed", leagues.size());
            payload.put("leagueResults", leagueResults);
            payload.put("remainingBefore", pendingBeforeAll);
            payload.put("remainingAfter", pendingAfterAll);
            payload.put("currentWeek", currentWeek);
            payload.put("seasonYear", seasonYear);
            payload.put("userMatchId", finalUserMatch != null ? finalUserMatch.getId() : null);
            payload.put("hasUserMatch", finalUserMatch != null);
            payload.put("userLeague", activeLeague.getName());
            job.complete(payload);
        } catch (Exception ex) {
            log.error("Week preparation job failed for team {}", teamId, ex);
            job.fail("Preparing the current week failed. Check server logs.");
        }
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
        return matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
                        league.getId(), seasonYear, currentWeek
                ).stream()
                .filter(fixture -> fixture.getHomeTeam() != null && fixture.getAwayTeam() != null)
                .filter(fixture -> Objects.equals(fixture.getHomeTeam().getId(), teamId)
                        || Objects.equals(fixture.getAwayTeam().getId(), teamId))
                .findFirst()
                .orElse(null);
    }

    private Match findPlayedUserMatch(Competition league, int seasonYear, int currentWeek, Long teamId) {
        MatchFixture fixture = findUserFixture(league, seasonYear, currentWeek, teamId);
        return fixture != null ? fixture.getPlayedMatch() : null;
    }

    private int countRemainingFixtures(Competition league, int seasonYear, int currentWeek) {
        return matchFixtureRepository.findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(
                league.getId(), seasonYear, currentWeek
        ).size();
    }

    private int countRemainingFixturesAcrossLeagues(int seasonYear, int currentWeek) {
        int remaining = 0;
        for (Competition league : seasonService.getSerbianLeaguesInOrder()) {
            remaining += transactionTemplate.execute(status -> {
                prepareLeagueForCurrentWeek(league, seasonYear, currentWeek);
                return countRemainingFixtures(league, seasonYear, currentWeek);
            });
        }
        return remaining;
    }

    public record WeekPreparationSnapshot(
            String status,
            String action,
            String jobId,
            String message,
            int processedLeagues,
            int totalLeagues,
            String currentLeague,
            Map<String, Object> payload
    ) {
        static WeekPreparationSnapshot idle() {
            return new WeekPreparationSnapshot(
                    "idle",
                    "WEEK_PREPARATION_IDLE",
                    null,
                    "Current week has not been prepared yet.",
                    0,
                    0,
                    null,
                    null
            );
        }
    }

    private static final class WeekPreparationJob {
        private final String jobId = UUID.randomUUID().toString();
        private volatile String status = "running";
        private volatile String message = "Preparing current week in the background.";
        private volatile int processedLeagues;
        private volatile int totalLeagues;
        private volatile String currentLeague;
        private volatile Map<String, Object> payload;

        static WeekPreparationJob running() {
            return new WeekPreparationJob();
        }

        boolean isRunning() {
            return "running".equals(status);
        }

        void updateProgress(int processed, int total, String leagueName) {
            this.processedLeagues = processed;
            this.totalLeagues = total;
            this.currentLeague = leagueName;
            this.message = leagueName == null
                    ? "Preparing current week in the background."
                    : "Preparing " + leagueName + "...";
        }

        void complete(Map<String, Object> payload) {
            this.status = "completed";
            this.payload = payload;
            this.processedLeagues = this.totalLeagues;
            this.currentLeague = null;
            this.message = String.valueOf(payload.getOrDefault("message", "Current week prepared."));
        }

        void fail(String message) {
            this.status = "failed";
            this.currentLeague = null;
            this.message = message;
            this.payload = Map.of(
                    "status", "error",
                    "action", "WEEK_PREPARATION_FAILED",
                    "message", message
            );
        }

        WeekPreparationSnapshot toSnapshot() {
            String action = switch (status) {
                case "completed" -> payload != null ? String.valueOf(payload.get("action")) : "WEEK_PREPARED";
                case "failed" -> "WEEK_PREPARATION_FAILED";
                default -> "WEEK_PREPARATION_RUNNING";
            };
            return new WeekPreparationSnapshot(
                    status,
                    action,
                    jobId,
                    message,
                    processedLeagues,
                    totalLeagues,
                    currentLeague,
                    payload
            );
        }
    }
}
