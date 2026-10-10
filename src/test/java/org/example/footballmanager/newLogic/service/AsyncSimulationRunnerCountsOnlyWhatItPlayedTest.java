package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fixture that was never played must never be counted as one that was (T-REST-17).
 *
 * <p><b>The defect.</b> The fixture loop ran inside
 * {@code transactionTemplate.executeWithoutResult(...)}. A lambda's {@code return} leaves the lambda and
 * nothing else, so the "this fixture has no home side or no away side" check returned into the loop
 * body — which went straight on to {@code simulatedCount.incrementAndGet()}. A fixture that could not be
 * played was reported as a match that had been.
 *
 * <p><b>This is a source check, and that is a considered decision rather than a fallback.</b> The runner
 * is a {@code @Service} singleton whose {@code running} flag rejects a concurrent call, and its entry
 * point is {@code @Async}. Driving it from a test means either sharing the singleton — where five tests
 * interfere and the failures are all about the flag rather than the count — or constructing one per
 * test, where the {@code @Async} thread outlives the assertion and the suite hangs. Both were tried; the
 * second hung the run past ten minutes.
 *
 * <p>The claim being protected is a <b>control-flow</b> claim: the increment must be reachable only when
 * the fixture was actually simulated. That is a statement about the shape of the code, and the shape is
 * what a source check can see. A behavioural test would be better if it could be written; this one is
 * here because it can be, and because the alternative was shipped untested.
 *
 * <p>It fails if the increment is moved back out of reach of the outcome branches — which is precisely
 * the regression, and precisely what happened.
 */
class AsyncSimulationRunnerCountsOnlyWhatItPlayedTest {

    private static final Path RUNNER =
            Path.of("src/main/java/org/example/footballmanager/newLogic/service/AsyncSimulationRunner.java");

    @Test
    @DisplayName("the simulated counter is only reached for a fixture that was simulated")
    void theIncrementIsGuardedByTheOutcome() throws Exception {
        String source = Files.readString(RUNNER);

        int increment = source.indexOf("simulatedCount.incrementAndGet()");
        assertTrue(increment > 0,
                "the simulated counter is gone from the runner, so this test is checking nothing");

        String before = source.substring(Math.max(0, increment - 2000), increment);

        // Every early return in the loop must leave the increment behind it. The defect was a lambda
        // `return` that did not: it skipped the rest of the lambda and fell into the increment below.
        assertTrue(before.contains("UNPLAYABLE") || before.contains("Outcome"),
                "nothing decides the outcome of a fixture before the simulated counter is incremented, so "
                        + "the counter is counting fixtures rather than matches. That is the defect this "
                        + "test exists for: a `return` that leaves only a lambda still falls through to "
                        + "the increment underneath it.");
    }

    @Test
    @DisplayName("a fixture with no sides is an outcome of its own, not a silent skip")
    void unplayableIsItsOwnOutcome() throws Exception {
        String source = Files.readString(RUNNER);

        assertTrue(source.contains("UNPLAYABLE"),
                "the runner no longer distinguishes a fixture it cannot play, which is how the original "
                        + "defect returned: without an outcome there is nothing for the loop to branch on");
        assertTrue(source.contains("ALREADY_GONE") && source.contains("SIMULATED"),
                "all three outcomes must be present. 'Nothing to do', 'could not be done' and 'was done' "
                        + "mean different things to whoever is waiting for the round, and only the last one "
                        + "counts as a match.");
    }

    @Test
    @DisplayName("an unplayable fixture is counted as a failure and named")
    void unplayableIsCountedAndNamed() throws Exception {
        String source = Files.readString(RUNNER);

        int branch = source.indexOf("Outcome.UNPLAYABLE", source.indexOf("if (outcome =="));
        assertTrue(branch > 0,
                "the UNPLAYABLE branch inside the loop has gone; unplayable fixtures are being counted as "
                        + "simulated again");

        String inBranch = source.substring(branch, branch + 500);
        assertTrue(inBranch.contains("failedCount.incrementAndGet()"),
                "an unplayable fixture must be counted somewhere. Counted as simulated it disappears; "
                        + "counted as nothing, a manager is never told a fixture went missing.");
        assertTrue(inBranch.contains("failedIds.add"),
                "and it must be named, so the status endpoint can tell 'not played' from 'played'. An "
                        + "unnamed failure is a log line nobody reads.");
    }

    /** A line that is code rather than prose. The comment explaining this defect quotes the very line. */
    private static boolean isCode(String line) {
        String t = line.trim();
        return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
    }

    @Test
    @DisplayName("every outcome that is not SIMULATED continues before the counter")
    void theRoundStillContinues() throws Exception {
        List<String> lines = Files.readAllLines(RUNNER);

        int incrementLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isCode(lines.get(i)) && lines.get(i).contains("simulatedCount.incrementAndGet()")) {
                incrementLine = i;
                break;
            }
        }
        assertTrue(incrementLine > 0, "the simulated counter is gone from the runner entirely");

        List<String> problems = new ArrayList<>();
        int guarded = 0;

        for (String outcome : List.of("UNPLAYABLE", "ALREADY_GONE")) {
            int branch = -1;
            for (int i = 0; i < incrementLine; i++) {
                if (isCode(lines.get(i))
                        && lines.get(i).contains("if (outcome == Outcome." + outcome + ")")) {
                    branch = i;
                    break;
                }
            }
            if (branch < 0) {
                problems.add("there is no branch for Outcome." + outcome + " before the counter");
                continue;
            }
            // Walk forward from the branch to the counter. A `continue` in that range is the guard: it
            // means the loop skips the increment for this outcome.
            boolean continues = false;
            for (int i = branch; i < incrementLine; i++) {
                if (lines.get(i).trim().equals("continue;")) {
                    continues = true;
                    break;
                }
            }
            if (continues) {
                guarded++;
            } else {
                problems.add("Outcome." + outcome + " is handled at line " + (branch + 1)
                        + " but does not `continue`, so the loop reaches the simulated counter at line "
                        + (incrementLine + 1) + " anyway. That is the defect.");
            }
        }

        assertEquals(2, guarded, "both non-SIMULATED outcomes must be guarded by a `continue`: " + problems);
        assertTrue(problems.isEmpty(), String.join("; ", problems));
    }
}
