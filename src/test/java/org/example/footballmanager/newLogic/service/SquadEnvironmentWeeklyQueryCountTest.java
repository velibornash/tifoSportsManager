package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The weekly squad rollover reads every squad in one query, not one per club.
 *
 * <p><b>Why a query count and not a clock.</b> The bug was {@code players.findByTeamId(club.getId())}
 * inside the club loop, so a week cost one query per club — 14,880 of them once the world is 48
 * countries, every week of every season, to read the 300,000 players a single {@code findByTeamIdIn}
 * already had. Any clock would have called the broken version fast on a development-sized world. The
 * number that separates them is how often the database is asked.
 *
 * <p><b>Why a pure mocked test.</b> An earlier guard in this repository used {@code @SpyBean}, skipped on
 * the empty test database and reported green — recorded in {@code kanbanProgress.md}. Mocking the
 * repository <i>interface</i> (never the injected bean, which Spring Data hands over as a JDK proxy
 * Mockito cannot wrap) makes the count exact and the test takes milliseconds.
 *
 * <p><b>Why the work is asserted too.</b> A query count alone is satisfied by a method that reads
 * nothing at all, which is the same hole as a test that asserts a key exists. So the third case checks
 * that every club is still advanced.
 */
class SquadEnvironmentWeeklyQueryCountTest {

    private final TeamRepository teams = mock(TeamRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);

    private SquadEnvironmentService service() {
        // Constructor order is (players, teams, minutes).
        return new SquadEnvironmentService(players, teams, mock(TrainingPercentService.class));
    }

    @Test
    @DisplayName("30 clubs cost one squad query, not thirty")
    void thirtyClubsCostOneSquadQuery() {
        when(teams.findAll()).thenReturn(clubs(30));
        when(players.findByTeamIdIn(anyList())).thenReturn(squadsOf(30, 5));

        service().advanceWeek(1, 2);

        verify(players, times(1)).findByTeamIdIn(anyList());
        verify(players, never()).findByTeamId(anyLong());
    }

    @Test
    @DisplayName("every club is still advanced, so the one query did not come at the expense of the work")
    void everyClubIsStillAdvanced() {
        when(teams.findAll()).thenReturn(clubs(30));
        when(players.findByTeamIdIn(anyList())).thenReturn(squadsOf(30, 5));

        assertEquals(30, service().advanceWeek(1, 2),
                "a club with a squad is advanced whatever the query count says");
    }

    @Test
    @DisplayName("a club with no players needs no query of its own, and is not a failure")
    void aClubWithNoPlayersNeedsNoQueryOfItsOwn() {
        when(teams.findAll()).thenReturn(clubs(3));
        // Only the first club has anybody. The other two must be discovered from the absent map key,
        // not by asking the database whether they are empty.
        when(players.findByTeamIdIn(anyList())).thenReturn(squadsOf(1, 4));

        assertEquals(3, service().advanceWeek(1, 2),
                "an empty squad is a real week with nobody in it: cohesion still moves, and the club "
                        + "is still counted");

        verify(players, times(1)).findByTeamIdIn(anyList());
        verify(players, never()).findByTeamId(anyLong());
    }

    @Test
    @DisplayName("an empty world still costs the one query")
    void anEmptyWorldStillCostsOneQuery() {
        when(teams.findAll()).thenReturn(List.of());
        when(players.findByTeamIdIn(anyList())).thenReturn(List.of());

        assertEquals(0, service().advanceWeek(1, 2));

        verify(players, times(1)).findByTeamIdIn(anyList());
    }

    private List<Team> clubs(int count) {
        List<Team> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Team club = new Team();
            club.setId((long) i);
            club.setName("Club " + i);
            out.add(club);
        }
        return out;
    }

    /**
     * Real {@link Player} objects, built with setters rather than the all-args constructor — which grows
     * by one parameter per field added to the entity, so a positional call in a test breaks the next
     * time somebody adds a column. {@code SquadEnvironment} treats a null familiarity and a null cohesion
     * as its documented defaults, so nothing else has to be set.
     */
    private List<Player> squadsOf(int clubCount, int perClub) {
        List<Player> out = new ArrayList<>();
        long id = 1;
        for (int c = 1; c <= clubCount; c++) {
            Team club = clubs(clubCount).get(c - 1);
            for (int p = 0; p < perClub; p++) {
                Player player = new Player();
                player.setId(id);
                player.setName("Player " + id);
                player.setTeam(club);
                out.add(player);
                id++;
            }
        }
        return out;
    }
}
