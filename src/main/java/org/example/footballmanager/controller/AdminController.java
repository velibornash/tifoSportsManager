package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.MessageResponseDTO;
import org.example.footballmanager.dto.RegistrationReviewRequestDTO;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.User;
import org.example.footballmanager.service.AdminDatabaseAsyncService;
import org.example.footballmanager.service.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final RegistrationService registrationService;

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

    @PostMapping("/registration-requests/{requestId}/approve")
    public ResponseEntity<MessageResponseDTO> approveRegistration(@PathVariable Long requestId,
                                                                 @AuthenticationPrincipal User reviewer,
                                                                 @RequestBody(required = false) RegistrationReviewRequestDTO dto) {
        try {
            RegistrationRequest request = registrationService.approveRequest(requestId, reviewer, dto != null ? dto.getNote() : null);
            return ResponseEntity.ok(new MessageResponseDTO(
                    "APPROVED",
                    request.getTeam().getName() + " now belongs to " + request.getUsername() + "."
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new MessageResponseDTO("INVALID_REQUEST", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponseDTO("CONFLICT", ex.getMessage()));
        }
    }

    @PostMapping("/registration-requests/{requestId}/reject")
    public ResponseEntity<MessageResponseDTO> rejectRegistration(@PathVariable Long requestId,
                                                                @AuthenticationPrincipal User reviewer,
                                                                @RequestBody(required = false) RegistrationReviewRequestDTO dto) {
        try {
            RegistrationRequest request = registrationService.rejectRequest(requestId, reviewer, dto != null ? dto.getNote() : null);
            return ResponseEntity.ok(new MessageResponseDTO(
                    "REJECTED",
                    "Ownership request for " + request.getUsername() + " was rejected."
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new MessageResponseDTO("INVALID_REQUEST", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new MessageResponseDTO("CONFLICT", ex.getMessage()));
        }
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
