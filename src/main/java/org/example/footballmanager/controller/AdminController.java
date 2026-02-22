package org.example.footballmanager.controller;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.util.DatabaseInitializer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        return ResponseEntity.ok("Database successfully cleared.");
    }
}