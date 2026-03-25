package org.example.footballmanager.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.github.javafaker.Faker;

/**
 * Test Configuration for providing test beans
 * Used in integration and E2E tests
 */
@TestConfiguration
public class TestConfig {
    
    @Bean
    public Faker faker() {
        return new Faker();
    }
}

