package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.dto.MessageResponseDTO;
import org.example.footballmanager.dto.RegistrationReviewRequestDTO;
import org.example.footballmanager.model.RegistrationRequest;
import org.example.footballmanager.model.User;
import org.example.footballmanager.service.RegistrationService;
import org.example.footballmanager.service.ResetService;
import org.example.footballmanager.util.DatabaseInitializer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DatabaseInitializer databaseInitializer;
    private final ResetService resetService;
    private final RegistrationService registrationService;

    @PostMapping("/initialize-db")
    public ResponseEntity<String> initializeDatabase() {
        databaseInitializer.init();
        return ResponseEntity.ok("Database successfully initialized.");
    }

    @PostMapping("/reset-db")
    public ResponseEntity<String> resetDatabase() {
        resetService.resetDatabase();
        databaseInitializer.seedOwnerAfterReset();
        return ResponseEntity.ok("Database successfully cleared. Owner user has been restored.");
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
}
