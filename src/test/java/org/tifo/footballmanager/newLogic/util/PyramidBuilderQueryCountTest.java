package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.UniqueIsoCodes;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Seeding a country asks the database about club names once, not once per club (owner, 2026-10-07).
 *
 * <p>The owner reported the symptom precisely: *"kako ide dalje kroz drzave tako ide sve sporije"* — the
 * longer it went, the slower each country got, and writing one league took almost ten seconds. **That
 * shape is the diagnosis.** Something in the per-club path costs more the more clubs already exist, and
 * that is a scan, not an insert.
 *
 * <p>Measured against a 14,880-club table, the query the builder used to issue per club:
 *
 * <pre>
 * LOWER(name) = LOWER(?)   10.0 ms  ->  149 s for 14,880 lookups
 * </pre>
 *
 * <p>A functional index cannot serve that predicate, so the fix is not a faster query but **one query per
 * country** and a membership test in memory. 14,880 round trips become 48.
 *
 * <p><b>This asserts the query count, not the result.</b> Every behavioural test for this builder passed
 * while it took nearly two and a half minutes, because correctness and cost are different properties and
 * only one of them was being checked.
 */
class PyramidBuilderQueryCountTest extends BaseTest {

    @SpyBean
    private org.tifo.footballmanager.newLogic.repository.TeamRepository teams;

    @Autowired
    private PyramidBuilder pyramids;

    @Autowired
    private CountryRepository countries;

    @Autowired
    private CompetitionRepository competitions;

    @Test
    @Transactional
    @DisplayName("one country's club names are read once, whatever the size of its pyramid")
    void clubNamesAreReadOncePerCountry() {
        Country country = aSimulatedCountry();

        pyramids.buildStatic(country, 1);

        verify(teams, times(1)).findNamesForCountry(country.getId());

        long divisions = competitions
                .findByCountryIsoCodeAndType(country.getIsoCode(), CompetitionType.LEAGUE).size();
        assertEquals(31, divisions, "and the pyramid really was built - the query count is not low because "
                + "there was nothing to do");
        assertEquals(310L, teams.count(),
                "310 clubs exist, so the fast path is what built them");
    }

    @Test
    @Transactional
    @DisplayName("seeding a second country does not re-read the first country's clubs one at a time")
    void aSecondCountryKeepsTheQueryCountFlat() {
        pyramids.buildStatic(aSimulatedCountry(), 1);
        clearInvocations(teams);

        pyramids.buildStatic(aSimulatedCountry(), 1);

        verify(teams, times(1)).findNamesForCountry(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the builder never looks a club up by name - the country's clubs are loaded once")
    void noPerClubLookupSurvives() throws java.io.IOException {
        String source = code(java.nio.file.Files.readString(
                org.tifo.footballmanager.ProductionSource.file(PyramidBuilder.class)));

        assertEquals(0, count(source, "findAllByNameIgnoreCase"),
                "the builder asked LOWER(name) = LOWER(?) once per club, which is a full scan of the "
                        + "world's clubs per club. The country's clubs are loaded once into ExistingClubs");
        assertEquals(1, count(source, "findByCountryId("),
                "the re-seed path loads a country's clubs with one query. A second call site means a "
                        + "per-club or per-division load is back");
    }

    /** The file without its comment lines - the javadoc explains the old query, it does not issue it. */
    private static String code(String source) {
        StringBuilder kept = new StringBuilder();
        for (String line : source.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("/*") || stripped.startsWith("//")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static int count(String haystack, String needle) {
        int seen = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            seen++;
        }
        return seen;
    }

    private Country aSimulatedCountry() {
        Country country = new Country();
        country.setName("Perf " + UUID.randomUUID().toString().substring(0, 8));
        country.setIsoCode(UniqueIsoCodes.next());
        country.setReputation(1500);
        country.setYouthRating(1500);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }
}