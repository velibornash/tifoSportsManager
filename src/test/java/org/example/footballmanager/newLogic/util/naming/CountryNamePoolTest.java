package org.example.footballmanager.newLogic.util.naming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-country name pools (redesign.md R-23).
 *
 * <p>The pools decide what a club in a country is called and what its people are called, so the two
 * things that matter are checked here: a country with a pool gets names <b>from that pool</b>, and a
 * country without one is left exactly as it was.
 */
class CountryNamePoolTest {

    private final CountryNamePool pool = new CountryNamePool();

    @Test
    @DisplayName("a country with a pool has club names, cities and people's names")
    void aPooledCountryHasNames() {
        CountryNamePool.Pool serbia = pool.forCountry("SRB").orElseThrow();
        assertTrue(serbia.hasClubs(), "Serbia's pool has club names");
        assertTrue(serbia.hasCities(), "Serbia's pool has cities");
        assertTrue(serbia.hasFirstNames(), "Serbia's pool has first names");
        assertTrue(serbia.hasLastNames(), "Serbia's pool has last names");
    }

    @Test
    @DisplayName("a country without a pool has none, so the caller falls back")
    void anUnpooledCountryHasNone() {
        assertTrue(pool.forCountry("ZZZ").isEmpty(), "a country with no file has no pool");
        assertTrue(pool.forCountry("").isEmpty(), "an empty country code has no pool");
        assertTrue(pool.forCountry(null).isEmpty(), "a null country code has no pool");
    }

    @Test
    @DisplayName("the ISO code is matched in any case")
    void theCountryIsFoundInAnyCase() {
        assertTrue(pool.forCountry("srb").isPresent(), "the pool is found in lower case");
        assertTrue(pool.forCountry(" Srb ").isPresent(), "the pool is found with the spaces around it");
    }

    @Test
    @DisplayName("the names come out of the pool, never out of thin air")
    void theNamesComeFromThePool() {
        CountryNamePool.Pool serbia = pool.forCountry("SRB").orElseThrow();
        String club = pool.randomClubName("SRB", new Random(7)).orElseThrow();
        assertTrue(serbia.clubs().contains(club), "the club name is one of the country's: " + club);
    }

    @Test
    @DisplayName("the same seed gives the same world, so a rebuild is reproducible")
    void theSameSeedGivesTheSameName() {
        String first = pool.randomFirstName("SRB", new Random(11)).orElseThrow();
        String again = pool.randomFirstName("SRB", new Random(11)).orElseThrow();
        assertEquals(first, again, "the same seed must give the same name, or the world changes "
                + "every time it is built");
    }

    @Test
    @DisplayName("the pool hands out more than one name")
    void thePoolHandsOutMoreThanOneName() {
        // A dozen draws, not two: two seeds can collide in a pool of thirty-two, and a test that
        // fails one time in thirty is a test nobody trusts.
        java.util.Set<String> drawn = new java.util.HashSet<>();
        for (int seed = 0; seed < 12; seed++) {
            drawn.add(pool.randomClubName("SRB", new Random(seed)).orElseThrow());
        }
        assertTrue(drawn.size() > 1, "the pool must not hand out the same club every time");
    }

    @Test
    @DisplayName("a country with no pool draws nothing, so the caller keeps the old names")
    void anUnpooledCountryDrawsNothing() {
        assertFalse(pool.randomClubName("ZZZ", new Random(3)).isPresent(),
                "no pool means no name from the pool");
        assertFalse(pool.randomCity("ZZZ", new Random(3)).isPresent(), "no pool means no city");
        assertFalse(pool.randomFirstName("ZZZ", new Random(3)).isPresent(), "no pool means no first name");
        assertFalse(pool.randomLastName("ZZZ", new Random(3)).isPresent(), "no pool means no last name");
    }
}
