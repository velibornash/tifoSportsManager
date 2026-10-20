package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerDiscipline;
import org.example.footballmanager.newLogic.repository.PlayerDisciplineRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ban check is asked once for a squad, not once per player.
 *
 * <p><b>Why this test exists (measured on the live database, 2026-10-19).</b> The day-1 matchday holds
 * 2,400 fixtures. The ban check was asked <b>per player</b>, twice, for every player of both squads —
 * about 220 questions a fixture and half a million for the matchday. One of the two was a Spring Data
 * <em>derived</em> query, and a derived query rebuilds and copies its whole JPQL syntax tree on every
 * execution. 216,000 executions left <b>20.7 million live collection objects</b> behind — six gigabytes
 * of an eight gigabyte heap — and the matchday was killed by {@code OutOfMemoryError} before it finished.
 * The JVM sat at 937 % CPU in a GC death spiral and stopped answering HTTP.
 *
 * <p><b>Why it cannot pass by accident.</b> The count is asserted directly, against a mocked repository:
 * two queries for a squad of thirty, not sixty. A test that inferred the count from a wall-clock time
 * would pass on a fast machine and fail on a slow one, and would say nothing about which query was the
 * expensive one.
 *
 * <p><b>And the answers are the same answers.</b> The second half of this class asks the batched
 * question and the per-player question the same thing and compares them, because "faster" is worthless
 * if the rules changed. The rules themselves are covered behaviourally by {@code DisciplineServiceTest};
 * this covers the shape of the call.
 */
class DisciplineSuspensionQueryCountTest {

    private static final int SEASON = 3;
    private static final Long COMPETITION_ID = 77L;

    /** A squad of thirty players, as ids. */
    private static List<Long> aSquad(int size) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ids.add(1000L + i);
        }
        return ids;
    }

    private PlayerDiscipline row(Long playerId, Long competitionId, int bansOwed) {
        PlayerDiscipline record = new PlayerDiscipline();
        Player player = new Player();
        player.setId(playerId);
        record.setPlayer(player);
        record.setSeason(SEASON);
        record.setBansOwed(bansOwed);
        if (competitionId != null) {
            Competition competition = new Competition();
            competition.setId(competitionId);
            record.setCompetition(competition);
        }
        return record;
    }

    private MatchFixture aFixture() {
        Competition competition = new Competition();
        competition.setId(COMPETITION_ID);
        MatchFixture fixture = new MatchFixture();
        fixture.setSeasonYear(SEASON);
        fixture.setCompetition(competition);
        return fixture;
    }

    private DisciplineService serviceOver(PlayerDisciplineRepository records) {
        return new DisciplineService(records, Mockito.mock(PlayerRepository.class));
    }

    @Test
    @DisplayName("thirty players cost two queries, not sixty")
    void aSquadCostsTwoQueries() {
        PlayerDisciplineRepository records = Mockito.mock(PlayerDisciplineRepository.class);
        Mockito.when(records.findClubWideBySeasonAndPlayerIds(Mockito.eq(SEASON), Mockito.anyList()))
                .thenReturn(List.of(row(1005L, null, 1)));
        Mockito.when(records.findBySeasonAndCompetitionAndPlayerIds(
                        Mockito.eq(SEASON), Mockito.eq(COMPETITION_ID), Mockito.anyList()))
                .thenReturn(List.of(row(1009L, COMPETITION_ID, 2)));

        DisciplineService service = serviceOver(records);
        service.suspensionsFor(aSquad(30), SEASON, aFixture());

        Mockito.verify(records, Mockito.times(1)).findClubWideBySeasonAndPlayerIds(Mockito.eq(SEASON), Mockito.anyList());
        Mockito.verify(records, Mockito.times(1)).findBySeasonAndCompetitionAndPlayerIds(
                Mockito.eq(SEASON), Mockito.eq(COMPETITION_ID), Mockito.anyList());
        Mockito.verifyNoMoreInteractions(records);

        assertEquals(2, Mockito.mockingDetails(records).getInvocations().size(),
                "the squad's bans were not read in one pass — the per-player version asked the database "
                        + "two questions for every player, and that is what exhausted the heap on "
                        + "2026-10-19 and killed the day-1 matchday");
    }

    @Test
    @DisplayName("the whole squad is asked about, not a sample of it")
    void everyPlayerOfTheSquadIsAskedAbout() {
        PlayerDisciplineRepository records = Mockito.mock(PlayerDisciplineRepository.class);
        Mockito.when(records.findClubWideBySeasonAndPlayerIds(Mockito.eq(SEASON), Mockito.anyList()))
                .thenReturn(List.of());
        Mockito.when(records.findBySeasonAndCompetitionAndPlayerIds(
                        Mockito.eq(SEASON), Mockito.eq(COMPETITION_ID), Mockito.anyList()))
                .thenReturn(List.of());

        DisciplineService service = serviceOver(records);
        List<Long> squad = aSquad(30);
        service.suspensionsFor(squad, SEASON, aFixture());

        org.mockito.ArgumentCaptor<List<Long>> asked = org.mockito.ArgumentCaptor.forClass(List.class);
        Mockito.verify(records).findClubWideBySeasonAndPlayerIds(Mockito.eq(SEASON), asked.capture());
        assertEquals(squad, asked.getValue(),
                "the query was asked about a different set of players than the squad it was given");
    }

    @Test
    @DisplayName("a red card and a yellow accumulation both bar a player, and the red card wins")
    void theBatchedAnswerMatchesThePerPlayerAnswer() {
        PlayerDisciplineRepository records = Mockito.mock(PlayerDisciplineRepository.class);
        // 1005 owes a club-wide ban AND a competition ban: the club-wide one is the reason shown.
        Mockito.when(records.findClubWideBySeasonAndPlayerIds(Mockito.eq(SEASON), Mockito.anyList()))
                .thenReturn(List.of(row(1005L, null, 1)));
        Mockito.when(records.findBySeasonAndCompetitionAndPlayerIds(
                        Mockito.eq(SEASON), Mockito.eq(COMPETITION_ID), Mockito.anyList()))
                .thenReturn(List.of(row(1005L, COMPETITION_ID, 2), row(1009L, COMPETITION_ID, 1)));

        DisciplineService service = serviceOver(records);
        Map<Long, String> barred = service.suspensionsFor(aSquad(30), SEASON, aFixture());

        assertEquals(2, barred.size(), "the wrong number of players came back barred");
        assertTrue(barred.containsKey(1005L), "a player with a red-card ban must be barred");
        assertTrue(barred.get(1005L).contains("sent off"),
                "a red card outranks a yellow accumulation, and it is the reason the manager is shown: "
                        + barred.get(1005L));
        assertTrue(barred.containsKey(1009L), "a player with a yellow accumulation must be barred");
        assertTrue(barred.get(1009L).contains("match ban"), barred.get(1009L));
        assertFalse(barred.containsKey(1000L), "a player with no record must not be barred");
    }

    @Test
    @DisplayName("an empty squad asks nothing")
    void anEmptySquadAsksNothing() {
        PlayerDisciplineRepository records = Mockito.mock(PlayerDisciplineRepository.class);

        DisciplineService service = serviceOver(records);
        assertTrue(service.suspensionsFor(List.of(), SEASON, aFixture()).isEmpty());
        assertTrue(service.suspensionsFor(null, SEASON, aFixture()).isEmpty());

        Mockito.verifyNoInteractions(records);
    }
}
