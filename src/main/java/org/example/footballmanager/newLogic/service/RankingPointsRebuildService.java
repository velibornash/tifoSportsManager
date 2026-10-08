package org.example.footballmanager.newLogic.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one thing that writes the ranking points and the medals after a matchday (owner, 2026-10-08).
 *
 * <p><b>Why this class exists at all.</b> Four services already computed exactly the right things —
 * {@link ClubRankingPointsService}, {@link NationalRankingPointsService},
 * {@link AchievementBonusService} and {@link HonourService} — and <b>nothing in the application called
 * any of them</b>. Their tests called them, so they were green, and in a live game the ranking lists
 * sorted an empty ledger, no achievement bonus was ever applied, and no medal was ever derived. This is
 * the third time in this repository that a green status has hidden an empty table, so the wiring is now a
 * named thing with one caller rather than a hope that somebody remembers.
 *
 * <p><b>Order is the whole contract.</b> The base ledgers first, because the bonuses read those rows and
 * add to them; the bonuses before the medals, because a trophy bonus and a trophy medal come from the
 * same results and must not disagree about who won. All four in <b>one</b> transaction: a bonus computed
 * against a base ledger that then failed to commit is worse than no bonus at all, and the whole rebuild is
 * idempotent, so the next batch simply does it again.
 *
 * <p><b>Once per batch, never per match.</b> Each of the four replays the played match history, so running
 * them inside {@code SimMatchService.persist} would replay the world once per fixture. The owner chose the
 * matchday batch (2026-10-08) over the week rollover, so a manager sees the ladder move the same evening.
 */
@Slf4j
@Service
public class RankingPointsRebuildService {

    /** What one rebuild produced, for the log and for a caller that wants something checkable. */
    public record Summary(int season, int clubRows, int nationalRows,
                          int clubBonusRows, int nationalBonusRows, int medals) {
    }

    private final ClubRankingPointsService clubRankingPoints;
    private final NationalRankingPointsService nationalRankingPoints;
    private final AchievementBonusService achievementBonuses;
    private final HonourService honours;
    private final TransactionTemplate rebuildTemplate;

    public RankingPointsRebuildService(ClubRankingPointsService clubRankingPoints,
                                       NationalRankingPointsService nationalRankingPoints,
                                       AchievementBonusService achievementBonuses,
                                       HonourService honours,
                                       PlatformTransactionManager transactionManager) {
        this.clubRankingPoints = clubRankingPoints;
        this.nationalRankingPoints = nationalRankingPoints;
        this.achievementBonuses = achievementBonuses;
        this.honours = honours;
        // Its own template, not a shared bean: the simulation runner mutates the injected one's
        // propagation for its fixture loop, and the rebuild must not inherit that by accident.
        this.rebuildTemplate = new TransactionTemplate(transactionManager);
        this.rebuildTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Rewrites one season's ranking points, achievement bonuses and medals.
     *
     * @return what was written
     */
    public Summary rebuild(int season) {
        return rebuildTemplate.execute(status -> {
            ClubRankingPointsService.Result clubs = clubRankingPoints.recompute();
            NationalRankingPointsService.Result nations = nationalRankingPoints.recompute();
            AchievementBonusService.Result bonuses = achievementBonuses.apply(season);
            int medals = honours.derive(season);
            return new Summary(season,
                    clubs.rowsWritten(), nations.rowsWritten(),
                    bonuses.clubRows(), bonuses.nationalRows(), medals);
        });
    }

    /** The same thing, timed and swallowed — for a caller that must not fail a matchday. */
    public void rebuildAfterBatch(int season) {
        long startedAt = System.nanoTime();
        try {
            Summary summary = rebuild(season);
            log.info("Ranking after the batch (season {}): {} club row(s), {} national row(s), "
                            + "{} club and {} national bonus(es) applied, {} medal(s), in {} ms.",
                    summary.season(), summary.clubRows(), summary.nationalRows(),
                    summary.clubBonusRows(), summary.nationalBonusRows(), summary.medals(),
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException e) {
            // The football is already saved by this point and the next batch catches up anyway, so this
            // must never propagate into the matchday.
            log.warn("Could not rebuild the ranking after the batch: {}", e.getMessage(), e);
        }
    }
}