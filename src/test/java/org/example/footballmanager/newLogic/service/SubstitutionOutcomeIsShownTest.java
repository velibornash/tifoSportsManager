package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The post-match substitution outcome is rendered by a real JavaScript engine (owner, 2026-10-10).
 *
 * <p><b>What this exists for.</b> {@code SubstitutionPlan.outcomeJson} was written by every simulation
 * and returned by {@code SubstitutionPlanController}, and the board recorded that "the post-match screen
 * reports each rule as fired or void". It did not. {@code substitution-plan-view.js} parsed
 * {@code rulesJson} and nothing else, and {@code fixture-view.js} bailed out of mounting the panel
 * altogether once a fixture was played — so the record was written on every match, served on every
 * request, and displayed nowhere. A manager whose condition quietly died of an empty bench had no way
 * to find that out.
 *
 * <p><b>Why a Node harness and not static guards.</b> The old view asserted over strings in a file, which
 * is how three tests in this repository stayed green while measuring nothing. This one loads the real
 * module, stubs the fetch layer and renders real HTML, so it fails on the same defects the
 * {@code CountryPageRendersWithoutReferenceErrorTest} was written for: a {@code ReferenceError} inside a
 * template string, and an outcome attributed to the wrong rule.
 *
 * <p><b>It has been seen to fail.</b> Both mutations were run against it and both were caught: switching
 * {@code matchOutcomes} from content matching to position matching failed four checks, and deleting the
 * badge from the rule row failed five.
 */
class SubstitutionOutcomeIsShownTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/render-substitution-outcome.mjs");

    @Test
    @DisplayName("each condition reports how it actually went, in words")
    void theOutcomeIsRendered() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertEquals(0, result.exitCode(),
                "the substitution outcome harness reported failures:\n" + result.output()
                        + "\n\nA void condition has to say WHY it could not happen. The engine writes a "
                        + "VoidReason enum and nothing read it, so a dead instruction was invisible until "
                        + "the manager noticed the player never came on.");
    }

    @Test
    @DisplayName("a plan whose conditions never fired is not badged as though they had")
    void aNeverDueConditionIsNotAFailure() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   a pending rule reads as never-due"),
                "a PENDING rule must read as a condition that never came up, not as a broken one. "
                        + "\"If we are losing from 60\" on a game won 3-0 was a correct instruction:\n"
                        + result.output());
    }

    @Test
    @DisplayName("the outcome is lined up with its rule by content, not by position")
    void outcomesAreMatchedByContent() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the rule that did go void is the one badged"),
                "an outcome was attributed to the wrong rule.\n" + result.output()
                        + "\n\nThe engine skips a rule it cannot parse when it builds the outcome list, which "
                        + "shifts every later row by one. Matching on position would then tell a manager "
                        + "that rule A fired at rule B's minute — a confident, wrong answer, which is worse "
                        + "than showing nothing.");
    }

    @Test
    @DisplayName("the harness is wired to the real view, not to a copy")
    void theHarnessCanFail() throws IOException {
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("substitution-plan-view.js") && harness.contains("readFileSync"),
                "the harness must load the real substitution-plan-view.js from disk, or it proves nothing");
        assertTrue(harness.contains("loadPlan"),
                "and it must drive the view's own entry point rather than calling a helper directly, because "
                        + "the defect was about what the rendered page shows");
        assertTrue(harness.contains("matchOutcomes") && harness.contains("describeOutcome"),
                "the alignment and the wording are the two parts worth pinning, so both must be exercised");
    }

    // ── harness plumbing ──────────────────────────────────────────────────────────────────────────────

    private record Result(int exitCode, List<String> lines, String output) { }

    private Result runHarness() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(nodeBinary(), HARNESS.toString());
        builder.directory(Path.of(".").toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor(120, TimeUnit.SECONDS);

        return new Result(process.exitValue(), List.of(output.split("\\R")), output);
    }

    /** Whether Node can be run at all, so a machine without it skips rather than fails. */
    private boolean nodeIsAvailable() {
        try {
            Process process = new ProcessBuilder(nodeBinary(), "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Node on the PATH, or the Homebrew install this repository is built on. */
    private String nodeBinary() {
        Path homebrew = Path.of("/usr/local/bin/node");
        if (Files.isExecutable(homebrew)) {
            return homebrew.toString();
        }
        return "node";
    }
}