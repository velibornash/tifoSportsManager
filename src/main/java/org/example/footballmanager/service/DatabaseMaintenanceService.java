package org.example.footballmanager.service;

import lombok.RequiredArgsConstructor;
import org.example.footballmanager.util.DatabaseInitializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
public class DatabaseMaintenanceService {

    private final DatabaseInitializer databaseInitializer;
    private final TransactionTemplate transactionTemplate;

    public void rebuildDatabase(Consumer<String> progressListener) {
        transactionTemplate.executeWithoutResult(status ->
                databaseInitializer.resetAndInitializeDatabase(progressListener)
        );
    }
}
