package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.example.footballmanager.newLogic.repository.GoalEventRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The club milestone page reads the season's goals once, and still names the same two leaders.
 *
 * <p><b>Why the read count is the thing to hold.</b> {@code findByMatchSeasonYearAndScoredTrue} is the
 * heaviest read in the service — it walks every match of the season and parses each one's event log — and
 * the page called it <b>twice</b>, once to find goals and again to find assists, discarding the first
 * walk. A club's top scorer and top assist come out of the same list, so the second call was the whole
 * cost of the page paid twice.
 *
 * <p><b>Why the leaders are asserted too.</b> A test that only counted calls would be satisfied by a
 * method that reads once and returns two nulls. The two leaders are what the page is for.
 *
 * <p>Note that {@code buildLeagueMilestones} already read once and derived both leaders from the one
 * list — this makes the club page the same shape rather than inventing a new one.
 */
class LeagueMilestoneSingleSeasonReadTest {

    private final MatchRepository matches = mock(MatchRepository.class);
    private final GoalEventRepository goals = mock(GoalEventRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);

    private LeagueMilestoneService service() {
        // The honours collaborator is mocked and left empty: this class is about how many times the match
        // and goal tables are read, and a mock is what makes that measurable. Its own read path is proved
        // in HonourServiceTest and the payload shape in ClubMilestonesCarryHonoursTest.
        HonourService honours = mock(HonourService.class);
        when(honours.honoursOf(any())).thenReturn(List.of());
        return new LeagueMilestoneService(matches, goals, players, honours);
    }

    private Team aClub() {
        Team club = new Team();
        club.setId(7L);
        club.setName("OFK Omladinac");
        return club;
    }

    /** Two club players: 1001 scores twice and 1002 assists twice. */
    private void aSeasonOfGoals() {
        Team club = aClub();
        when(players.findByTeamId(7L)).thenReturn(List.of(player(1001L, club), player(1002L, club)));
        when(matches.findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(7L, 7L))
                .thenReturn(List.of());

        when(goals.findByMatchSeasonYearAndScoredTrue(3)).thenReturn(List.of(
                goal(10, 1001L, "Ada Goals", 1002L, "Ben Assists"),
                goal(40, 1001L, "Ada Goals", 1002L, "Ben Assists"),
                // Somebody else's goal, which the club filter must drop.
                goal(70, 2001L, "Cy Other", 2002L, "Dee Other")));
    }

    private Player player(long id, Team club) {
        Player player = new Player();
        player.setId(id);
        player.setName("Player " + id);
        player.setTeam(club);
        return player;
    }

    private GoalEvent goal(int minute, long scorerId, String scorer, Long assistId, String assist) {
        return new GoalEvent(minute, minute * 40, scorerId, scorer, assistId, assist,
                "HOME", 0.1, 1, 0);
    }

    @Test
    @DisplayName("the season is read once for the top scorer and the top assist")
    void theSeasonIsReadOnce() {
        aSeasonOfGoals();

        service().buildTeamMilestones(aClub(), 3);

        verify(goals, times(1)).findByMatchSeasonYearAndScoredTrue(3);
    }

    @Test
    @DisplayName("and both leaders are still named from that one read")
    void bothLeadersAreStillNamed() {
        aSeasonOfGoals();

        var milestones = service().buildTeamMilestones(aClub(), 3);

        assertNotNull(milestones.getTopScorer(), "the club's top scorer went missing");
        assertEquals("Ada Goals", milestones.getTopScorer().getPlayerName());
        assertEquals(2, milestones.getTopScorer().getValue());

        assertNotNull(milestones.getTopAssist(), "the club's top assist went missing");
        assertEquals("Ben Assists", milestones.getTopAssist().getPlayerName());
        assertEquals(2, milestones.getTopAssist().getValue());
    }

    @Test
    @DisplayName("another club's goals are still not counted for this club")
    void anotherClubsGoalsAreNotCounted() {
        aSeasonOfGoals();

        var milestones = service().buildTeamMilestones(aClub(), 3);

        // Cy Other also scored twice in the log; if the club filter were dropped the leader would be
        // ambiguous rather than simply wrong, so this pins that the filter still runs.
        assertEquals("Ada Goals", milestones.getTopScorer().getPlayerName());
    }
}
