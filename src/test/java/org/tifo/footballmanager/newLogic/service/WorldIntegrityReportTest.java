package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.UniqueIsoCodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world's substance is counted by the queries, not by the panel's own arithmetic.
 *
 * <p><b>Why this is a separate test from {@link WorldStateTest}.</b> The verdict is a value and can
 * be tested in a line. The counting cannot: it is three JPQL queries, and a query that names a
 * column that does not exist throws when it runs — which is a production failure, not a test
 * failure. So the queries are run against a real database, here, with a country of this test's own
 * so the numbers are exact.
 *
 * <p><b>Why the assertions are about one country and not about the world.</b> The test database is
 * shared by every test in the suite and it holds whatever the last one left behind, so a count of
 * "the clubs in the world" is not a number anybody can assert. A country created here is this
 * test's own, and its counts are.
 */
@SpringBootTest
@Transactional
class WorldIntegrityReportTest {

    @Autowired CountryRepository countries;
    @Autowired TeamRepository teams;
    @Autowired PlayerRepository players;
    @Autowired CompetitionRepository competitions;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired WorldIntegrityService integrity;

    @Test
    @DisplayName("the counts name the world's substance, and they count it right")
    void theCountsAreRight() {
        Country country = myCountry();
        Team club = myClub(country);
        myPlayer(club);
        Competition competition = myCompetition(country);
        myFixture(competition, club);

        assertEquals(1, teams.countClubTeamsForCountry(country.getId()),
                "one club in this country, counted");
        assertEquals(1, players.countClubPlayersForCountry(country.getId()),
                "one player on it, counted");
        assertEquals(1, fixtures.countForCountry(country.getId()),
                "one fixture of its competition, counted");
    }

    @Test
    @DisplayName("the report carries the counts and the button that answers them")
    void theReportCarriesTheSubstance() {
        Country country = myCountry();
        Team club = myClub(country);
        myPlayer(club);
        Competition competition = myCompetition(country);
        myFixture(competition, club);

        Map<String, Object> report = integrity.report();

        for (String key : new String[]{"activeCountries", "activeClubs", "activePlayers",
                "activeFixtures", "sidesWithoutSquad", "healthy", "nextStep"}) {
            assertTrue(report.containsKey(key), "the report carries '" + key + "'");
        }

        // This country is ACTIVE, so the report's counts are the sum over the played countries and
        // must therefore include this one's. What the rest of the suite left behind is unknown and
        // deliberately not asserted on.
        long reportedClubs = ((Number) report.get("activeClubs")).longValue();
        long reportedPlayers = ((Number) report.get("activePlayers")).longValue();
        long reportedFixtures = ((Number) report.get("activeFixtures")).longValue();

        assertTrue(reportedClubs >= 1, "the report counted this country's club, and more if the "
                + "database held other played countries");
        assertTrue(reportedPlayers >= 1, "and its player");
        assertTrue(reportedFixtures >= 1, "and its fixture");
        assertNotNull(report.get("activeCountries"), "and it says which countries are played");

        // The button is one of three, and never a fourth.
        Object nextStep = report.get("nextStep");
        assertTrue(nextStep == null || "initialize".equals(nextStep) || "repair".equals(nextStep),
                "the button is Initialize DB, Repair world, or nothing — it was '" + nextStep + "'");
    }

    // ── a world of this test's own ────────────────────────────────────────────────────────────────

    private Country myCountry() {
        long stamp = System.nanoTime();
        Country country = new Country();
        // **Not a catalogue ISO code.** The catalogue owns those, and a row that carries one is
        // renamed or deleted by the next seeding pass.
        country.setIsoCode(UniqueIsoCodes.next());
        country.setName("Integrity Probe " + stamp);
        country.setState(CountryState.ACTIVE);
        return countries.save(country);
    }

    private Team myClub(Country country) {
        Team club = new Team();
        club.setName("Integrity Probe Club " + System.nanoTime());
        club.setCountry(country);
        return teams.save(club);
    }

    private void myPlayer(Team club) {
        Player player = new Player();
        player.setName("Probe " + System.nanoTime());
        player.setTeam(club);
        players.save(player);
    }

    private Competition myCompetition(Country country) {
        Competition competition = new Competition();
        competition.setName("Integrity Probe League " + System.nanoTime());
        competition.setType(CompetitionType.LEAGUE);
        competition.setCountry(country);
        return competitions.save(competition);
    }

    private void myFixture(Competition competition, Team club) {
        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(club);
        fixture.setAwayTeam(club);
        fixture.setSeasonYear(1);
        fixture.setRoundNumber(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixtures.save(fixture);
    }
}
