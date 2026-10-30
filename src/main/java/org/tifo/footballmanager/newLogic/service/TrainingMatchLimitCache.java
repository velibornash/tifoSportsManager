package org.tifo.footballmanager.newLogic.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * How many training matches an account has played today, and nothing else.
 *
 * <p><b>The owner's rule (2026-10-27): one a day, and "ne snima se u bazu, samo se pamti limit i stoji
 * u cache".</b> A training match writes no rows at all — no fixture, no match, no player stats, no
 * condition — so the daily limit cannot live on a club row the way a practice match's
 * {@code lastPracticeMatchDay} does. It lives here, in memory, and this class holds <b>only</b> the
 * limit. The score, the statistics and the recording of a training match are handed to the page that
 * asked for them and are deliberately not kept: reload the page and they are gone, exactly as written.
 *
 * <p><b>Bounded on purpose.</b> An unbounded map keyed by account grows for the life of the JVM, which
 * is the shape of defect this codebase has already paid for twice (the tactics rules cache, the content
 * cache). This is an LRU capped at 512 accounts — many more managers than this game has — so whoever
 * has not played lately is evicted rather than remembered forever.
 */
@Component
public class TrainingMatchLimitCache {

    /**
     * Accounts that play without a limit (owner, 2026-10-27: "za mog usera velibor@example.com bez
     * limita" — so he can test the engine as many times in a day as he needs).
     */
    private static final Set<String> UNLIMITED = Set.of("velibor@example.com", "tifoTest1@example.com");

    private static final int MAX_ACCOUNTS = 512;

    /** Account (lower-cased) -> the game day its last training match was played on. */
    private final Map<String, Integer> lastDayPlayed = Collections.synchronizedMap(
            new LinkedHashMap<String, Integer>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_ACCOUNTS;
                }
            });

    /** Whether this account is exempt from the daily limit. */
    public static boolean isUnlimited(String account) {
        return account != null && UNLIMITED.contains(account.toLowerCase(Locale.ROOT));
    }

    /** Whether this account may play a training match on {@code gameDay}. */
    public boolean canPlay(String account, int gameDay) {
        if (account == null) {
            return false;
        }
        if (isUnlimited(account)) {
            return true;
        }
        Integer played = lastDayPlayed.get(account.toLowerCase(Locale.ROOT));
        return played == null || played != gameDay;
    }

    /** Records that this account has spent today's match. Unlimited accounts are not tracked. */
    public void record(String account, int gameDay) {
        if (account == null || isUnlimited(account)) {
            return;
        }
        lastDayPlayed.put(account.toLowerCase(Locale.ROOT), gameDay);
    }

    /** How many accounts are being remembered — for a test to assert the bound holds. */
    public int size() {
        return lastDayPlayed.size();
    }
}
