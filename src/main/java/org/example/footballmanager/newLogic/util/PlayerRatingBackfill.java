package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings existing players' stored rating onto {@link Player#careerRating()} (owner, 2026-09-29).
 *
 * <p>The {@code rating} column had three writers meaning three different things, and the world carries
 * the evidence: in a freshly seeded database 2 350 players read exactly 96 — {@code BASE_SKILL * 8} —
 * and 5 250 read 0, because nothing had ever rated them. {@code PlayerDTO.calculateOverall} only
 * applied its rating bonus when the value was positive, so those two populations of identical ability
 * sat about six OVR points apart, decided by which seeder created the row.
 *
 * <p>This recomputes the column from the skills that are already there, so the numbers converge on
 * the same definition the seeders and the academy now use. It is safe to run at any time: it only
 * writes a row whose rating is not already what {@code careerRating()} computes, so a settled world
 * is left alone and a re-boot does no work.
 *
 * <p>Its own transaction, for the reason the other two backfills need one: this runs inside the boot
 * listener's transaction, and a save that joins somebody else's transaction is a save that may never
 * have happened.
 */
@Component
public class PlayerRatingBackfill {

    private static final Logger log = LoggerFactory.getLogger(PlayerRatingBackfill.class);

    private final PlayerRepository players;
    private final TransactionTemplate requiresNew;

    public PlayerRatingBackfill(PlayerRepository players, PlatformTransactionManager transactionManager) {
        this.players = players;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * How many players one pass holds in memory.
     *
     * <p>Deliberately a constant and not a percentage: the point is that peak memory is bounded by
     * something a human chose, and does not move when the world does.
     */
    private static final int BATCH = 500;

    /**
     * Recomputes every player's rating from their own skills.
     *
     * <p><b>Read in batches, not with {@code findAll()}.</b> This used to load the entire player table
     * into one {@code List<Player>}, then build a second list of the stale ones on top of it. The owner
     * asked what a full pyramid for all 48 countries would cost, and this was the answer that mattered:
     * 10,130 players today and roughly 373,000 at that scale, every one of them resident at once.
     *
     * <p>A loaded entity runs about three times its 287-byte stored tuple, so the whole table in one
     * pass is a few hundred megabytes of heap in a single method, and a second list of references on
     * top. Nothing about the answer changes when the table grows - only the memory needed to reach it.
     *
     * <p>Each batch is written in its own transaction ({@code requiresNew}), so a failure part way
     * through keeps the batches already committed rather than rolling back the world's ratings, and no
     * transaction is held open across the table.
     *
     * <p>Sorted by id and paged by offset: only ratings change here, never the number of rows or their
     * ids, so the page boundaries stay put while the pass runs.
     */
    public Map<String, Object> backfill() {
        Map<Integer, Integer> beforeSpread = new LinkedHashMap<>();
        int total = 0;
        int changed = 0;

        for (int page = 0; ; page++) {
            Page<Player> batch = players.findAll(PageRequest.of(page, BATCH, Sort.by("id")));
            List<Player> content = batch.getContent();
            if (content.isEmpty()) {
                break;
            }
            total += content.size();

            List<Player> stale = new ArrayList<>();
            for (Player player : content) {
                if (player.getRating() != player.careerRating()) {
                    stale.add(player);
                }
            }

            if (!stale.isEmpty()) {
                Map<Integer, Integer> batchSpread = new LinkedHashMap<>();
                requiresNew.executeWithoutResult(status -> {
                    for (Player player : stale) {
                        batchSpread.merge(player.getRating(), 1, Integer::sum);
                        player.setRating(player.careerRating());
                        players.save(player);
                    }
                });
                beforeSpread.putAll(batchSpread);
                changed += stale.size();
            }

            if (!batch.hasNext()) {
                break;
            }
        }

        log.info("Player ratings: recomputed {} of {} player(s) from their skills. The values that were "
                + "there before were {}", changed, total, beforeSpread);
        return Map.of("changed", changed, "total", total, "beforeSpread", beforeSpread);
    }
}
