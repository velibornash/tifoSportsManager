package org.example.commonmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.service.AdminDatabaseAsyncService;
import org.example.footballmanager.newLogic.dto.transfer.TransferDTO;
import org.example.footballmanager.newLogic.service.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminDatabaseAsyncService adminDatabaseAsyncService;
    private final TransferService transferService;

    @PostMapping("/initialize-db")
    public ResponseEntity<Map<String, Object>> initializeDatabase() {
        return ResponseEntity.accepted().body(toDatabaseJobResponse(
                adminDatabaseAsyncService.startOrGetRunningJob("initialize")
        ));
    }

    @PostMapping("/reset-db")
    public ResponseEntity<Map<String, Object>> resetDatabase() {
        return ResponseEntity.accepted().body(toDatabaseJobResponse(
                adminDatabaseAsyncService.startOrGetRunningJob("reset")
        ));
    }

    @GetMapping("/database-job/status")
    public ResponseEntity<Map<String, Object>> getDatabaseJobStatus() {
        return ResponseEntity.ok(toDatabaseJobResponse(adminDatabaseAsyncService.getJobSnapshot()));
    }

    /**
     * Force a player off the transfer list, ignoring any registered interest or live offers.
     *
     * <p>Operator escape hatch for a stuck listing. Kept under {@code /admin} deliberately: the
     * {@code /transfers} prefix is not role-guarded, so putting it there would let any authenticated
     * user delist another club's player.
     *
     * <p>Also the intended route for force-listing a specific player (for example a national-team
     * player who should not be sitting in a bot club).
     */
    @DeleteMapping("/transfer-list/{playerId}")
    public TransferDTO forceUnlist(@PathVariable Long playerId) {
        return transferService.forceUnlist(playerId);
    }

    private Map<String, Object> toDatabaseJobResponse(AdminDatabaseAsyncService.AdminDatabaseSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (snapshot.payload() != null && ("completed".equals(snapshot.status()) || "failed".equals(snapshot.status()))) {
            payload.putAll(snapshot.payload());
        }
        payload.put("status", snapshot.status());
        payload.put("action", snapshot.action());
        payload.put("jobId", snapshot.jobId());
        payload.put("message", snapshot.message());
        payload.put("completedSteps", snapshot.completedSteps());
        payload.put("totalSteps", snapshot.totalSteps());
        return payload;
    }
}
