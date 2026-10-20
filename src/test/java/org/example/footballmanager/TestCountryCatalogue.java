package org.example.footballmanager;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Gives a test class the country catalogue, and nothing else.
 *
 * <p><b>Why this exists, and why it is not the whole world.</b> Seven test classes were written against a
 * {@code test} profile that seeded nine countries with known youthRatings, and that seeding was deliberately
 * removed on 2026-10-01 so that starting the application starts the application and nothing else. They fail
 * with *"Seed data is missing country BRA"*.
 *
 * <p>The obvious repair is {@code DatabaseInitializer.ensureBaselineDataOnStartup()}. It is public, idempotent,
 * and builds exactly the world these tests want — **and it builds the entire world**, tens of thousands of
 * writes. One test class ran for **thirteen minutes** without finishing, so it was reverted rather than
 * shipped. That would have defeated the decision it was meant to serve.
 *
 * <p>This seeds only {@code WorldCatalogSeeder.seedAll()}: **48 country rows** — the world's actual catalogue,
 * no pyramid and no squads. A few hundred writes, and the real catalogue rather than a fixture that will drift
 * from it.
 *
 * <p>What a test needs <i>beyond</i> the catalogue — clubs, leagues, squads — it builds itself. A single shared
 * world would be cheaper and would reintroduce the order-dependence that made two of this session's own tests
 * wrong: both were green alone and red in the suite because they measured global state.
 *
 * <p>Brought in with {@code @Import(TestCountryCatalogue.class)} — the seeder is injected, and it is a
 * {@code @TestConfiguration} because a test class cannot carry {@code @Bean} methods; Spring refuses that
 * outright with *"Test classes cannot include @Bean methods"*, which is how the first two attempts failed.
 */
@org.springframework.boot.test.context.TestConfiguration
public class TestCountryCatalogue {

    @Autowired
    private org.example.footballmanager.newLogic.util.WorldCatalogSeeder seeder;

    /** The 48-country catalogue. Idempotent, so calling it from every {@code @BeforeEach} is cheap. */
    public void seed() {
        seeder.seedAll();
    }
}
