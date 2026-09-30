package org.example.commonmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.americanfootballmanager.controller.AfController;
import org.example.americanfootballmanager.service.AfDataInitializer;
import org.example.basketballmanager.controller.BbController;
import org.example.basketballmanager.data.BbDataInitializer;
import org.example.commonmanager.util.StartupInitializer;
import org.example.footballmanager.newLogic.util.DatabaseInitializer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDatabaseAsyncService {

    private final StartupInitializer startupInitializer;
    private final BbController bbController;
    private final AfController afController;
    private final BbDataInitializer bbDataInitializer;
    private final AfDataInitializer afDataInitializer;
    private final DatabaseInitializer databaseInitializer;
    private final org.example.footballmanager.newLogic.repository.CompetitionRepository competitionRepository;
    private final org.example.footballmanager.newLogic.repository.TeamRepository teamRepository;

    private final AtomicLong jobSequence = new AtomicLong(0L);
    private final AtomicReference<AdminDatabaseSnapshot> currentSnapshot = new AtomicReference<>(
            new AdminDatabaseSnapshot(0L, "idle", "idle", "No database job is running.", 0, 0, Map.of())
    );

    public AdminDatabaseSnapshot startOrGetRunningJob(String action) {
        synchronized (this) {
            AdminDatabaseSnapshot snapshot = currentSnapshot.get();
            if ("running".equals(snapshot.status()) && action.equals(snapshot.action())) {
                return snapshot;
            }

            long jobId = jobSequence.incrementAndGet();
            int totalSteps = stepsFor(action);
            currentSnapshot.set(new AdminDatabaseSnapshot(
                    jobId,
                    action,
                    "running",
                    messageFor(action) + " Started.",
                    0,
                    totalSteps,
                    Map.of("startedAt", LocalDateTime.now().toString())
            ));

            try {
                execute(action);
                Map<String, Object> payload = successPayload(action, jobId);
                currentSnapshot.set(new AdminDatabaseSnapshot(
                        jobId,
                        action,
                        "completed",
                        messageFor(action) + " Completed successfully.",
                        totalSteps,
                        totalSteps,
                        payload
                ));
            } catch (Exception ex) {
                Throwable root = rootCause(ex);
                Map<String, Object> payload = failurePayload(action, jobId, root);
                currentSnapshot.set(new AdminDatabaseSnapshot(
                        jobId,
                        action,
                        "failed",
                        root.getMessage() != null ? root.getMessage() : "Database job failed.",
                        0,
                        totalSteps,
                        payload
                ));
                log.error("Database job '{}' failed: {}", action, root.getMessage(), ex);
            }

            return currentSnapshot.get();
        }
    }

    public AdminDatabaseSnapshot getJobSnapshot() {
        return currentSnapshot.get();
    }

    private void execute(String action) {
        switch (String.valueOf(action).toLowerCase()) {
            case "reset" -> {
                // Only clear data — does NOT rebuild. User must call Initialize separately.
                databaseInitializer.clearDatabaseOnly();
                bbController.resetBasketball();
                afController.resetAmericanFootball();
                // BB/AF re-init is NOT called — they stay cleared until user clicks Initialize
            }
            case "initialize" -> {
                // The whole world, not just the pyramid.
                //
                // This called initSerbianFootballStructure() directly, which is only one of the ways
                // the world is built. The catalogue, the 96 national sides, the legacy-row cleanup,
                // the integrity repair, the selector appointments and the internationals all live in
                // ensureBaselineDataOnStartup() - the boot listener. Calling the pyramid step on its
                // own therefore built 310 clubs in a world with no countries, and nothing said so:
                // the panel reported success and the country page came up empty.
                //
                // Going through the same entry point as boot means an initialized database and a
                // booted one are the same world, whichever door you came in by.
                databaseInitializer.ensureBaselineDataOnStartup();
                databaseInitializer.seedOwnerAfterReset();
                startupInitializer.run();
                bbDataInitializer.initBasketballData();
                afDataInitializer.initAmericanFootballData();
                verifyFootballWorldWasBuilt();
            }
            default -> throw new IllegalArgumentException("Unsupported database job action: " + action);
        }
    }

    /**
     * Fails the job if the rebuild produced no football.
     *
     * <p>Reported by the owner as: reset, then initialise, then "no leagues at all" — with the panel
     * saying it had worked. It had not. {@code ensureBaselineDataOnStartup()} catches its own seeding
     * failures on purpose, because it is also the boot listener and must not take the application down
     * over a partial world. The side effect is that it returns normally whether or not it built
     * anything, and the job reported success off that return.
     *
     * <p>So the job stops taking its word for it. Two counts and an exception: a reset that leaves the
     * world empty is a failed reset, and saying so is the whole difference between a five-minute
     * diagnosis and an evening of wondering which button was pressed.
     *
     * <p>Deliberately not asserting a club <em>count</em>. A small world is a legitimate world, and a
     * check that fails on a legitimately small one is a check that gets disabled.
     */
    private void verifyFootballWorldWasBuilt() {
        long leagues = competitionRepository.count();
        long clubs = teamRepository.count();

        if (leagues == 0 || clubs == 0) {
            throw new IllegalStateException(
                    "Initialize reported success but the world is empty: "
                            + leagues + " league(s) and " + clubs + " club(s) in the database. "
                            + "The rebuild did not complete. See the startup log for "
                            + "'Startup initialization failed' and the reason it was swallowed.");
        }
        log.info("Initialize verified: {} league(s), {} club(s) in the rebuilt world.", leagues, clubs);
    }

    private int stepsFor(String action) {
        return switch (String.valueOf(action).toLowerCase()) {
            case "reset" -> 3;
            case "initialize" -> 4;
            default -> 1;
        };
    }

    private String messageFor(String action) {
        return switch (String.valueOf(action).toLowerCase()) {
            case "reset" -> "Clearing database (preserves user + tactics).";
            case "initialize" -> "Database initialization in progress.";
            default -> "Database job in progress.";
        };
    }

    private Map<String, Object> successPayload(String action, long jobId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId);
        payload.put("action", action);
        payload.put("status", "completed");
        payload.put("message", messageFor(action) + " Completed successfully.");
        payload.put("completedAt", LocalDateTime.now().toString());
        return payload;
    }

    private Map<String, Object> failurePayload(String action, long jobId, Throwable root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId);
        payload.put("action", action);
        payload.put("status", "failed");
        payload.put("message", root.getMessage() != null ? root.getMessage() : "Database job failed.");
        payload.put("errorType", root.getClass().getName());
        payload.put("failedAt", LocalDateTime.now().toString());
        return payload;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public record AdminDatabaseSnapshot(
            long jobId,
            String action,
            String status,
            String message,
            int completedSteps,
            int totalSteps,
            Map<String, Object> payload
    ) {
    }
}
