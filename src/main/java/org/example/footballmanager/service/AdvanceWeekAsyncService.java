package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.model.Competition;
import org.example.footballmanager.model.Team;
import org.example.footballmanager.repository.CompetitionRepository;
import org.example.footballmanager.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdvanceWeekAsyncService {

    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final SeasonService seasonService;
    private final TrainingProgressionService trainingProgressionService;
    private final TransactionTemplate transactionTemplate;

    private final Map<Long, AdvanceWeekJob> jobsByTeamId = new ConcurrentHashMap<>();

    public AdvanceWeekSnapshot startOrGetRunningJob(Long teamId) {
        AdvanceWeekJob job = jobsByTeamId.compute(teamId, (id, existing) -> {
            if (existing != null && existing.isRunning()) {
                return existing;
            }

            AdvanceWeekJob created = AdvanceWeekJob.running();
            CompletableFuture.runAsync(() ->
                    transactionTemplate.executeWithoutResult(status -> runAdvanceWeek(id, created))
            );
            return created;
        });
        return job.toSnapshot();
    }

    public AdvanceWeekSnapshot getJobSnapshot(Long teamId) {
        AdvanceWeekJob job = jobsByTeamId.get(teamId);
        return job == null ? AdvanceWeekSnapshot.idle() : job.toSnapshot();
    }

    private void runAdvanceWeek(Long teamId, AdvanceWeekJob job) {
        try {
            Team userTeam = teamRepository.findById(teamId).orElse(null);
            if (userTeam == null) {
                job.fail("User team not found.");
                return;
            }

            Competition superLiga = competitionRepository.findById(1L).orElse(null);
            if (superLiga == null) {
                job.fail("League not found.");
                return;
            }

            int currentWeek = seasonService.getCurrentWeek();
            int currentSeasonYear = seasonService.getActiveSeasonYear();
            int remainingFixtures = countRemainingFixturesAcrossLeagues(currentSeasonYear, currentWeek);
            if (remainingFixtures > 0) {
                job.complete(Map.of(
                        "status", "blocked",
                        "action", "ROUND_NOT_COMPLETE",
                        "message", "Current week still has unfinished fixtures. Play your match and simulate the remaining results before advancing the calendar.",
                        "remainingFixtures", remainingFixtures
                ));
                return;
            }

            boolean trainingRan = currentWeek != SeasonService.PLAYOFF_WEEK && userTeam.getId() != null;
            if (trainingRan) {
                job.updateMessage("Running weekly training...");
                trainingProgressionService.runWeeklyTraining(userTeam.getId());
            }

            job.updateMessage("Advancing calendar and processing seasonal updates...");
            seasonService.advanceWeekAndHandleSeasonTransition(superLiga);

            int nextWeek = seasonService.getCurrentWeek();
            int nextSeasonYear = seasonService.getActiveSeasonYear();
            String message = nextSeasonYear != currentSeasonYear
                    ? "Week advanced and a new season has started."
                    : "Week advanced successfully.";
            if (trainingRan) {
                message = "Weekly training completed. " + message;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("action", "WEEK_ADVANCED");
            payload.put("message", message);
            payload.put("currentWeek", nextWeek);
            payload.put("seasonYear", nextSeasonYear);
            job.complete(payload);
        } catch (Exception ex) {
            log.error("Advance-week background job failed for team {}", teamId, ex);
            job.fail("Advance week failed. Check server logs.");
        }
    }

    private int countRemainingFixturesAcrossLeagues(int seasonYear, int currentWeek) {
        int remaining = 0;
        for (Competition league : seasonService.getSerbianLeaguesInOrder()) {
            seasonService.ensureEntriesForSeasonCompetition(league, seasonYear);
            seasonService.ensureDoubleRoundRobinSchedule(league, seasonYear);
            if (currentWeek == SeasonService.PLAYOFF_WEEK && java.util.Objects.equals(league.getTier(), 1)) {
                seasonService.ensurePlayoffWeekFixtures(league, seasonYear);
            } else if (currentWeek == SeasonService.FRIENDLY_WEEK) {
                seasonService.ensureFriendlyWeekFixtures(league, seasonYear);
            }
            remaining += seasonService.countRemainingFixturesForWeek(league.getId(), seasonYear, currentWeek);
        }
        return remaining;
    }

    public record AdvanceWeekSnapshot(
            String status,
            String action,
            String jobId,
            String message,
            Map<String, Object> payload
    ) {
        static AdvanceWeekSnapshot idle() {
            return new AdvanceWeekSnapshot(
                    "idle",
                    "WEEK_ADVANCE_IDLE",
                    null,
                    "No week-advance job is currently running.",
                    null
            );
        }
    }

    private static final class AdvanceWeekJob {
        private final String jobId = UUID.randomUUID().toString();
        private volatile String status = "running";
        private volatile String message = "Advancing week in the background...";
        private volatile Map<String, Object> payload;

        static AdvanceWeekJob running() {
            return new AdvanceWeekJob();
        }

        boolean isRunning() {
            return "running".equals(status);
        }

        void updateMessage(String message) {
            this.message = message;
        }

        void complete(Map<String, Object> payload) {
            this.status = "completed";
            this.payload = payload;
            this.message = String.valueOf(payload.getOrDefault("message", "Week advanced."));
        }

        void fail(String message) {
            this.status = "failed";
            this.message = message;
            this.payload = Map.of(
                    "status", "error",
                    "action", "WEEK_ADVANCE_FAILED",
                    "message", message
            );
        }

        AdvanceWeekSnapshot toSnapshot() {
            String action = switch (status) {
                case "completed" -> payload != null ? String.valueOf(payload.get("action")) : "WEEK_ADVANCED";
                case "failed" -> "WEEK_ADVANCE_FAILED";
                default -> "WEEK_ADVANCE_RUNNING";
            };
            return new AdvanceWeekSnapshot(status, action, jobId, message, payload);
        }
    }
}
