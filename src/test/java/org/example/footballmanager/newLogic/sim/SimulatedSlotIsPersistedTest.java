package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The one link T-MATCH-8 was missing, end to end: the slot the simulation knows reaches the database.
 *
 * <p>{@code PlayerOutcome} has carried a {@code role} all along and two other readers use it, and
 * {@code SimMatchService} dropped it on the floor. That single omission is why training could not tell a
 * minute at full-back from a minute at centre midfield.
 *
 * <p>The filtering half is in {@code TrainingCountsOnlyItsOwnSlotTest}, against stored rows. This is the
 * half that writes them.
 */
class SimulatedSlotIsPersistedTest extends BaseTest {

    @Autowired private SimMatchService sim;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchPlayerStatsRepository stats;

    @Test
    @Transactional
    @DisplayName("the slot the engine reports is the slot stored on the match stats row")
    void theEngineRoleReachesTheDatabase() {
        Team home = aClub("Home");
        Team away = aClub("Away");
        Player midfielder = aPlayer(home, Position.MID);

        sim.persist(aFixture(home, away), outcome(home, away, midfielder, "MID"), -1L);

        var rows = stats.findByPlayerId(midfielder.getId());
        assertEquals(1, rows.size(), "no stat row was written for the player");
        assertEquals(Position.MID, rows.get(0).getPositionPlayed(),
                "the engine said MID and the row does not say so, which is the omission this closes");
    }

    @Test
    @Transactional
    @DisplayName("a role we do not recognise is stored as no slot, not as a guess")
    void anUnknownRoleIsNotGuessedAt() {
        Team home = aClub("Home");
        Team away = aClub("Away");
        Player player = aPlayer(home, Position.ATT);

        sim.persist(aFixture(home, away), outcome(home, away, player, "SOMETHING_NEW"), -1L);

        assertNull(stats.findByPlayerId(player.getId()).get(0).getPositionPlayed(),
                "an unknown role was turned into a position, which would move the player's minutes into "
                        + "whichever slot we guessed");
    }

    @Test
    @Transactional
    @DisplayName("a lowercase role still lands, because the engine is not careful about case")
    void aLowercaseRoleStillLands() {
        Team home = aClub("Home");
        Team away = aClub("Away");
        Player defender = aPlayer(home, Position.DEF);

        sim.persist(aFixture(home, away), outcome(home, away, defender, "def"), -1L);

        assertEquals(Position.DEF, stats.findByPlayerId(defender.getId()).get(0).getPositionPlayed(),
                "a role in lower case was dropped instead of read");
    }

    // --- builders ---------------------------------------------------------

    private Team aClub(String label) {
        Country country = new Country();
        country.setName("ZZ Slot " + label + " " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team club = new Team();
        club.setName("ZZ Slot club " + label + " " + UUID.randomUUID());
        club.setCountry(country);
        club.setType(CompetitionTeamType.CLUB);
        return teams.save(club);
    }

    private Player aPlayer(Team club, Position position) {
        Player player = new Player();
        player.setName("ZZ Slot player " + UUID.randomUUID());
        player.setTeam(club);
        player.setPosition(position);
        return players.save(player);
    }

    private MatchFixture aFixture(Team home, Team away) {
        Competition competition = new Competition();
        competition.setName("ZZ Slot competition " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        return fixtures.save(fixture);
    }

    private ProposalMatchOutcome outcome(Team home, Team away, Player player, String role) {
        // Four names, then goals..minutesPlayed, then a rating. The counts come from the compiler's own
        // "required:" line rather than from counting the record by hand.
        ProposalMatchOutcome.PlayerOutcome playerOutcome = new ProposalMatchOutcome.PlayerOutcome(
                String.valueOf(player.getId()), player.getName(), home.getName(), role,
                0, 0, 0, 0, 10, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 90, 6.5);
        // A team name, then goals..penalties, then possession and the average rating.
        ProposalMatchOutcome.TeamOutcome teamStats = new ProposalMatchOutcome.TeamOutcome(
                home.getName(), 0, 10, 5, 300, 280, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                45.0, 6.0);
        return new ProposalMatchOutcome(
                home.getName(), away.getName(), 0, 0, 5400L, 90, "4-4-2", "4-4-2",
                50.0, 50.0, 1.2, 1.1,
                teamStats, teamStats, List.of(playerOutcome), List.of(), null, null, null);
    }
}

