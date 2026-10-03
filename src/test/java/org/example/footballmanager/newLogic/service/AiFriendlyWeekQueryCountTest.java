package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.FriendlyRequestRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The weekly AI friendly pass reads the week twice, not once per club per candidate opponent.
 *
 * <p><b>The defect.</b> {@code isBusy}, {@code isInPlayoff} and {@code hasFixtureThatWeek} each walked
 * {@code fixtures.findBySeasonYearAndWeekNumber(season, week)} — the same list three times — and
 * {@code isBusy} also asked for one club's agreed friendlies. They were called per club in the first loop
 * and again per candidate opponent inside a nested loop, so the floor was two queries for every club in
 * the world for every slot: <b>29,760 a week at 48 countries</b>, before a single friendly was arranged,
 * and three more for every decision after one was.
 *
 * <p><b>Why a query count and not a clock.</b> The whole pass over a development-sized world finishes in
 * milliseconds either way. The number that separates them is how often the database is asked.
 *
 * <p><b>What the second test does and does not prove.</b> It asserts a club is never booked twice in one
 * slot, which is the real correctness property. It does <b>not</b> prove the snapshot's write-backs are
 * load-bearing: with the pending set alone the uniqueness already holds, and deleting
 * {@code agreed()} or {@code refused()} changed no count here. The query count is what this fix buys and
 * the query count is what the first test holds.
 */
class AiFriendlyWeekQueryCountTest {

    /**
     * Week 6, and the choice is not arbitrary: the season template gives weeks 6, 11 and 12 two
     * friendly-capable slots and every other week a league match in both. A test on any other week
     * returns before it reads anything and passes on a pass that does nothing — which is exactly what
     * the first version of this test did.
     */
    private static final int FRIENDLY_WEEK = 6;

    private final FriendlyRequestRepository requests = mock(FriendlyRequestRepository.class);
    private final MatchFixtureRepository fixtures = mock(MatchFixtureRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final GameClockRepository clocks = mock(GameClockRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);

    /** Everything {@code save} was asked to write, which is how the second test sees the pass's work. */
    private final List<FriendlyRequest> written = new ArrayList<>();

    private FriendlyRequestService service() {
        // Constructor order is (requests, fixtures, teams, clocks, players).
        return new FriendlyRequestService(requests, fixtures, teams, clocks, players);
    }

    /** A week with no fixtures and no agreed friendlies, so every club is free and the pass works hardest. */
    private void anEmptyWeek() {
        when(fixtures.findBySeasonYearAndWeekNumber(any(), any())).thenReturn(List.of());
        when(requests.findBySeasonAndWeek(any(), any())).thenReturn(List.of());
        when(clocks.findAll()).thenReturn(List.of(clock(1, 1)));
        // Answering an accepted friendly writes the fixture it will be played as.
        when(fixtures.save(any(MatchFixture.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(teams.existsById(anyLong())).thenReturn(true);
        when(teams.findAll()).thenReturn(clubs(40));
        when(teams.findById(anyLong())).thenAnswer(i -> Optional.of(club(1)));
        // Save is update-or-insert, keyed by id, because `respond` saves the same request a second time
        // after it changes the status. Counting saves instead of distinct requests would then report two
        // arrangements where there was one.
        when(requests.save(any(FriendlyRequest.class))).thenAnswer(invocation -> {
            FriendlyRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId((long) written.size() + 1);
                written.add(saved);
            } else {
                for (int i = 0; i < written.size(); i++) {
                    if (saved.getId().equals(written.get(i).getId())) {
                        written.set(i, saved);
                        break;
                    }
                }
            }
            return saved;
        });
        // `respond` reads the request back before answering it, so the mock has to find it.
        when(requests.findByIdAndOpponentTeamId(anyLong(), anyLong())).thenAnswer(invocation ->
                written.stream()
                        .filter(r -> r.getId().equals(invocation.getArgument(0))
                                && Objects.equals(r.getOpponentTeamId(), invocation.getArgument(1)))
                        .findFirst());
    }

    @Test
    @DisplayName("a pass over 40 clubs reads the week twice, not once per club")
    void theWeekIsReadTwiceNotOncePerClub() {
        anEmptyWeek();

        assertTrue(service().runAiFriendlyWeek(1, FRIENDLY_WEEK, null) > 0,
                "the fixture week must actually arrange friendlies, or this test is measuring an early "
                        + "return and would pass on a pass that does nothing");

        verify(fixtures, times(1)).findBySeasonYearAndWeekNumber(any(), any());
        verify(requests, times(1)).findBySeasonAndWeek(any(), any());
    }

    @Test
    @DisplayName("a club is never booked twice in the same slot, so the snapshot is written to")
    void aClubIsNeverBookedTwiceInOneSlot() {
        anEmptyWeek();

        int arranged = service().runAiFriendlyWeek(1, FRIENDLY_WEEK, null);

        assertTrue(arranged > 0, "nothing was arranged, so the double-booking check below is vacuous");

        Map<String, Long> perSlot = written.stream()
                .filter(r -> r.getStatus() == FriendlyStatus.ACCEPTED)
                .collect(Collectors.groupingBy(
                        r -> r.getWeek() + ":" + r.getSlot() + ":" + r.getOpponentTeamId(),
                        Collectors.counting()));
        long doubles = perSlot.values().stream().filter(n -> n > 1).count();
        assertEquals(0, doubles,
                "the same club was written into the same slot more than once — the snapshot is read but "
                        + "never written back to: " + perSlot);
        assertEquals(arranged, perSlot.size(),
                "the pass reported arrangements it did not write, or wrote ones it did not report");
    }

    private GameClock clock(int season, int week) {
        GameClock clock = new GameClock();
        clock.setId(1L);
        clock.setCurrentSeason(season);
        clock.setCurrentWeek(week);
        clock.setCurrentDate(LocalDateTime.of(2026, 1, 5, 12, 0));
        return clock;
    }

    private Team club(long id) {
        Team team = new Team();
        team.setId(id);
        team.setName("Club " + id);
        return team;
    }

    private List<Team> clubs(int count) {
        List<Team> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) out.add(club(i));
        return out;
    }
}
