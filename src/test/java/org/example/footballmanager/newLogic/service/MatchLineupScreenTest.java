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
 * The team selection screen is reachable and says the right things (owner, 2026-10-10, T0-BE-3 UI).
 *
 * <p>The same lesson as the substitution plan and the game plan, which is why it is asserted twice over
 * here: the module renders, <em>and</em> the mounting in {@code match-view.js} is checked. Either alone was
 * independently sufficient to hide those features while every test stayed green.
 */
class MatchLineupScreenTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/render-match-lineup.mjs");

    @Test
    @DisplayName("a manager reaches a rendered team selection on the fixture they open")
    void theScreenRenders() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertEquals(0, result.exitCode(),
                "the team selection harness reported failures:\n" + result.output()
                        + "\n\nWithout this screen a club can pick eleven in the database and never on the "
                        + "page, so the feature exists for nobody.");
    }

    @Test
    @DisplayName("the panel is mounted, not merely defined")
    void thePanelIsMounted() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the mount is called"),
                "match-view.js defines mountLineup but never calls it.\n" + result.output()
                        + "\n\nDefining a mount is not reaching one. That is how the substitution plan sat "
                        + "on a page no route renders while every test stayed green.");
    }

    @Test
    @DisplayName("a manager sees who is unavailable before choosing, not after")
    void availabilityIsShownUpFront() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the warning is on the screen before anything is submitted"),
                "the goalkeeper warning and any suspension must be on the screen the manager is looking at.\n"
                        + result.output());
        assertTrue(result.output().contains("ok   a suspended player is marked in the squad list"),
                "and the reason has to be on the player's row, not only in a summary about the eleven.\n"
                        + result.output());
    }

    @Test
    @DisplayName("a club not in the fixture is shown nothing at all")
    void aClubNotInTheFixtureIsShownNothing() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   a club not in the fixture renders nothing"),
                "an empty panel reads as \"you have no squad\", which is a different statement:\n"
                        + result.output());
    }

    @Test
    @DisplayName("the harness reads the real screen")
    void theHarnessCanFail() throws IOException {
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("match-lineup-view.js") && harness.contains("match-view.js"),
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