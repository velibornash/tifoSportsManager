package org.tifo.footballmanager.newLogic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The only thing a training match remembers: whether this account has spent today's match.
 *
 * <p><b>The owner's answer, verbatim (2026-10-27): "Only the limit, exactly as written."</b> The
 * score, the statistics and the recording are handed to the page that asked for them and are never
 * kept here — reload the page and they are gone. So there is deliberately <em>no</em> assertion in
 * this class about a result: a cache that started holding one would be a second, silent answer to
 * a question the owner already answered.
 *
 * <p>Plain JUnit: the cache has no dependencies, and a Spring context would add two minutes to
 * proving four facts about a map.
 */
class TrainingMatchLimitCacheTest {

    private static final int DAY = 1024; // season 1, week 2, day 4 — the shape GameClockService emits

    private final TrainingMatchLimitCache limits = new TrainingMatchLimitCache();

    @Test
    @DisplayName("a manager plays one training match per game day")
    void oneMatchPerGameDay() {
        assertTrue(limits.canPlay("manager@example.com", DAY), "the first match of the day is free");

        limits.record("manager@example.com", DAY);

        assertFalse(limits.canPlay("manager@example.com", DAY),
                "the day's match was spent and must not be handed out twice");
        assertTrue(limits.canPlay("manager@example.com", DAY + 1),
                "a new game day is a new match; a limit that never resets is a feature that dies "
                        + "on the first day it is used");
    }

    @Test
    @DisplayName("the account match is case-insensitive, so two spellings are one account")
    void theAccountMatchIsCaseInsensitive() {
        limits.record("Manager@Example.com", DAY);

        assertFalse(limits.canPlay("manager@example.com", DAY),
                "two spellings of one address are two accounts, and then the limit buys nothing");
    }

    @Test
    @DisplayName("velibor@example.com is never blocked, today or any day")
    void theOwnersAccountIsNeverBlocked() {
        assertTrue(TrainingMatchLimitCache.isUnlimited("velibor@example.com"));
        assertTrue(limits.canPlay("velibor@example.com", DAY));

        limits.record("velibor@example.com", DAY);

        assertTrue(limits.canPlay("velibor@example.com", DAY),
                "the owner asked for no limit on his own account so he can test the engine; the "
                        + "limit he wrote for everyone else must not reach it");
        assertTrue(limits.canPlay("velibor@example.com", DAY + 1));
        assertEquals(0, limits.size(),
                "an unlimited account must not be tracked at all, or the map fills with accounts "
                        + "that never consume what is being counted");
    }

    @Test
    @DisplayName("an account with no name plays nothing, rather than playing forever")
    void noAccountNoMatch() {
        assertFalse(limits.canPlay(null, DAY));
        limits.record(null, DAY);
        assertFalse(limits.canPlay(null, DAY));
    }

    @Test
    @DisplayName("the map is bounded, so remembering cannot outlive the JVM")
    void theMapIsBounded() {
        for (int i = 0; i < 600; i++) {
            limits.record("account-" + i + "@example.com", DAY);
        }

        assertTrue(limits.size() <= 512,
                "an unbounded map keyed by account is the shape of defect this codebase has already "
                        + "paid for twice; it held " + limits.size());
    }
}
