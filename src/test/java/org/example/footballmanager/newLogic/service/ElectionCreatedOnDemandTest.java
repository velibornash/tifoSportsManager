package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.NationalTeamElection;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.NationalTeamElectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An election is created on demand, not reported as missing ({@code P0-ELEC-3}).
 *
 * <p>The owner reported the panel always reading "Registration closed", and the database explained it:
 * after a Reset there are **zero** rows, and {@code describeElection} returned
 * {@code exists: false, stage: NONE} with no {@code acceptingCandidates} — so the button's
 * {@code !!election.acceptingCandidates} was false forever, and the only thing the panel could ever say
 * was "no election running". A reset was a dead end.
 */
class ElectionCreatedOnDemandTest extends BaseTest {

    @Autowired
    NationalTeamElectionService elections;

    @Autowired
    CountryRepository countries;

    @Autowired
    NationalTeamElectionRepository ledger;

    @Test
    @Transactional
    @DisplayName("asking for a country with no election row creates one, and registration is open")
    void askingCreatesOneAndOpensRegistration() {
        Country country = new Country();
        country.setName("On demand " + UUID.randomUUID());
        country.setIsoCode("O" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Map<String, Object> described = elections.describeElection(country, NationalTeamLevel.SENIOR, 1, null, false);

        assertEquals(Boolean.TRUE, described.get("exists"),
                "asking is not the same as nothing: a reset left zero rows and the panel could only ever "
                        + "say no election running");
        assertEquals(1, ledger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.SENIOR, 1).stream().count(),
                "exactly one row, not a second from the call below");
        assertEquals(true, described.get("acceptingCandidates"),
                "and registration is open, so the button does not read 'Registration closed'");

        // A second call is a lookup, not a duplicate.
        elections.describeElection(country, NationalTeamLevel.SENIOR, 1, null, false);
        assertEquals(1, ledger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.SENIOR, 1).stream().count(),
                "ensureElection is idempotent");
    }

    @Test
    @Transactional
    @DisplayName("asking for the senior side does not create the U-21 side")
    void levelsStaySeparate() {
        Country country = new Country();
        country.setName("Levels " + UUID.randomUUID());
        country.setIsoCode("L" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        elections.describeElection(country, NationalTeamLevel.SENIOR, 1, null, false);

        assertTrue(ledger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.U21, 1).isEmpty(),
                "asking for the senior election must not create a U-21 one");
    }

    @Test
    @Transactional
    @DisplayName("registration opens in the previous season's week 12, not the day before week 1")
    void registrationOpensInPreviousSeasonWeek12() {
        Country country = new Country();
        country.setName("Week12 " + UUID.randomUUID());
        country.setIsoCode("W" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        java.time.Instant weekOne = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        elections.ensureElection(country, NationalTeamLevel.SENIOR, 1, weekOne);

        NationalTeamElection election = ledger.findByCountryIdAndLevelAndSeasonYear(
                country.getId(), NationalTeamLevel.SENIOR, 1).stream().findFirst().orElseThrow();

        // One seven-day week before week 1, not one day: with seasons of twelve weeks, week 12 of the
        // previous season and week 1 of this one are back to back, and the owner's rule names week 12.
        assertEquals(weekOne.minus(java.time.Duration.ofDays(7)), election.getRegistrationOpensAt(),
                "registration must open one whole week before week 1");
    }
}
