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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminDatabaseAsyncService adminDatabaseAsyncService;
    private final org.example.footballmanager.newLogic.service.WorldIntegrityService worldIntegrityService;
    private final TransferService transferService;
    private final org.example.footballmanager.newLogic.service.RegistrationService registrationService;
    private final org.example.footballmanager.newLogic.repository.RegistrationRequestRepository registrationRequests;

    /**
     * The pending registration requests, for the admin queue.
     *
     * <p><b>All four legs of registration were unwired</b> (owner, 2026-09-28): {@code register.html}
     * posted to an endpoint that did not exist, the admin queue called one that did not exist, and
     * {@code RegistrationService.approveRequest} and {@code rejectRequest} — both fully written — had
     * no caller anywhere. So applying to play did nothing, and approving an application could not be
     * done from the interface. The only accounts in the game were the two hand-seeded ones.
     *
     * <p>Each request now carries the country its applicant chose, because the club was reserved
     * from that country's leagues and a reviewer looking at a club name alone cannot tell what the
     * applicant actually asked for.
     */
    @GetMapping("/registration-requests")
    public ResponseEntity<java.util.List<Map<String, Object>>> listPendingRegistrations() {
        return ResponseEntity.ok(registrationRequests
                .findByStatus(org.example.footballmanager.newLogic.model.RegistrationRequestStatus.PENDING)
                .stream()
                .map(request -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", request.getId());
                    row.put("username", request.getUsername());
                    row.put("email", request.getEmail());
                    row.put("countryCode", request.getCountryCode());
                    row.put("countryName",
                            org.example.footballmanager.newLogic.model.CountryCatalog
                                    .byCode(request.getCountryCode())
                                    .map(org.example.footballmanager.newLogic.model.CountryCatalog::displayName)
                                    .orElse(request.getCountryCode()));
                    row.put("teamId", request.getTeam() != null ? request.getTeam().getId() : null);
                    row.put("teamName", request.getTeam() != null ? request.getTeam().getName() : null);
                    row.put("createdAt", request.getCreatedAt());
                    return row;
                })
                .toList());
    }

    /** Approves a request, which is what actually creates the account and links the club. */
    @PostMapping("/registration-requests/{id}/{action:approve|reject}")
    public ResponseEntity<Map<String, Object>> decideRegistration(
            @PathVariable Long id,
            @PathVariable String action,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.example.commonmanager.model.User reviewer,
            @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        try {
            var request = "approve".equalsIgnoreCase(action)
                    ? registrationService.approveRequest(id, reviewer, note)
                    : registrationService.rejectRequest(id, reviewer, note);
            return ResponseEntity.ok(Map.of(
                    "id", request.getId(),
                    "status", request.getStatus().name(),
                    "countryCode", request.getCountryCode() == null ? "" : request.getCountryCode()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

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

    /**
     * Is the world whole, and if not, put it back (owner, 2026-09-29).
     *
     * <p>Reachable because a reset or an interrupted start must not need a developer to fix. Reports
     * on GET without changing anything, so it can be asked the question safely; POST repairs.
     */
    @GetMapping("/world-integrity")
    public ResponseEntity<Map<String, Object>> worldIntegrity() {
        return ResponseEntity.ok(worldIntegrityService.report());
    }

    @PostMapping("/world-integrity/repair")
    public ResponseEntity<Map<String, Object>> repairWorld() {
        return ResponseEntity.ok(worldIntegrityService.repair());
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
