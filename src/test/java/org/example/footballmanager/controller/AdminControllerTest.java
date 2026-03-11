package org.example.footballmanager.controller;

import org.example.footballmanager.service.RegistrationService;
import org.example.footballmanager.util.DatabaseInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private DatabaseInitializer databaseInitializer;
    @Mock private RegistrationService registrationService;

    private AdminController adminController;

    @BeforeEach
    void setUp() {
        adminController = new AdminController(databaseInitializer, registrationService);
    }

    @Test
    void initializeDatabaseRebuildsFullBaseline() {
        ResponseEntity<String> response = adminController.initializeDatabase();

        verify(databaseInitializer).resetAndInitializeDatabase();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("Database successfully initialized.", response.getBody());
    }

    @Test
    void resetDatabaseRebuildsFullBaseline() {
        ResponseEntity<String> response = adminController.resetDatabase();

        verify(databaseInitializer).resetAndInitializeDatabase();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("Database successfully reset and rebuilt. Owner user has been restored.", response.getBody());
    }
}