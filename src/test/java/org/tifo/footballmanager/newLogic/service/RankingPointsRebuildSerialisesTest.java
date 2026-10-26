package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two rebuilds must not run at the same time, because each one deletes the ledger and writes it back.
 *
 * <p><b>What was seen.</b> On 2026-10-26 the scheduled game-clock tick and a clock-move walk both ran
 * {@code league-table-reconcile} over the same season, both replayed the match history, both deleted
 * the club ledger before either had inserted, and the second insert died on
 * {@code uk_club_season_points}: {@code Key (team_id, season_year)=(1008, 1) already exists}. The
 * matchday simulation pool calls the same rebuild when a batch finishes, so a second walk is not even
 * required to reproduce it.
 *
 * <p><b>What this test holds.</b> Not "the rebuild runs" — the other rebuild tests already do that —
 * but that a second call <b>waits</b> until the first has left the ledger alone. The first rebuild is
 * held open on a latch, and the second is given a third of a second to reach
 * {@code recompute()} anyway; without the lock it gets there immediately and the counter reads two.
 */
class RankingPointsRebuildSerialisesTest {

    private final ClubRankingPointsService clubs = mock(ClubRankingPointsService.class);
    private final NationalRankingPointsService nations = mock(NationalRankingPointsService.class);
    private final AchievementBonusService bonuses = mock(AchievementBonusService.class);
    private final HonourService honours = mock(HonourService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @Test
    @DisplayName("a second rebuild waits for the one in flight instead of rewriting the ledger beside it")
    void aSecondRebuildWaits() throws Exception {
        CountDownLatch insideFirstRebuild = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger replays = new AtomicInteger();

        when(clubs.recompute()).thenAnswer(invocation -> {
            replays.incrementAndGet();
            insideFirstRebuild.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the first rebuild was never released");
            }
            return new ClubRankingPointsService.Result(0, 0, 0, 0);
        });
        when(nations.recompute())
                .thenReturn(new NationalRankingPointsService.Result(0, 0, 0, 0));
        when(bonuses.apply(anyInt())).thenReturn(new AchievementBonusService.Result(0, 0));
        when(honours.derive(anyInt())).thenReturn(0);
        // The template needs a status to hand back to commit(); the manager itself is not what is under test.
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));

        RankingPointsRebuildService service = new RankingPointsRebuildService(
                clubs, nations, bonuses, honours, transactionManager);

        Thread first = new Thread(() -> service.rebuild(1), "first-rebuild");
        first.setDaemon(true);
        first.start();
        assertTrue(insideFirstRebuild.await(10, TimeUnit.SECONDS),
                "the first rebuild never reached the replay");

        Thread second = new Thread(() -> service.rebuild(1), "second-rebuild");
        second.setDaemon(true);
        second.start();
        Thread.sleep(300);

        assertEquals(1, replays.get(),
                "the second rebuild replayed the ledger while the first was still inside its own "
                        + "delete-and-write; that is the race that failed with uk_club_season_points");

        release.countDown();
        first.join(10_000);
        second.join(10_000);

        assertFalse(first.isAlive(), "the first rebuild never finished");
        assertFalse(second.isAlive(), "the second rebuild never finished once the first released the lock");
        assertEquals(2, replays.get(), "both rebuilds run, one after the other");
    }
}
