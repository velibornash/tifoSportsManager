package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.TrainingAssignment;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.repository.TrainingAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T-MATCH-8, the half that is about this repository.
 *
 * <p>The owner's rule: *"ako je igrač na trening strani stavljen da trenira u npr MID slotu ... u formulu
 * za računanje treninga mu ulaze SAMO minuti provedeni na MID slotu ali on apsolutno može da igra na bilo
 * kom slotu u meču."* — only the minutes spent in the training slot count, and he may still play anywhere.
 *
 * <p>Before this, a player set to train MID was credited with 90 minutes at full-back, because
 * {@code MatchPlayerStats} recorded no slot at all: the simulation carried the slot and dropped it on the
 * floor. There was nothing to filter on, so nothing was filtered.
 */
class TrainingCountsOnlyItsOwnSlotTest extends BaseTest {

    private static final int SEASON = 1;
    private static final int WEEK = 3;

    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchRepository matches;
    @Autowired private MatchPlayerStatsRepository stats;
    @Autowired private CountryRepository countries;
    @Autowired private TrainingAssignmentRepository trainingAssignments;
    @Autowired private TrainingPercentService training;

    @Test
    @Transactional
    @DisplayName("a player who trains MID gets nothing for 90 minutes at full-back")
    void minutesInTheWrongSlotDoNotCount() {
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        trains(midfielder, Position.MID, false);
        played(midfielder, Position.DEF, 90);

        assertEquals(0, training.minutesPlayed(midfielder.getId(), midfielder.getTeam().getId(), SEASON, WEEK),
                "a full-back's 90 minutes were credited to a midfielder's MID training, which is the "
                        + "whole of this task");
    }

    @Test
    @Transactional
    @DisplayName("the same player, 90 minutes at MID, gets all 90 - the control")
    void minutesInTheOwnSlotCount() {
        // Without this the test above would also pass if the service returned zero for everybody.
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        trains(midfielder, Position.MID, false);
        played(midfielder, Position.MID, 90);

        assertEquals(90, training.minutesPlayed(midfielder.getId(), midfielder.getTeam().getId(), SEASON, WEEK),
                "the minutes in his own slot were not counted either, so the filter is not filtering");
    }

    @Test
    @Transactional
    @DisplayName("advanced training takes only its own slot too")
    void advancedTrainingRespectsTheSlot() {
        // The case the owner singled out: "posebno bitno za ove u advanced slotu".
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        trains(midfielder, Position.MID, true);
        played(midfielder, Position.ATT, 70);
        played(midfielder, Position.MID, 20);

        assertEquals(20, training.minutesPlayed(midfielder.getId(), midfielder.getTeam().getId(), SEASON, WEEK),
                "advanced training was credited with minutes played out of position");
    }

    @Test
    @Transactional
    @DisplayName("a player with no training position keeps today's behaviour - every minute counts")
    void noTrainingPositionMeansEveryMinute() {
        // Changing this silently would alter how every existing player develops, on a world of 33,000.
        Team club = aClub();
        Player goalkeeper = aPlayer(club, Position.GK);
        played(goalkeeper, Position.DEF, 45);
        played(goalkeeper, Position.ATT, 45);

        assertEquals(90, training.minutesPlayed(goalkeeper.getId(), goalkeeper.getTeam().getId(), SEASON, WEEK),
                "a player with no training position lost minutes he used to be credited with");
    }

    @Test
    @Transactional
    @DisplayName("a result recorded before the slot column existed still counts")
    void aMatchWithNoRecordedSlotCountsInFull() {
        // Every result already in the database has a null slot. Counting nothing for all of them would
        // stop training everywhere the day this shipped.
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        trains(midfielder, Position.MID, false);
        played(midfielder, null, 90);

        assertEquals(90, training.minutesPlayed(midfielder.getId(), midfielder.getTeam().getId(), SEASON, WEEK),
                "a result with no slot recorded was worth nothing, which would freeze every existing "
                        + "player's development");
    }

    @Test
    @Transactional
    @DisplayName("minutes from two matches in the week add up, and only the right ones")
    void twoMatchesAccumulate() {
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        trains(midfielder, Position.MID, false);
        played(midfielder, Position.MID, 60);
        played(midfielder, Position.DEF, 30);
        played(midfielder, Position.MID, 25);

        assertEquals(85, training.minutesPlayed(midfielder.getId(), midfielder.getTeam().getId(), SEASON, WEEK),
                "the wrong-slot minutes leaked in, or the right-slot ones were lost");
    }

    @Test
    @Transactional
    @DisplayName("the training position is per player, not per club")
    void trainingPositionsArePerPlayer() {
        // The squad page sets one slot per player, so a filter keyed on the club would be wrong for
        // every club that does not train everybody the same way.
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        Player defender = aPlayer(club, Position.DEF);
        trains(midfielder, Position.MID, false);
        trains(defender, Position.DEF, false);

        played(midfielder, Position.DEF, 90);
        played(defender, Position.MID, 90);

        assertEquals(0, training.minutesPlayed(midfielder.getId(), midfielder.getTeam().getId(), SEASON, WEEK), "the midfielder was credited");
        assertEquals(0, training.minutesPlayed(defender.getId(), defender.getTeam().getId(), SEASON, WEEK), "the defender was credited");
    }

    @Test
    @Transactional
    @DisplayName("a training position never stops a manager fielding the player")
    void trainingPositionDoesNotConstrainTheLineup() {
        // The other half of the owner's sentence, and the half that is easy to break by accident while
        // fixing the first. It is about the squad page, which must offer the player in every slot
        // whatever his training says.
        Team club = aClub();
        Player midfielder = aPlayer(club, Position.MID);
        trains(midfielder, Position.MID, false);

        assertNotNull(midfielder.getPositionEnum(),
                "the player's own position was rewritten by his training assignment");
        // No service method anywhere may be handed the training position to filter a selection by. This
        // asserts the shape of the rule rather than a screen: the training position is only ever read by
        // TrainingPercentService, which counts minutes and never selects anybody.
        String selectors = java.util.Arrays.stream(
                        org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository.class
                                .getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .reduce("", (a, b) -> a + " " + b);
        assertEquals(true, !selectors.contains("findSelectableByTrainingPosition"),
                "a query was added to select players by training position, which would make training a "
                        + "constraint on the manager's choice");
    }

    // --- builders ---------------------------------------------------------

    private Team aClub() {
        Country country = new Country();
        country.setName("ZZ T8 country " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Team club = new Team();
        club.setName("ZZ T8 club " + UUID.randomUUID());
        club.setCountry(country);
        club.setType(CompetitionTeamType.CLUB);
        return teams.save(club);
    }

    private Player aPlayer(Team club, Position position) {
        Player player = new Player();
        player.setName("ZZ T8 player " + UUID.randomUUID());
        player.setTeam(club);
        player.setPosition(position);
        return players.save(player);
    }

    private void trains(Player player, Position slot, boolean advanced) {
        TrainingAssignment assignment = new TrainingAssignment();
        assignment.setPlayerId(player.getId());
        assignment.setTrainingPosition(slot);
        assignment.setAdvanced(advanced);
        trainingAssignments.save(assignment);
    }

    /** One match for the club this week, with the given player in the given slot for the given minutes. */
    private void played(Player player, Position slot, int minutes) {
        Match match = matches.findAll().stream()
                .filter(m -> m.getSeasonYear() != null && m.getSeasonYear() == SEASON)
                .filter(m -> m.getWeekNumber() != null && m.getWeekNumber() == WEEK)
                .findFirst()
                .orElseGet(() -> {
                    Match fresh = new Match();
                    fresh.setHomeTeam(player.getTeam());
                    fresh.setAwayTeam(player.getTeam());
                    fresh.setSeasonYear(SEASON);
                    fresh.setWeekNumber(WEEK);
                    return matches.save(fresh);
                });

        MatchPlayerStats line = new MatchPlayerStats();
        line.setMatch(match);
        line.setPlayer(player);
        line.setMinutesPlayed(minutes);
        line.setPositionPlayed(slot);
        stats.save(line);
    }
}
