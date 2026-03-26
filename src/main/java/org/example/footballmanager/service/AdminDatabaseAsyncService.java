package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminDatabaseAsyncService {

    private final DatabaseMaintenanceService databaseMaintenanceService;

    private volatile AdminDatabaseJob currentJob;

    public synchronized AdminDatabaseSnapshot startOrGetRunningJob(String requestedAction) {
        if (currentJob != null && currentJob.isRunning()) {
            return currentJob.toSnapshot();
        }

        AdminDatabaseJob created = AdminDatabaseJob.running(requestedAction);
        currentJob = created;
        CompletableFuture.runAsync(() -> runJob(created));
        return created.toSnapshot();
    }

    public AdminDatabaseSnapshot getJobSnapshot() {
        AdminDatabaseJob job = currentJob;
        return job == null ? AdminDatabaseSnapshot.idle() : job.toSnapshot();
    }

    private void runJob(AdminDatabaseJob job) {
        try {
            job.updateProgress(1, 6, "Snapshotting tactics profiles...");
            databaseMaintenanceService.rebuildDatabase(message -> {
                int nextStep = Math.min(job.totalSteps, job.completedSteps + 1);
                job.updateProgress(nextStep, job.totalSteps, message);
            });

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("message", "Database rebuild completed.");
            payload.put("requestedAction", job.requestedAction);
            job.complete(payload);
        } catch (Exception ex) {
            log.error("Admin database job failed", ex);
            job.fail("Database rebuild failed. Check server logs.");
        }
    }

    public record AdminDatabaseSnapshot(
            String status,
            String action,
            String jobId,
            String message,
            int completedSteps,
            int totalSteps,
            Map<String, Object> payload
    ) {
        static AdminDatabaseSnapshot idle() {
            return new AdminDatabaseSnapshot(
                    "idle",
                    "ADMIN_DATABASE_IDLE",
                    null,
                    "No database rebuild is currently running.",
                    0,
                    0,
                    null
            );
        }
    }

    private static final class AdminDatabaseJob {
        private final String jobId = UUID.randomUUID().toString();
        private final String requestedAction;
        private volatile String status = "running";
        private volatile String message = "Database rebuild is running in the background.";
        private volatile int completedSteps = 0;
        private final int totalSteps = 6;
        private volatile Map<String, Object> payload;

        private AdminDatabaseJob(String requestedAction) {
            this.requestedAction = requestedAction;
        }

        static AdminDatabaseJob running(String requestedAction) {
            return new AdminDatabaseJob(requestedAction);
        }

        boolean isRunning() {
            return "running".equals(status);
        }

        void updateProgress(int completedSteps, int totalSteps, String message) {
            this.completedSteps = Math.max(0, Math.min(completedSteps, totalSteps));
            this.message = message == null || message.isBlank()
                    ? "Database rebuild is running in the background."
                    : message;
        }

        void complete(Map<String, Object> payload) {
            this.status = "completed";
            this.completedSteps = this.totalSteps;
            this.payload = payload;
            this.message = String.valueOf(payload.getOrDefault("message", "Database rebuild completed."));
        }

        void fail(String message) {
            this.status = "failed";
            this.message = message;
            this.payload = Map.of("status", "error", "message", message);
        }

        AdminDatabaseSnapshot toSnapshot() {
            String action = switch (status) {
                case "running" -> "ADMIN_DATABASE_RUNNING";
                case "completed" -> "ADMIN_DATABASE_COMPLETED";
                case "failed" -> "ADMIN_DATABASE_FAILED";
                default -> "ADMIN_DATABASE_IDLE";
            };
            return new AdminDatabaseSnapshot(status, action, jobId, message, completedSteps, totalSteps, payload);
        }
    }
}
