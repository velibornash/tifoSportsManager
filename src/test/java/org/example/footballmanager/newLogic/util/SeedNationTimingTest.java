package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

/**
 * How long does seeding one simulated country take, and does it get worse as the world fills up?
 *
 * <p>Owner, 2026-10-19: <i>"kako kad radim seed other nations prvih par nacija uradi celu piramidu za
 * par sekundi a svaku sledeću baš značajno sporije? postoji li šansa da se to nekako ubrza?"</i>
 *
 * <p><b>Why a bench database.</b> The test profile is H2 in-memory, which has neither PostgreSQL's
 * sequential scans nor its planner, so a number taken there is a number about H2. This runs against a
 * scratch PostgreSQL database ({@code sokker_bench}) that this class expects to be empty, so the
 * owner's world is never written to while a measurement runs.
 *
 * <p><b>What it prints.</b> One line per country, in the order they are seeded, with the time each
 * took and the size of the world at that moment. A cost that grows with the size of the world shows
 * up as a rising per-country time; a constant cost is a flat line. Those two look identical in a
 * total and completely different in a per-line breakdown, which is why the total the owner was reading
 * off the screen could not have answered this.
 *
 * <p>It calls {@link SimulatedWorldSeeder#seed(Country, int)} — the exact unit the admin button runs,
 * one per country, one transaction each — so what is timed is the real path and not a reimplementation
 * of it.
 *
 * <p>Deliberately not an assertion. A time threshold in a test reports "the world got slower on some
 * machine" and says nothing about why. The numbers are the deliverable.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sokker_bench",
        "spring.datasource.username=postgres",
        "spring.datasource.password=stojke",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class SeedNationTimingTest {

    private static final int COUNTRIES = 12;
    private static final int SEASON = 0;

    @Autowired SimulatedWorldSeeder seeder;
    @Autowired CountryRepository countries;
    @Autowired org.example.footballmanager.newLogic.repository.TeamRepository teams;

    @Test
    @DisplayName("seeds N simulated countries, one line each, and reports the time each took")
    void timeEachSeededNation() {
        // Only countries that want seeding, or seedAll would skip them and the numbers would be zeros.
        List<Country> fresh = new ArrayList<>();
        for (int i = 0; i < COUNTRIES; i++) {
            Country c = new Country();
            // Unique per run: a measurement that collides with the last one is a measurement of
            // nothing, and the bench database is meant to be dropped empty between runs.
            c.setName("Benchland " + (char) ('A' + i) + "-" + System.nanoTime());
            // iso_code is varchar(3); the bench database is dropped between runs, so the index
            // alone is unique enough and there is nothing to append.
            c.setIsoCode("B" + (char) ('A' + i));
            c.setState(CountryState.SIMULATED);
            fresh.add(countries.save(c));
        }

        System.out.println();
        System.out.println("=== SEEDING NATIONS, ONE PER LINE (empty bench database) ===");
        System.out.printf("   %-3s %-20s %9s %9s %11s%n",
                "#", "country", "secs", "running", "clubs so far");

        double running = 0.0;
        long clubs = 0;
        double[] times = new double[fresh.size()];
        for (int i = 0; i < fresh.size(); i++) {
            Country c = fresh.get(i);
            long teamsBefore = countClubs();
            long start = System.nanoTime();
            seeder.seed(c, SEASON);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            times[i] = seconds;
            running += seconds;
            clubs += countClubs() - teamsBefore;
            System.out.printf("   %-3d %-20s %9.2f %9.2f %11d%n",
                    i + 1, c.getName(), seconds, running, clubs);
        }

        int half = fresh.size() / 2;
        double firstHalf = 0.0;
        double secondHalf = 0.0;
        for (int i = 0; i < times.length; i++) {
            if (i < half) firstHalf += times[i]; else secondHalf += times[i];
        }
        System.out.println();
        System.out.printf("   first %d countries  %.2f s  (avg %.2f s)%n", half, firstHalf, firstHalf / half);
        System.out.printf("   last  %d countries  %.2f s  (avg %.2f s)%n",
                fresh.size() - half, secondHalf, secondHalf / Math.max(1, fresh.size() - half));
        System.out.printf("   ratio of the second half to the first: %.2fx%n",
                (secondHalf / Math.max(1, fresh.size() - half)) / Math.max(0.001, firstHalf / Math.max(1, half)));
        System.out.println("   A flat profile means the cost is constant. A rising one means it grows with");
        System.out.println("   the world, and the two have completely different fixes.");
    }

    /** The size of the world at this moment, which is the independent variable in this measurement. */
    private long countClubs() {
        return teams.count();
    }

    /**
     * The same work, but through the path the admin button actually uses.
     *
     * <p>{@link #timeEachSeededNation()} calls {@link SimulatedWorldSeeder#seed} through the injected
     * proxy, so {@code REQUIRES_NEW} applies and each country gets its own persistence context. The
     * button does not do that: {@code seedAll} calls {@code seed} on {@code this}, which never goes
     * through a proxy, so the annotation on {@code seed} is not applied on that path at all. This test
     * measures the button's path.
     *
     * <p><b>What makes the difference visible.</b> Time alone is ambiguous — the owner already knows it
     * gets slower and cannot say by how much or why. Statements executed per country is not ambiguous:
     * if each country costs the same number of queries, the cost is per-country and the fix is local to
     * whatever the constant is. If the number climbs country after country inside one run, the run is
     * re-doing work proportional to everything seeded before it, which is a different bug with a
     * different fix and would be invisible in a total.
     *
     * <p>Seeded in batches rather than all at once so the growth is sampled: three batches, each
     * reporting its own per-country query count.
     */
    @Test
    @DisplayName("seeds through seedAll, the path the admin button uses, and reports queries per country")
    void timeTheButtonsOwnPath() {
        List<Country> fresh = new ArrayList<>();
        for (int i = 0; i < COUNTRIES; i++) {
            Country c = new Country();
            c.setName("Buttonland " + (char) ('A' + i));
            c.setIsoCode("C" + (char) ('A' + i));
            c.setState(CountryState.SIMULATED);
            fresh.add(countries.save(c));
        }

        org.hibernate.stat.Statistics stats =
                ((org.hibernate.SessionFactory) emf).getStatistics();
        stats.setStatisticsEnabled(true);

        System.out.println();
        System.out.println("=== THE ADMIN BUTTON'S OWN PATH (seedAll, three batches) ===");
        System.out.printf("   %-9s %9s %11s %11s   %s%n",
                "batch", "secs", "statements", "per country", "clubs so far");

        long clubs = 0;
        for (int batch = 0; batch < 3; batch++) {
            List<Country> slice = fresh.subList(batch * 4, batch * 4 + 4);
            long before = stats.getPrepareStatementCount();
            long clubsBefore = countClubs();
            long start = System.nanoTime();
            seeder.seedAll(slice, SEASON);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            long used = stats.getPrepareStatementCount() - before;
            clubs += countClubs() - clubsBefore;
            System.out.printf("   %-9d %9.2f %11d %11d   %d%n",
                    batch + 1, seconds, used, used / slice.size(), clubs);
        }

        stats.setStatisticsEnabled(false);
        System.out.println();
        System.out.println("   A per-country statement count that climbs means each country is paying for");
        System.out.println("   every country before it. One that stays level means it is not that.");
    }

    /**
     * Seeding a country that <em>already has a pyramid</em>, which is what the button actually does to a
     * half-built world and what every second press does.
     *
     * <p>This is the one path {@link #timeEachSeededNation()} and {@link #timeTheButtonsOwnPath()} never
     * touch, because both seed countries that start empty. It takes a different branch inside
     * {@code fillStaticDivision}: a fresh country skips the per-club existence check entirely, while a
     * country that already has its divisions runs {@code findAllByNameIgnoreCase} once per club —
     * 310 times for a full pyramid.
     *
     * <p>That query is {@code lower(name) = lower(?)}. The index on {@code team} is a plain btree on
     * {@code name}, which cannot serve a comparison wrapped in {@code lower()}, so each of those lookups
     * is a candidate for a full scan of a table that holds every club in the world. If that is what is
     * happening then the second pass is not merely slower, it is slower <em>per country than the first
     * pass was</em>, and unlike the first pass it degrades as the world grows.
     */
    @Test
    @DisplayName("seeds countries a second time, once they already have a pyramid")
    void timeReSeedingAnAlreadyBuiltCountry() {
        List<Country> fresh = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Country c = new Country();
            c.setName("Reseedland " + (char) ('A' + i));
            c.setIsoCode("R" + (char) ('A' + i));
            c.setState(CountryState.SIMULATED);
            fresh.add(countries.save(c));
        }

        System.out.println();
        System.out.println("=== FIRST PASS (country starts empty) vs SECOND PASS (pyramid already there) ===");
        System.out.printf("   %-14s %-9s %9s %11s%n", "country", "pass", "secs", "statements");

        org.hibernate.stat.Statistics stats = ((org.hibernate.SessionFactory) emf).getStatistics();
        stats.setStatisticsEnabled(true);

        for (int pass = 1; pass <= 2; pass++) {
            for (Country c : fresh) {
                long before = stats.getPrepareStatementCount();
                long start = System.nanoTime();
                seeder.seedAll(List.of(c), SEASON);
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                long used = stats.getPrepareStatementCount() - before;
                System.out.printf("   %-14s %-9d %9.2f %11d%n", c.getName(), pass, seconds, used);
            }
        }
        stats.setStatisticsEnabled(false);
        System.out.println();
        System.out.println("   Roughly equal statements per country means the two passes do the same work");
        System.out.println("   and the cost is per-country. A second pass several times larger means the");
        System.out.println("   second pass is re-reading the whole world once per club.");
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    @Autowired
    private jakarta.persistence.EntityManagerFactory emf;
}