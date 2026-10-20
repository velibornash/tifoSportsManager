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
 * The game plan screen is reachable and says the right things (owner, 2026-10-10, T0-BE-2 UI).
 *
 * <p><b>Why this exists separately from the resolver tests.</b> The substitution feature had a module, a
 * controller, twenty unit tests and no path from a manager's click to a rendered screen, because it was
 * mounted on a view nothing routed to. The engine side of the conditional tactics had the same shape for a
 * while: resolver, conditions, priority, tick hook, and nothing in production handed it an assignment.
 *
 * <p>This harness loads the real module and renders it against a stubbed fetch layer, and separately reads
 * the real mounting in {@code match-view.js} — the two things that were each independently sufficient to
 * hide the substitution feature.
 */
class MatchTacticPlanScreenTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/render-match-tactic-plan.mjs");

    @Test
    @DisplayName("a manager reaches a rendered game plan on the fixture they open")
    void theScreenRenders() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertEquals(0, result.exitCode(),
                "the game plan harness reported failures:\n" + result.output()
                        + "\n\nWithout this screen the whole of T0-BE-2 is an engine feature no manager can "
                        + "reach: the conditions, the priority, the resolver and the tick hook all work, and "
                        + "none of them is ever set.");
    }

    @Test
    @DisplayName("the panel is mounted, not merely defined")
    void thePanelIsMounted() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the mount is called"),
                "match-view.js defines mountTacticPlan but never calls it.\n" + result.output()
                        + "\n\nDefining a mount is not the same as reaching one. That is precisely how the "
                        + "substitution plan sat on a page no route renders while every test stayed green.");
    }

    @Test
    @DisplayName("the screen cannot write the opposition's plan")
    void theScreenNeverChoosesASide() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the screen never sends a side"),
                "the screen must not send a side.\n" + result.output()
                        + "\n\nThe server resolves it from the session. A side sent from the browser would be "
                        + "a manager writing the opposition's plan for them, and the engine would obey it.");
    }

    @Test
    @DisplayName("a club not in the fixture is shown nothing at all")
    void aClubNotInTheFixtureIsShownNothing() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   a club not in the fixture renders nothing"),
                "an empty panel reads as \"you have no tactics\", which is a different statement:\n"
                        + result.output());
        assertTrue(result.output().contains("ok   and is not offered a form that cannot be filled"),
                "a club with no tactics must not be offered three empty dropdowns and a Save that can only "
                        + "fail:\n" + result.output());
    }

    @Test
    @DisplayName("the harness is reading the real screen")
    void theHarnessCanFail() throws IOException {
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("match-tactic-plan-view.js") && harness.contains("match-view.js"),
                "the harness must read both the screen and its mounting, or it proves nothing");
        assertTrue(harness.contains("loadPlan"),
                "and it must drive the screen's own entry point rather than a helper");
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

    private String nodeBinary() {
        Path homebrew = Path.of("/usr/local/bin/node");
        if (Files.isExecutable(homebrew)) {
            return homebrew.toString();
        }
        return "node";
    }
}