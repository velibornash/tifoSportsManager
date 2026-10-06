package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Initialize DB must survive a world that already holds national-team competitions (owner,
 * 2026-10-07).
 *
 * <p><b>This was a crash, reported with a stack trace.</b> Initialize DB walked every competition and
 * asked {@code c.getCountry().getIsoCode()} to find the Serbian leagues. The four national-team
 * competitions have <b>no country</b> — an international tournament is not any one nation's — so the
 * walk threw an NPE and the admin panel reported <i>"Database job 'initialize' failed"</i> halfway
 * through building a pyramid.
 *
 * <p>The failure is order-dependent, which is why it survived so long: it needs an NT competition to
 * already exist. On a genuinely empty database it never fires, and the reset button clears the evidence
 * before the next Initialize can trip over it.
 *
 * <p>The test builds the world state that triggers it and calls the same method the button calls.
 */
class DatabaseInitializerNationalCompetitionsTest extends BaseTest {

    @Autowired
    private DatabaseInitializer initializer;

    @Autowired
    private CompetitionRepository competitions;

    @Autowired
    private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("Initialize DB completes with the four national-team competitions in place")
    void initializeSurvivesNationalTeamCompetitions() {
        Country serbia = countries.save(aCountry("SRB", "Serbia", CountryState.ACTIVE));

        // The exact rows that broke it: international tournaments, no country behind them.
        for (NationalTeamLevel level : NationalTeamLevel.values()) {
            for (NationalStage stage : new NationalStage[]{NationalStage.QUALIFYING, NationalStage.WORLD_CUP}) {
                Competition nt = new Competition();
                nt.setName(NationalTeamCompetitions.nameFor(level, stage));
                nt.setType(CompetitionType.TOURNAMENT);
                nt.setScope(CompetitionScope.INTERNATIONAL);
                nt.setTeamType(CompetitionTeamType.NATIONAL_TEAM);
                nt.setNationalLevel(level);
                nt.setNationalStage(stage);
                nt.setCountry(null);
                competitions.save(nt);
            }
        }
        assertEquals(4, competitions.findAll().stream().filter(c -> c.getCountry() == null).count(),
                "four country-less competitions, which is the world state that used to throw");

        // The call the admin button makes. Before the fix this threw
        // "Cannot invoke Country.getIsoCode() because Competition.getCountry() is null".
        initializer.buildSerbianStructure();

        assertTrue(competitions.findAll().stream()
                        .anyMatch(c -> c.getCountry() != null && "SRB".equals(c.getCountry().getIsoCode())),
                "the pyramid was built: at least one Serbian competition exists");
        assertEquals(4, competitions.findAll().stream().filter(c -> c.getCountry() == null).count(),
                "and the national-team competitions were left alone, not deleted or emptied");
        assertTrue(serbia.getId() != null);
    }

    private Country aCountry(String iso, String name, CountryState state) {
        Country country = new Country();
        country.setIsoCode(iso);
        country.setName(name);
        country.setReputation(1500);
        country.setYouthRating(1500);
        country.setState(state);
        return country;
    }
}