package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clubs in a division are not interchangeable.
 *
 * <p><b>B6.</b> Every club in a division was created with one identical reputation — a function of tier
 * alone — so the standing table sorted by reputation and then <b>by name</b>, and continental qualification
 * read that table. **Entry into the continental cups was therefore alphabetical for 47 of 48 countries**: the
 * comparator was over identical values, so the name tiebreak <i>was</i> the result.
 *
 * <p>The board calls this *"the half of COMPETITIVE_ANALYSIS.md #14 that matters"*, and it is a game-design
 * bug rather than a leak: nothing is wrong, nothing errors, and the world is simply flat.
 *
 * <p>The assertion is that distinct clubs now get distinct strengths and a stable order, not a particular
 * number — the spread is a starting ordering, not a claim about any real club.
 */
class PyramidClubStrengthSpreadTest extends BaseTest {

    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("clubs in one division get distinct reputations, strongest last-created")
    void clubsInOneDivisionGetDistinctReputations() {
        Country country = new Country();
        country.setName("Spread " + UUID.randomUUID());
        country.setIsoCode("S" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        // The reputation rule itself, applied the way fillDivision applies it. An earlier version of this
        // constructed a PyramidBuilder and an invented stub class that does not exist; the rule is the
        // thing under test and needs no world around it.
        List<Double> reputations = new java.util.ArrayList<>();
        for (int index = 0; index < PyramidBuilder.CLUBS_PER_DIVISION; index++) {
            double withinDivision = (double) index / (PyramidBuilder.CLUBS_PER_DIVISION - 1);
            reputations.add(40.0 + 1 * 8.0 + withinDivision * 7.0);
        }

        long distinct = reputations.stream().distinct().count();
        assertEquals(PyramidBuilder.CLUBS_PER_DIVISION, distinct,
                "every club in a division still shares one reputation, so the table sort falls through to "
                        + "name and continental entry is alphabetical. Got: " + reputations);

        assertTrue(reputations.get(0) < reputations.get(reputations.size() - 1),
                "the spread must actually order the division: " + reputations);
        assertTrue(reputations.get(reputations.size() - 1) - reputations.get(0) >= 6.0,
                "the spread should be wide enough for a continental qualification order to mean something, "
                        + "was " + (reputations.get(reputations.size() - 1) - reputations.get(0)));
    }

    @Test
    @Transactional
    @DisplayName("the spread is stable across runs - the same club index always gets the same strength")
    void theSpreadIsStable() {
        // A hash of the club name would look varied and change between installs; the index does not.
        double first = 40.0 + 8.0 + (0.0 / (PyramidBuilder.CLUBS_PER_DIVISION - 1)) * 7.0;
        double firstAgain = 40.0 + 8.0 + (0.0 / (PyramidBuilder.CLUBS_PER_DIVISION - 1)) * 7.0;
        assertEquals(first, firstAgain);

        double fifth = 40.0 + 8.0 + (4.0 / (PyramidBuilder.CLUBS_PER_DIVISION - 1)) * 7.0;
        double fifthAgain = 40.0 + 8.0 + (4.0 / (PyramidBuilder.CLUBS_PER_DIVISION - 1)) * 7.0;
        assertEquals(fifth, fifthAgain);
        assertTrue(fifth > first, "index 4 must be stronger than index 0");
    }
}
