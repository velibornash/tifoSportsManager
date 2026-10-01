package org.example.footballmanager.newLogic.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two jobs may not share a day and a key.
 *
 * <p>The done-flag is keyed on {@code (season, week, day, jobKey)} and <b>not on the hour</b>. That is safe
 * only while every registered job occupies a distinct {@code (day, key)} pair — because if two jobs shared
 * both, the first to run would mark the slot DONE and the second would be skipped for ever, silently, with
 * no error anywhere.
 *
 * <p>The codebase already relies on this and says so, in {@code MatchdayJobsConfig}:
 * <i>"Days 3 and 7 are separate instances of the same job with different keys, because the done-flag is
 * keyed on (season, week, day, key) and a shared key would let the day-3 job suppress the day-7 round."</i>
 *
 * <p>That is a comment. This is the check. It exists because A5 was reclassified on measurement, and the
 * measurement left one honest question: after a crash, jobs committed in {@code REQUIRES_NEW} sit ahead of a
 * clock that rolled back, and the retry re-evaluates those hours and skips what is already DONE. That is
 * self-healing — but it is self-healing <i>only</i> because the key is unambiguous. Add a second job on the
 * same day with the same key and one of the two stops running, and the retry logic quietly depends on a
 * property nothing enforces.
 */
class JobTriggerUniquenessTest extends org.example.footballmanager.BaseTest {

    @Autowired
    List<DayJob> jobs;

    @Test
    @DisplayName("no two jobs share a day and a key")
    void noTwoJobsShareADayAndAKey() {
        Map<String, String> seen = new HashMap<>();
        Set<String> clashes = new HashSet<>();

        for (DayJob job : jobs) {
            // ANY_DAY means the job is eligible every day, so it can only be told apart by its key.
            String day = job.day() == DayJob.ANY_DAY ? "any" : String.valueOf(job.day());
            String slot = day + "/" + job.key();

            String previous = seen.put(slot, job.key());
            if (previous != null) {
                clashes.add(slot);
            }
        }

        assertTrue(clashes.isEmpty(),
                "these jobs share a day and a key, so the first to run marks the slot DONE and the rest are "
                        + "skipped for ever with no error: " + clashes
                        + ". Give one of them a different key, as MatchdayJobsConfig does for day 3 and day 7.");
    }

    @Test
    @DisplayName("every job's key is unique on its own")
    void everyKeyIsUnique() {
        Set<String> keys = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (DayJob job : jobs) {
            if (!keys.add(job.key())) {
                duplicates.add(job.key());
            }
        }

        // Stricter than the invariant above and therefore not strictly required — but a duplicated key is
        // always a mistake waiting for a second registration on a different day.
        assertTrue(duplicates.isEmpty(),
                "these job keys are registered more than once: " + duplicates
                        + ". A shared key across different days works today and is one edit away from breaking.");
    }
}