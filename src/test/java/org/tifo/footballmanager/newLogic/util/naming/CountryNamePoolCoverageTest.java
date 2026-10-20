package org.tifo.footballmanager.newLogic.util.naming;

import org.tifo.footballmanager.newLogic.model.CountryCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name pools, as a whole (redesign.md R-23, owner 2026-10-20).
 *
 * <p>The owner asked for the pools of every country: "za svaku drzavu od ovih 47 istrazis najcesca
 * imena timova i imena i prezimena i gradova i dodas defaulte koji se mogu menjati i koje seederi
 * koriste za AI timove/igrace jer ovi genericki su objektivno ruzni" - for each of the forty-seven
 * countries, research the most common club names, first and last names and towns, and add them as
 * defaults that can be changed and that the seeders use for the AI teams and players, because the
 * generic ones are objectively ugly.
 *
 * <p>These are the checks that keep that true. A pool that is empty, or that names the same club
 * twice, or that a country never got, is a pool the seeders cannot use.
 */
class CountryNamePoolCoverageTest {

    private final CountryNamePool pool = new CountryNamePool();

    @Test
    @DisplayName("every country in the catalogue has a pool")
    void everyCountryHasAPool() {
        List<String> missing = new ArrayList<>();
        for (CountryCatalog country : CountryCatalog.values()) {
            if (!pool.forCountry(country.code()).isPresent()) {
                missing.add(country.code());
            }
        }
        assertTrue(missing.isEmpty(),
                "these countries have no name pool, so their clubs are still called after their "
                        + "country code: " + missing);
    }

    @Test
    @DisplayName("every pool has club names, cities and people's names")
    void everyPoolIsComplete() {
        for (CountryCatalog country : CountryCatalog.values()) {
            CountryNamePool.Pool p = pool.forCountry(country.code()).orElseThrow();
            assertTrue(p.hasClubs(), country.code() + " has no club names");
            assertTrue(p.hasCities(), country.code() + " has no cities");
            assertTrue(p.hasFirstNames(), country.code() + " has no first names");
            assertTrue(p.hasLastNames(), country.code() + " has no last names");
        }
    }

    @Test
    @DisplayName("no pool names the same thing twice")
    void noPoolHasDuplicates() {
        for (CountryCatalog country : CountryCatalog.values()) {
            CountryNamePool.Pool p = pool.forCountry(country.code()).orElseThrow();
            assertNoDuplicates(country.code() + " clubs", p.clubs());
            assertNoDuplicates(country.code() + " cities", p.cities());
            assertNoDuplicates(country.code() + " first names", p.firstNames());
            assertNoDuplicates(country.code() + " last names", p.lastNames());
        }
    }

    @Test
    @DisplayName("no name is empty and none is longer than the column allows")
    void noNameIsEmptyOrTooLong() {
        for (CountryCatalog country : CountryCatalog.values()) {
            CountryNamePool.Pool p = pool.forCountry(country.code()).orElseThrow();
            for (String name : p.clubs()) {
                assertFalse(name.isBlank(), country.code() + " has an empty club name");
                assertTrue(name.length() <= 120,
                        country.code() + " has a club name the column cannot hold: " + name);
            }
            for (String name : p.cities()) {
                assertFalse(name.isBlank(), country.code() + " has an empty city");
            }
            for (String name : p.firstNames()) {
                assertFalse(name.isBlank(), country.code() + " has an empty first name");
            }
            for (String name : p.lastNames()) {
                assertFalse(name.isBlank(), country.code() + " has an empty last name");
            }
        }
    }

    @Test
    @DisplayName("the TIFO nation draws from every country")
    void theTifoNationDrawsFromEveryCountry() {
        CountryNamePool.Pool tifo = pool.forCountry("OTH").orElseThrow();
        for (CountryCatalog country : CountryCatalog.values()) {
            if (country.code().equals("OTH")) continue;
            CountryNamePool.Pool one = pool.forCountry(country.code()).orElseThrow();
            for (String club : one.clubs()) {
                assertTrue(tifo.clubs().contains(club),
                        "the TIFO nation does not have " + country.code() + "'s " + club
                                + " - the owner asked for it to be able to use all the countries");
            }
        }
    }

    @Test
    @DisplayName("the pool is bigger than one division, so the first clubs are the famous ones")
    void thePoolCoversMoreThanOneDivision() {
        for (CountryCatalog country : CountryCatalog.values()) {
            CountryNamePool.Pool p = pool.forCountry(country.code()).orElseThrow();
            assertTrue(p.clubs().size() >= 10,
                    country.code() + " has " + p.clubs().size()
                            + " club names - fewer than one division of ten clubs");
        }
    }

    private void assertNoDuplicates(String what, List<String> names) {
        Set<String> seen = new HashSet<>();
        List<String> repeated = new ArrayList<>();
        for (String name : names) {
            if (!seen.add(name.trim().toLowerCase(java.util.Locale.ROOT))) {
                repeated.add(name);
            }
        }
        assertEquals(List.of(), repeated, "these names are in the pool twice: " + repeated);
    }
}
