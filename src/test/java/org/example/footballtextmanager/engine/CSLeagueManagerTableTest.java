package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.CSMatchResult;
import org.example.footballtextmanager.model.CSTableEntry;
import org.example.footballtextmanager.model.CSTeam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Straža za pozicije u tabeli.
 *
 * <p>{@code CSMapper.toCSTableEntry} je mapirao {@code entry.getPosition()} direktno, a
 * {@code CSLeagueManager} nikad nije postavljao to polje. Rezultat: frontend je za svih 16
 * klubova prikazivao istu poziciju, a tabela nije bila ni uređena ni korisna.
 */
class CSLeagueManagerTableTest {

    private CSLeagueManager leagueManager;
    private List<CSTeam> teams;

    @BeforeEach
    void setUp() {
        leagueManager = new CSLeagueManager();
        teams = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            teams.add(SquadFixture.team(id, "Team " + id));
        }
    }

    @Test
    @DisplayName("initializeTable popunjava pozicije 1..N bez praznina")
    void initialTableHasContiguousPositions() {
        List<CSTableEntry> table = leagueManager.initializeTable(teams);

        assertEquals(4, table.size());
        for (int i = 0; i < table.size(); i++) {
            assertEquals(i + 1, table.get(i).getPosition(),
                    "Pozicija " + (i + 1) + ". mesta u tabeli je " + table.get(i).getPosition());
        }
    }

    @Test
    @DisplayName("Najbolja ekipa dobija poziciju 1, najgora poslednju")
    void bestTeamGetsPositionOne() {
        List<CSTableEntry> table = leagueManager.initializeTable(teams);

        // Team 4 pobjeđuje Team 1, Team 2 igra nerešeno protiv Team 3.
        leagueManager.updateTable(table, result(4L, 1L, 2, 0));
        leagueManager.updateTable(table, result(2L, 3L, 1, 1));

        assertEquals("Team 4", atPosition(table, 1).getTeamName());
        assertEquals(1, atPosition(table, 1).getPosition());
        assertEquals(4, atPosition(table, 4).getPosition());
    }

    @Test
    @DisplayName("Nerešen rezultat daje isti broj bodova obema ekipama")
    void drawGivesBothTeamsAPoint() {
        List<CSTableEntry> table = leagueManager.initializeTable(teams);

        leagueManager.updateTable(table, result(1L, 2L, 1, 1));

        CSTableEntry home = team(table, 1L);
        CSTableEntry away = team(table, 2L);

        assertEquals(1, home.getPoints());
        assertEquals(1, away.getPoints());
        assertEquals(1, home.getPlayed());
        assertEquals(1, away.getPlayed());
        assertEquals(0, home.getGoalsScored() - home.getGoalsConceded());
        assertEquals(0, away.getGoalsScored() - away.getGoalsConceded());
        assertEquals(1, home.getDraws());
        assertEquals(1, away.getDraws());
    }

    @Test
    @DisplayName("Pozicije se prebrojavaju posle svakog meča, ne samo na kraju kola")
    void positionsAreRenumberedAfterEachMatch() {
        List<CSTableEntry> table = leagueManager.initializeTable(teams);

        leagueManager.updateTable(table, result(3L, 1L, 1, 0));

        // Team 3 ima 3 boda i mora biti prvi, iako je tek sad tu pobedio.
        assertEquals("Team 3", atPosition(table, 1).getTeamName());
        assertEquals(1, team(table, 3L).getPosition());
    }

    @Test
    @DisplayName("Gol razlika razdvaja ekipe sa istim bodovima")
    void goalDifferenceBreaksTheTie() {
        List<CSTableEntry> table = leagueManager.initializeTable(teams);

        // Obe ekipe imaju po 3 boda; Team 1 ima bolju gol razliku.
        leagueManager.updateTable(table, result(1L, 2L, 3, 0));
        leagueManager.updateTable(table, result(3L, 4L, 1, 0));

        assertEquals("Team 1", atPosition(table, 1).getTeamName());
        assertEquals("Team 3", atPosition(table, 2).getTeamName());
    }

    private static CSMatchResult result(long homeId, long awayId, int homeGoals, int awayGoals) {
        return CSMatchResult.builder()
                .homeTeamId(homeId)
                .awayTeamId(awayId)
                .homeTeamName("Team " + homeId)
                .awayTeamName("Team " + awayId)
                .homeGoals(homeGoals)
                .awayGoals(awayGoals)
                .build();
    }

    private static CSTableEntry atPosition(List<CSTableEntry> table, int position) {
        return table.stream()
                .filter(e -> e.getPosition() == position)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nema ekipe na poziciji " + position
                        + ". Stanje tabele: " + table.stream()
                        .map(e -> e.getTeamName() + "@" + e.getPosition()).toList()));
    }

    private static CSTableEntry team(List<CSTableEntry> table, long teamId) {
        return table.stream()
                .filter(e -> e.getTeamId() == teamId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nema tima " + teamId));
    }
}
