package org.tifo.footballmanager.newLogic.jobs.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.tifo.footballmanager.newLogic.jobs.DayJob;
import org.tifo.footballmanager.newLogic.jobs.JobContext;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TransferRepository;
import org.tifo.footballmanager.newLogic.service.MoraleService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Day 6: form and morale (owner, 2026-10-22).
 *
 * <p>The owner's week carries a day for it: *"day 6 · form and morale"*. **Nothing ran there.** The
 * work already existed and had no caller — {@code MoraleService.applyStanding} ("is he being paid
 * fairly, is he in the side, is he being listed") had never been called by anything, and the weekly
 * settle of the form did not exist at all. A player's form only moved when he played, so a good week
 * was kept for ever and a bad one with it.
 *
 * <p><b>Why day 6, at 15:00.</b> The week is over: the day-3 league round and the day-5 cup have
 * both been played. The day-7 league round is not: the day-7 matchday is prepared at 15:00, so the
 * standing pressures are in place before the sides are picked — which is the whole point of asking
 * whether a player is in the side.
 *
 * <h2>What it does, for every club player in the world</h2>
 * <ol>
 *   <li><b>The form settles.</b> A quarter of the distance back to the natural level, so a good run
 *       takes about four weeks to fade.</li>
 *   <li><b>The standing pressures apply.</b> Out of the side, on the market, and the pay question.</li>
 * </ol>
 *
 * <h2>How it is asked, and how it is not</h2>
 * <p>Both inputs have to be derived, because the data does not hold them: {@code Player} has no
 * "started" flag and no "listed" flag, and {@code MatchPlayerStats} has minutes played but no
 * starter flag.
 *
 * <ul>
 *   <li><b>"In the side" = he played this week.</b> One query for the whole world — the players with
 *       an appearance in a match of this week. A club with no fixture that week is judged by
 *       nothing: a player who did not play is only out of his side if his club <em>played</em>.</li>
 *   <li><b>"On the market" = the codebase's own definition</b> — {@code LISTED}, no buyer, a real
 *       player. {@code TransferRepository.findActiveListedPlayerIds()} already answers it for the whole
 *       world, and it is the same predicate {@code TransferService.isActiveListing} uses.</li>
 * </ul>
 *
 * <h2>Why it is batched</h2>
 * <p>The world is fourteen thousand clubs and three hundred and seventy thousand players. Asked per
 * club it is fourteen thousand round trips a week; asked per player it is three hundred and seventy
 * thousand. **So the sweep is set-based at the top and paged at the bottom**: three queries for the
 * whole world, then the players read in pages of five hundred, each page in its own transaction.
 *
 * <p>The same lesson as the ban-check fix and the rating backfill, and the same reason the job opts
 * out of the framework's transaction: a page is a unit of work, and a page that fails must not take
 * the pages before it with it.
 */
@Component
public class FormAndMoraleJob implements DayJob {

    private static final Logger log = LoggerFactory.getLogger(FormAndMoraleJob.class);

    public static final String KEY = "form-and-morale";

    /**
     * How many players one page holds in memory.
     *
     * <p>A constant and not a percentage: the point is that peak memory is bounded by something a
     * human chose and does not move when the world does.
     */
    private static final int BATCH = 500;

    private final MoraleService morale;
    private final PlayerRepository players;
    private final MatchPlayerStatsRepository stats;
    private final TransferRepository transfers;
    private final MatchRepository matches;
    private final TransactionTemplate requiresNew;

    public FormAndMoraleJob(MoraleService morale, PlayerRepository players,
                            MatchPlayerStatsRepository stats, TransferRepository transfers,
                            MatchRepository matches, PlatformTransactionManager transactionManager) {
        this.morale = morale;
        this.players = players;
        this.stats = stats;
        this.transfers = transfers;
        this.matches = matches;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int week() {
        return ANY_WEEK;
    }

    @Override
    public int day() {
        return 6;
    }

    @Override
    public int hour() {
        return 15;
    }

    @Override
    public int order() {
        return 30;
    }

    /**
     * Runs outside the framework's transaction, committing a page at a time.
     *
     * <p>A page of five hundred players is a unit of work, and the framework's transaction is one
     * transaction for the whole world. See {@link DayJob#transactionalRun()}.
     */
    @Override
    public boolean transactionalRun() {
        return false;
    }

    @Override
    public void run(JobContext context) {
        int season = context.seasonYear();
        int week = context.weekNumber();

        // **Three questions, asked of the whole world at once.**
        //
        // Each of these is a set the sweep reads from memory. Asked per club or per player they would
        // be fourteen thousand and three hundred and seventy thousand round trips a week, and the
        // world's shape is not something a job may assume.
        Set<Long> onTheMarket = new HashSet<>(transfers.findActiveListedPlayerIds());
        Set<Long> playedThisWeek = new HashSet<>(stats.findPlayerIdsWithAppearancesInWeek(season, week));
        Set<Long> clubsWithFixtures = new HashSet<>(matches.findHomeClubIdsWithFixturesInWeek(season, week));
        clubsWithFixtures.addAll(matches.findAwayClubIdsWithFixturesInWeek(season, week));

        int settled = 0;
        long lastId = 0L;
        while (true) {
            final long from = lastId;
            Long next = requiresNew.execute(status -> settleOnePage(from, playedThisWeek, onTheMarket,
                    clubsWithFixtures));
            if (next == null) {
                break;
            }
            lastId = next;
            settled += BATCH;
        }

        log.info("Week {} season {}: form settled and standing pressures applied for {} player(s). "
                + "{} on the market.", week, season, settled, onTheMarket.size());
    }

    /**
     * One page of players, settled and committed.
     *
     * @return the id of the last player in the page, or {@code null} when there are no more
     */
    private Long settleOnePage(long from, Set<Long> playedThisWeek, Set<Long> onTheMarket,
                               Set<Long> clubsWithFixtures) {
        List<Player> page = players.findByIdGreaterThanOrderByIdAsc(from, PageRequest.of(0, BATCH));
        if (page.isEmpty()) {
            return null;
        }

        for (Player player : page) {
            if (!isClubPlayer(player)) {
                // A national squad's rows are copies of club players' rows, and a generated stand-in
                // belongs to a country nobody plays. Their form and morale are written by the copy
                // that made them, and settling them here would make the two drift apart.
                continue;
            }
            boolean inTheSide = playedThisWeek.contains(player.getId())
                    || !clubsWithFixtures.contains(clubIdOf(player));
            morale.settleWeek(player, inTheSide, onTheMarket.contains(player.getId()));
        }

        // The entities are managed in this transaction, so the changed rows are written when it
        // commits — and a row whose form and morale did not move is not written at all.
        return page.get(page.size() - 1).getId();
    }

    /**
     * Whether this player plays for a club.
     *
     * <p>{@code PyramidBuilder} creates every club in the world and never sets {@code Team.type}, so
     * a club's type is null and a national side's is {@code NATIONAL_TEAM}. A test for
     * {@code type == CLUB} alone would match no club at all.
     */
    private boolean isClubPlayer(Player player) {
        if (player == null || player.getTeam() == null) {
            return false;
        }
        return player.getTeam().getType() == null
                || player.getTeam().getType()
                        == org.tifo.footballmanager.newLogic.model.CompetitionTeamType.CLUB;
    }

    private Long clubIdOf(Player player) {
        return player.getTeam() == null ? null : player.getTeam().getId();
    }
}
