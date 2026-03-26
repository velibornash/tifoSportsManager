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
import org.example.footballmanager.repository.MatchRepository;
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
public class RoundSimulationAsyncService {

    private final CompetitionRepository competitionRepository;
    private final SeasonRepository seasonRepository;
    private final SeasonService seasonService;
    private final TeamRepository teamRepository;
    private final MatchFixtureRepository matchFixtureRepository;
    private final MatchRepository matchRepository;
    private final MatchEngine matchEngine;
    private final MatchStatisticEngine matchStatisticEngine;
    private final TransactionTemplate transactionTemplate;

    private final Map<Long, RoundSimulationJob> jobsByTeamId = new ConcurrentHashMap<>();

    public RoundSimulationSnapshot startOrGetRunningJob(Long teamId) {
        RoundSimulationJob job = jobsByTeamId.compute(teamId, (id, existing) -> {
            if (existing != null && existing.isRunning()) {
                return existing;
            }

            RoundSimulationJob created = RoundSimulationJob.running();
            CompletableFuture.runAsync(() -> runSimulation(id, created));
            return created;
        });
        return job.toSnapshot();
    }

    public RoundSimulationSnapshot getJobSnapshot(Long teamId) {
        RoundSimulationJob job = jobsByTeamId.get(teamId);
        return job == null ? RoundSimulationSnapshot.idle() : job.toSnapshot();
    }

    private void runSimulation(Long teamId, RoundSimulationJob job) {
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

            MatchFixture userFixture = transactionTemplate.execute(status ->
                    findUserFixture(activeLeague, seasonYear, currentWeek, userTeam.getId()));
            Match preparedUserMatch = transactionTemplate.execute(status ->
                    findPreparedUserMatch(activeLeague, seasonYear, currentWeek, userTeam.getId()));
            Team excludedHome = preparedUserMatch != null ? preparedUserMatch.getHomeTeam() : (userFixture != null ? userFixture.getHomeTeam() : null);
            Team excludedAway = preparedUserMatch != null ? preparedUserMatch.getAwayTeam() : (userFixture != null ? userFixture.getAwayTeam() : null);

            List<Competition> leagues = seasonService.getSerbianLeaguesInOrder();
            List<Map<String, Object>> leagueResults = new ArrayList<>();
            int simulatedCount = 0;

            for (int index = 0; index < leagues.size(); index++) {
                Competition league = leagues.get(index);
                job.updateProgress(index + 1, leagues.size(), league.getName());

                transactionTemplate.executeWithoutResult(status -> prepareLeagueForCurrentWeek(league, seasonYear, currentWeek));
                int pendingBefore = transactionTemplate.execute(status -> countRemainingFixtures(league, seasonYear, currentWeek));
                Team skipHome = Objects.equals(league.getId(), activeLeague.getId()) ? excludedHome : null;
                Team skipAway = Objects.equals(league.getId(), activeLeague.getId()) ? excludedAway : null;

                transactionTemplate.executeWithoutResult(status ->
                        matchEngine.simulateRestOfMatchDay(league, currentSeason, skipHome, skipAway)
                );

                int pendingAfter = transactionTemplate.execute(status -> countRemainingFixtures(league, seasonYear, currentWeek));
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

            boolean playoffWeekComplete = currentWeek == SeasonService.PLAYOFF_WEEK
                    && transactionTemplate.execute(status -> countRemainingFixtures(superLiga, seasonYear, currentWeek)) == 0;

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

            job.complete(payload);
        } catch (Exception ex) {
            log.error("Current-round background simulation failed for team {}", teamId, ex);
            job.fail("Current-round simulation failed. Check server logs.");
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
        return Math.toIntExact(matchFixtureRepository.countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(
                league.getId(), seasonYear, currentWeek
        ));
    }

    public record RoundSimulationSnapshot(
            String status,
            String action,
            String jobId,
            String message,
            int processedLeagues,
            int totalLeagues,
            String currentLeague,
            Map<String, Object> payload
    ) {
        static RoundSimulationSnapshot idle() {
            return new RoundSimulationSnapshot(
                    "idle",
                    "ROUND_SIMULATION_IDLE",
                    null,
                    "No round simulation is currently running.",
                    0,
                    0,
                    null,
                    null
            );
        }
    }

    private static final class RoundSimulationJob {
        private final String jobId = UUID.randomUUID().toString();
        private volatile String status = "running";
        private volatile String message = "Current-round simulation is running in the background.";
        private volatile int processedLeagues;
        private volatile int totalLeagues;
        private volatile String currentLeague;
        private volatile Map<String, Object> payload;

        static RoundSimulationJob running() {
            return new RoundSimulationJob();
        }

        boolean isRunning() {
            return "running".equals(status);
        }

        void updateProgress(int processed, int total, String leagueName) {
            this.processedLeagues = processed;
            this.totalLeagues = total;
            this.currentLeague = leagueName;
            this.message = leagueName == null
                    ? "Current-round simulation is running in the background."
                    : "Simulating fixtures in " + leagueName + "...";
        }

        void complete(Map<String, Object> payload) {
            this.status = "completed";
            this.payload = payload;
            this.processedLeagues = this.totalLeagues;
            this.currentLeague = null;
            this.message = String.valueOf(payload.getOrDefault("message", "Current-round simulation completed."));
        }

        void fail(String message) {
            this.status = "failed";
            this.currentLeague = null;
            this.message = message;
            this.payload = Map.of(
                    "status", "error",
                    "action", "ROUND_SIMULATION_FAILED",
                    "message", message
            );
        }

        RoundSimulationSnapshot toSnapshot() {
            String action = switch (status) {
                case "completed" -> payload != null ? String.valueOf(payload.get("action")) : "ROUND_SIMULATED";
                case "failed" -> "ROUND_SIMULATION_FAILED";
                default -> "ROUND_SIMULATION_RUNNING";
            };
            return new RoundSimulationSnapshot(
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
