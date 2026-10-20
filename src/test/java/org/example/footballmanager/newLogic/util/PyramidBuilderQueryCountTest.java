package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
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
    private org.example.footballmanager.newLogic.repository.TeamRepository teams;

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

    private Country aSimulatedCountry() {
        Country country = new Country();
        country.setName("Perf " + UUID.randomUUID().toString().substring(0, 8));
        country.setIsoCode("P" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setReputation(1500);
        country.setYouthRating(1500);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }
}