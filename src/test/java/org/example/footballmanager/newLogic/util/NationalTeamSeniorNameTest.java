package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
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

/**
 * A senior side is called after its country and nothing else; the U-21 side keeps its suffix
 * (owner, 2026-10-06).
 *
 * <p>Both halves matter. "Germany National Team" repeated what the screen already says, and if the
 * U-21 side lost its suffix as well the world would have two German teams with the same name.
 */
class NationalTeamSeniorNameTest extends BaseTest {

    @Autowired private NationalTeamSeeder seeder;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;

    @Test
    @Transactional
    @DisplayName("a new senior side is named after the country, and the U-21 side keeps 'U-21'")
    void seniorIsTheCountryNameAndU21KeepsItsSuffix() {
        Country country = aCountry();

        seeder.seedIfMissing(List.of(country));

        Country saved = countries.findById(country.getId()).orElseThrow();
        assertEquals(country.getName(), saved.getSeniorNationalTeam().getName(),
                "a senior side is the country name, with nothing appended");
        assertEquals(country.getName() + " U-21", saved.getU21NationalTeam().getName(),
                "the U-21 side keeps its suffix, or the two German teams share a name");
    }

    @Test
    @Transactional
    @DisplayName("a side still carrying the old suffix is renamed, so a re-seed brings the world forward")
    void theOldSuffixIsDroppedOnReseed() {
        Country country = aCountry();
        Team senior = aSide(country, country.getName() + " National Team");
        country.setSeniorNationalTeam(senior);

        seeder.seedIfMissing(List.of(country));

        assertEquals(country.getName(),
                countries.findById(country.getId()).orElseThrow().getSeniorNationalTeam().getName(),
                "'Germany National Team' becomes 'Germany'");
    }

    @Test
    @Transactional
    @DisplayName("a side somebody renamed by hand is left alone")
    void aHandRenamedSideIsNotTouched() {
        Country country = aCountry();
        Team senior = aSide(country, "Die Mannschaft");
        country.setSeniorNationalTeam(senior);

        seeder.seedIfMissing(List.of(country));

        assertEquals("Die Mannschaft",
                countries.findById(country.getId()).orElseThrow().getSeniorNationalTeam().getName(),
                "only the suffix this codebase used is stripped");
    }

    @Test
    @Transactional
    @DisplayName("a world that predates the rename is fixed by the rename alone, with no re-seed")
    void theRenameIsReachableWithoutReseeding() {
        Country country = aCountry();
        Team stale = aSide(country, country.getName() + " National Team");
        country.setSeniorNationalTeam(stale);
        countries.save(country);

        int renamed = seeder.renameSeniorSides(List.of(country));

        assertEquals(1, renamed, "the repair reaches the rename without anybody re-seeding first");
        assertEquals(country.getName(),
                countries.findById(country.getId()).orElseThrow().getSeniorNationalTeam().getName());
        assertEquals(0, seeder.renameSeniorSides(List.of(country)),
                "and a second call changes nothing, so the button is safe to press twice");
    }

    // ---------- world ----------

    private Country aCountry() {
        Country country = new Country();
        country.setName("ZZ " + UUID.randomUUID().toString().substring(0, 8));
        country.setIsoCode("Z" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setReputation(1000);
        country.setYouthRating(1000);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private Team aSide(Country country, String name) {
        Team team = new Team();
        team.setName(name);
        team.setCountry(country);
        team.setType(CompetitionTeamType.NATIONAL_TEAM);
        return teams.save(team);
    }
}