package org.example.footballmanager;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Base Test Class for all integration tests
 * 
 * Configures:
 * - Spring Boot test context
 * - Random port for web environment
 * - MockMvc for testing controllers
 * - Test profile with H2 database
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseTest {
    // Base class for all integration tests
    //
    // MOCK, not RANDOM_PORT. Both subclasses exercise controllers through MockMvc only - neither
    // uses TestRestTemplate or @LocalServerPort - so RANDOM_PORT was starting a real embedded
    // HTTP server for nothing. It also opens an actual listening port on the machine running the
    // build, which is confusing when a dev server is already on 8080, and it was costing about
    // 45 seconds of wall clock on the Spring context alone.
    // Switch back to RANDOM_PORT only if a test genuinely needs to make real HTTP calls.

}

