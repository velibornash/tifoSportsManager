package org.example.footballmanager.controller;

import org.example.footballmanager.service.RegistrationService;
import org.example.footballmanager.service.AdminDatabaseAsyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private AdminDatabaseAsyncService adminDatabaseAsyncService;
    @Mock private RegistrationService registrationService;

    private AdminController adminController;

    @BeforeEach
    void setUp() {
        adminController = new AdminController(adminDatabaseAsyncService, registrationService);
    }

    @Test
    void initializeDatabaseStartsBackgroundJob() {
        AdminDatabaseAsyncService.AdminDatabaseSnapshot snapshot =
                new AdminDatabaseAsyncService.AdminDatabaseSnapshot(
                        "running",
                        "ADMIN_DATABASE_RUNNING",
                        "job-1",
                        "Database rebuild is running in the background.",
                        1,
                        6,
                        null
                );
        when(adminDatabaseAsyncService.startOrGetRunningJob("initialize")).thenReturn(snapshot);

        ResponseEntity<Map<String, Object>> response = adminController.initializeDatabase();

        verify(adminDatabaseAsyncService).startOrGetRunningJob("initialize");
        assertEquals(202, response.getStatusCode().value());
        assertEquals("running", response.getBody().get("status"));
        assertEquals("job-1", response.getBody().get("jobId"));
    }

    @Test
    void resetDatabaseStartsBackgroundJob() {
        AdminDatabaseAsyncService.AdminDatabaseSnapshot snapshot =
                new AdminDatabaseAsyncService.AdminDatabaseSnapshot(
                        "running",
                        "ADMIN_DATABASE_RUNNING",
                        "job-2",
                        "Database rebuild is running in the background.",
                        1,
                        6,
                        null
                );
        when(adminDatabaseAsyncService.startOrGetRunningJob("reset")).thenReturn(snapshot);

        ResponseEntity<Map<String, Object>> response = adminController.resetDatabase();

        verify(adminDatabaseAsyncService).startOrGetRunningJob("reset");
        assertEquals(202, response.getStatusCode().value());
        assertEquals("running", response.getBody().get("status"));
        assertEquals("job-2", response.getBody().get("jobId"));
    }

    @Test
    void getDatabaseJobStatusReturnsLatestSnapshot() {
        AdminDatabaseAsyncService.AdminDatabaseSnapshot snapshot =
                new AdminDatabaseAsyncService.AdminDatabaseSnapshot(
                        "completed",
                        "ADMIN_DATABASE_COMPLETED",
                        "job-3",
                        "Database rebuild completed.",
                        6,
                        6,
                        Map.of("status", "ok", "message", "Database rebuild completed.")
                );
        when(adminDatabaseAsyncService.getJobSnapshot()).thenReturn(snapshot);

        ResponseEntity<Map<String, Object>> response = adminController.getDatabaseJobStatus();

        verify(adminDatabaseAsyncService).getJobSnapshot();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("completed", response.getBody().get("status"));
        assertEquals("job-3", response.getBody().get("jobId"));
        assertEquals("Database rebuild completed.", response.getBody().get("message"));
    }
}
