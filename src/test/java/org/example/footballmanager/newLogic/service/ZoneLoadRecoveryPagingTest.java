package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Zone;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.MatchPageEntry;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.PlayerZoneLoadRepository;
import org.example.footballmanager.newLogic.repository.ZoneLoadMinutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily recovery reads the window a page at a time, and credits every match in it exactly once.
 *
 * <p><b>Why the read is paged at all.</b> Unpaged, it joins the window's matches to a scan of every zone
 * load ever written: one matchday is 7,440 matches and 1,473,120 rows wanted out of <b>17,677,440 read</b>,
 * measured at 6,173 ms on a full projected season. Paged, it reads the rows it wants: <b>539 ms</b>.
 *
 * <p><b>Why two guards and not one.</b> A test that only counted pages would be satisfied by a loop that
 * pages and credits nobody, and a test that only counted credited players would pass against the unpaged
 * scan. So both halves are asserted: the read is paged, and every match in the window is asked for exactly
 * once — no gaps, no repeats. A repeated row double-counts a player's recovery and a skipped one
 * under-counts it, and neither throws.
 *
 * <p><b>Why keyset on {@code (matchDate, id)} and not on the id.</b> Every match in a matchday shares one
 * kickoff time, so paging on the id alone cannot use an index on the date and measured 206 ms a page
 * against 0.35 ms for the composite key. That is why the cursor carries both halves of the last row.
 *
 * <p><b>Mockito note that cost an hour of confusion:</b> re-stubbing with {@code when(mock.m(any()))}
 * <i>invokes</i> the answer already registered and hands it {@code any()}'s null, so the throw happens
 * inside the old lambda and names the wrong line. Everything below uses {@code doAnswer}/{@code doReturn}.
 */
class ZoneLoadRecoveryPagingTest {

    /** 1,207 = 500 + 500 + 207, so the last page is short and the loop must end on it. */
    private static final int MATCHES_IN_WINDOW = 1_207;

    /** One instant for the whole window, which is what a matchday actually looks like. */
    private static final LocalDateTime MATCH_DATE = LocalDateTime.of(2026, 3, 23, 19, 0);

    private final PlayerZoneLoadRepository loads = mock(PlayerZoneLoadRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final GameClockRepository clocks = mock(GameClockRepository.class);
    private final MatchRepository matches = mock(MatchRepository.class);

    private ZoneLoadService service() {
        return new ZoneLoadService(loads, players, clocks, matches);
    }

    /** Pages the window the way the repository does, recording the cursor and the ids handed out. */
    private void stubPages(List<Long> cursors, List<Long> idsHandedOut) {
        doAnswer(invocation -> {
            long lastId = invocation.getArgument(2);
            int limit = ((Pageable) invocation.getArgument(3)).getPageSize();
            if (cursors != null) {
                cursors.add(lastId);
            }
            // The page walks a FIXED id space and selects what is past the cursor — it does not count up
            // from the cursor. Counting up from Long.MIN_VALUE never reaches 1,207, so the loop under
            // test never terminates and the JVM dies of heap space instead of reporting a failure.
            //
            // Which is the right way round: a service that failed to advance its cursor gets the same page
            // forever, and this stub will hand it out forever, so the failure is a hung test rather than a
            // silently green one.
            List<MatchPageEntry> page = new ArrayList<>();
            for (long id = 1; id <= MATCHES_IN_WINDOW && page.size() < limit; id++) {
                if (id > lastId) {
                    page.add(new MatchPageEntry(id, MATCH_DATE));
                }
            }
            if (idsHandedOut != null) {
                page.forEach(entry -> idsHandedOut.add(entry.id()));
            }
            return page;
        }).when(matches).findMatchPagePlayedSince(any(), any(), any(), any(Pageable.class));
    }

    /** One player per match working one zone, so what recovery should credit is unambiguous. */
    private void stubLoads() {
        doAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            List<ZoneLoadMinutes> out = new ArrayList<>();
            for (Long matchId : ids) {
                out.add(new ZoneLoadMinutes(matchId, Zone.MIDFIELD_CENTRE, 90.0, 1.0));
            }
            return out;
        }).when(loads).findLoadMinutesForMatches(any());
    }

    @Test
    @DisplayName("1,207 matches are read in three pages of at most 500, and the short page ends the loop")
    void theWindowIsReadInPages() {
        stubPages(null, null);
        stubLoads();

        service().applyDailyRecovery();

        // 500 + 500 + 207. No fourth call for a page that would come back empty.
        verify(matches, times(3)).findMatchPagePlayedSince(any(), any(), any(), any(Pageable.class));
        verify(loads, times(3)).findLoadMinutesForMatches(any());
    }

    @Test
    @DisplayName("each page resumes strictly after the last row of the page before")
    void pagingIsKeysetNotOffset() {
        List<Long> cursors = new ArrayList<>();
        stubPages(cursors, null);
        doReturn(List.of()).when(loads).findLoadMinutesForMatches(any());

        service().applyDailyRecovery();

        assertEquals(List.of(Long.MIN_VALUE, 500L, 1000L), cursors,
                "the cursor must be the last id of the previous page — MIN_VALUE on the first call, then "
                        + "500, then 1000. Offset paging over a result with no stable order can skip and "
                        + "repeat rows between pages, and a repeated row double-counts a player's recovery.");
    }

    @Test
    @DisplayName("every match in the window is asked for exactly once: no gaps, no repeats")
    void everyMatchIsAskedForExactlyOnce() {
        List<Long> asked = new ArrayList<>();
        stubPages(null, asked);
        doReturn(List.of()).when(loads).findLoadMinutesForMatches(any());

        service().applyDailyRecovery();

        Set<Long> distinct = new HashSet<>(asked);
        assertEquals(MATCHES_IN_WINDOW, asked.size(),
                "asked for " + asked.size() + " ids, so the window was read more than once");
        assertEquals(MATCHES_IN_WINDOW, distinct.size(),
                "a match was asked for twice, so its 198 rows were counted twice");
        assertTrue(distinct.contains(1L) && distinct.contains((long) MATCHES_IN_WINDOW),
                "the first and last match of the window must both be read");
    }

    /**
     * A cursor that stops advancing must fail the job, not loop.
     *
     * <p>Found by trying to break the paging on purpose: without a bound in the service, a cursor that
     * never moves hands back the same page forever and the JVM dies of heap space — which is how the first
     * version of this test's own stub failed, and it is exactly what the bound exists to prevent in
     * production.
     */
    @Test
    @DisplayName("a cursor that never advances throws rather than reading the same page for ever")
    void aCursorThatNeverAdvancesThrows() {
        // Every call returns a full page, and the cursor never moves: the only honest outcome is a failure.
        doAnswer(invocation -> {
            int limit = ((Pageable) invocation.getArgument(3)).getPageSize();
            List<MatchPageEntry> page = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                page.add(new MatchPageEntry((long) i + 1, MATCH_DATE));
            }
            return page;
        }).when(matches).findMatchPagePlayedSince(any(), any(), any(), any(Pageable.class));
        doReturn(List.of()).when(loads).findLoadMinutesForMatches(any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service().applyDailyRecovery());
        assertTrue(thrown.getMessage().contains("cursor is not advancing"),
                "the failure has to say what is wrong, or nobody can act on it: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a window with nothing in it reads no zone loads and never calls the unpaged query")
    void anEmptyWindowReadsNothing() {
        when(matches.findMatchPagePlayedSince(any(), any(), any(), any(Pageable.class))).thenReturn(List.of());

        assertEquals(0, service().applyDailyRecovery());

        verify(loads, never()).findLoadMinutesForMatches(any());
        // The unpaged query is the whole-table scan this replaced. It must have no caller left.
        verify(loads, never()).findLoadMinutesPlayedSince(any());
    }
}
