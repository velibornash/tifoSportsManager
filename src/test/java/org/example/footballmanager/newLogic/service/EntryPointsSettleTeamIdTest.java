package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every way into a view has to wait for the manager's club id (2026-10-10).
 *
 * <p><b>The defect.</b> A view asks {@code getTeamId()} and builds an ownership test or a URL out of it.
 * {@code getTeamId()} returns a variable that is {@code null} until {@code /auth/me} answers.
 * {@code loadPage} has always awaited that, which is why every page reached by clicking was safe — and
 * why the bug survived: the paths that bypassed {@code loadPage} are the ones nobody clicks from the nav.
 *
 * <p>It was found by opening the substitution plan, which had never been rendered by anyone. All three
 * panels on the match view gate on "is this the manager's own club" and all three read the id, so
 * opening a fixture from a deep link mounted nothing: no panel, no error, no warning, an empty host.
 * {@code loadPlayer}, {@code loadFixture}, {@code loadFormations} and seventeen more had the same shape
 * and would have built {@code /teams/null/players}.
 *
 * <p><b>Why this is a source check.</b> The defect is about <em>which</em> twenty-some functions settle
 * and in what order. Rendering one page cannot see that; the file can. It is the only check in this
 * repository that would have noticed the entry points added after this was written.
 */
class EntryPointsSettleTeamIdTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/entry-points-settle-team-id.mjs");

    @Test
    @DisplayName("every view-delegating entry point waits for the club id")
    void everyEntryPointSettles() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not available on this machine");

        Harness result = runHarness();

        assertEquals(List.of(), result.failures(),
                "entry points reach a view without settling the club id, so a deep link reads null:\n"
                        + result.output()
                        + "\n\n`loadPage` settles for itself. Every function that calls a view directly "
                        + "has to settle too, or a refresh into that page is cold.");
    }

    @Test
    @DisplayName("settling never becomes the return value")
    void settlingIsNotReturned() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not available on this machine");

        Harness result = runHarness();

        assertTrue(result.failures().stream().noneMatch(f -> f.contains("never returned")),
                "an entry point returns the settle instead of awaiting it, which makes the delegation "
                        + "after it unreachable — the view is never called and the page renders nothing:\n"
                        + result.output()
                        + "\n\nThis check exists because that mistake was made while writing the fix, and "
                        + "every other check in this file passed while it was in place.");
    }

    @Test
    @DisplayName("the harness is wired to the real files, and finds the entry points it claims to")
    void theHarnessIsReal() throws Exception {
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("src/main/resources/static/js/pages.js")
                        && harness.contains("match-view.js"),
                "the harness must read the real pages.js and match-view.js from disk, or a check that "
                        + "reads nothing would pass by finding nothing");
        assertTrue(harness.contains("delegating.length >= 19"),
                "and it must assert it found the entry points at all — a pattern that stops matching the "
                        + "source would otherwise leave every other check passing vacuously");

        assumeTrue(nodeIsAvailable(), "Node is not available on this machine");
        Harness result = runHarness();
        assertTrue(result.output().contains("the sweep found the entry points it was supposed to find"),
                "the sweep did not run:\n" + result.output());
        assertTrue(!result.output().contains("only "),
                "the sweep found too few entry points to be checking the file:\n" + result.output());
    }

    // ── harness plumbing ──────────────────────────────────────────────────────────────────────────

    private record Harness(List<String> failures, String output) {
    }

    private Harness runHarness() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(nodeBinary(), HARNESS.toString());
        builder.directory(Path.of(".").toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor(120, TimeUnit.SECONDS);

        List<String> failures = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith("FAIL ")) {
                failures.add(line.trim().substring(5).split(" — ")[0]);
            }
        }
        return new Harness(failures, output);
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