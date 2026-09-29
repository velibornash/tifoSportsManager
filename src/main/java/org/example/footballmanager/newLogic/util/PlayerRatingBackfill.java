package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** @return how many rows moved, and the spread of old values it found, for the boot log. */
    public Map<String, Object> backfill() {
        List<Player> all = players.findAll();
        List<Player> stale = new ArrayList<>();
        for (Player player : all) {
            int derived = player.careerRating();
            if (player.getRating() != derived) {
                stale.add(player);
            }
        }

        if (stale.isEmpty()) {
            return Map.of("changed", 0, "total", all.size());
        }

        Map<Integer, Integer> beforeSpread = new LinkedHashMap<>();
        int[] changed = {0};
        requiresNew.executeWithoutResult(status -> {
            for (Player player : stale) {
                int old = player.getRating();
                beforeSpread.merge(old, 1, Integer::sum);
                player.setRating(player.careerRating());
                players.save(player);
                changed[0]++;
            }
        });

        log.info("Player ratings: recomputed {} of {} player(s) from their skills. The values that were "
                + "there before were {}", changed[0], all.size(), beforeSpread);
        return Map.of("changed", changed[0], "total", all.size(), "beforeSpread", beforeSpread);
    }
}
