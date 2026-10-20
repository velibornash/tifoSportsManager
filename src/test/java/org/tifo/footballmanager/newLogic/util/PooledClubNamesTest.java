package org.tifo.footballmanager.newLogic.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.util.naming.CountryNamePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The seeder draws its clubs from the country's pool (redesign.md R-23).
 *
 * <p>The owner: *"za svaku drzavu od ovih 47 istrazis najcesca imena timova i imena i prezimena i
 * gradova i dodas defaulte koji se mogu menjati i koje seederi koriste za AI timove/igrace jer ovi
 * genericki su objektivno ruzni"* - the pools are what the AI teams and players are named from, and
 * the generic names they replace are objectively ugly.
 *
 * <p>Three things have to be true at once, and each of them has been wrong at some point:
 * the clubs carry the pool's names and not their country code; no two clubs share a name (the
 * seeder's find-by-name would find the first and not create the second); and no two tiers share the
 * same ten names (the offset used to be the division's level, so the first division of the first tier
 * and the first division of the second tier were given the same names).
 *
 * <p>The test database is an in-memory H2, so a country seeded here touches nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class PooledClubNamesTest {

    @Autowired private SimulatedWorldSeeder seeder;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private CountryNamePool pool;

    @Test
    @DisplayName("a country's clubs are named from its pool, not after its country code")
    void theClubsCarryThePoolNames() {
        Country croatia = seedCroatia();

        List<Team> clubs = teams.findByCountryId(croatia.getId());
        // The pool is walked in order, so the first division's clubs are the pool's first ten.
        List<String> poolNames = pool.forCountry("CRO").orElseThrow().clubs();

        assertTrue(clubs.size() >= 10, "Croatia was seeded with " + clubs.size() + " clubs");
        for (Team club : clubs) {
            String name = club.getName();
            assertFalse(name.startsWith("CRO ") && name.contains("FC"),
                    "the club is still called after its country code: " + name);
            boolean fromThePool = poolNames.stream()
                    .anyMatch(candidate -> name.equals(candidate) || name.startsWith(candidate + " "));
            assertTrue(fromThePool, "the club is not from Croatia's pool: " + name);
        }
    }

    /**
     * Croatia, seeded.
     *
     * <p>The catalogue already has every country in the test database, so Croatia is fetched rather
     * than created - and seeding her twice is a no-op, which is the seeder's own promise.
     */
    private Country seedCroatia() {
        Country croatia = countries.findByIsoCode("CRO")
                .orElseGet(() -> countries.save(country("Croatia Pool Test", "CRO")));
        seeder.seed(croatia, 1);
        return croatia;
    }

    /** A country to seed, in the same shape the seeder's own fixture uses. */
    private Country country(String name, String iso) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(iso);
        country.setState(org.tifo.footballmanager.newLogic.model.CountryState.SIMULATED);
        return country;
    }

    @Test
    @DisplayName("no two clubs in a country have the same name")
    void everyClubHasItsOwnName() {
        Country croatia = seedCroatia();

        Set<String> names = new HashSet<>();
        List<Team> clubs = teams.findByCountryId(croatia.getId());
        for (Team club : clubs) {
            assertTrue(names.add(club.getName().toLowerCase(java.util.Locale.ROOT)),
                    "two clubs share the name " + club.getName());
        }
        assertEquals(clubs.size(), names.size(), "the country has fewer distinct names than clubs");
    }
}
