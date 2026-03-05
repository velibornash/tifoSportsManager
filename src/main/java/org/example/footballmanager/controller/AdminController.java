package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.service.ResetService;
import org.example.footballmanager.util.DatabaseInitializer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DatabaseInitializer databaseInitializer;
    private final ResetService resetService;

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
}
