package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The numbers on a fixture row are numbers, and a null is not a zero.
 *
 * <p>The owner, 2026-10-22: *"za International meceve nije dobar round S1 · D1 · Wk 1 · Rd null,
 * verujem i za Cup, liga je dobra"* - the round on an international tie and on a cup tie read
 * "Rd null", while the league's read correctly.
 *
 * <p>The cause was one expression, used in four places on the row:
 * {@code Number.isFinite(Number(value))}. {@code Number(null)} is <b>zero</b>, so the league round -
 * which is null for every cup and every international tie, because the league round only exists for
 * a league - passed as "a number", and the row printed the null itself. The real round was in the row
 * the whole time, in the branch the broken test never let it reach.
 *
 * <p>The same null read as a zero counted a result the manager has not seen yet as a 0-0 draw in the
 * club page's "Wins / Draws / Losses" - so the number of draws gave the hidden result away before
 * it was revealed.
 *
 * <p><b>What this test can and cannot do.</b> The behavioural half runs the real renderer in node and
 * reads the real label, so it fails when the code is wrong - and it is skipped when node is absent,
 * because a missing tool is not a broken page. The source half always runs, and it cannot tell that
 * the code is wrong; it can only tell that the null-as-zero test is not back.
 */
class FixtureRowNumbersTest {

    /** The node script that renders the row for real. */
    private static final Path SCRIPT = Path.of("src", "test", "javascript", "fixture-row-numbers.test.mjs");

    @Test
    @DisplayName("the row's round and the club summary read their numbers as numbers")
    void theNumbersAreNumbers() throws IOException, InterruptedException {
        String node = nodeBinary();
        if (node == null) {
            System.out.println("node not on PATH - the fixture row's numbers are not checked here");
            return;
        }

        ProcessBuilder builder = new ProcessBuilder(node, SCRIPT.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();

        if (code != 0) {
            fail("The fixture row's numbers are wrong:" + System.lineSeparator() + output);
        }
        assertTrue(output.contains("OK"), "the script said nothing about what it checked:" + output);
    }

    @Test
    @DisplayName("the renderer does not read a null as a zero again")
    void theNullIsNotAZeroAgain() throws IOException {
        // The path is resolved from the source root the compiler already knows, not written down here.
        Path renderer = ProductionSource.root().getParent()
                .resolve(Path.of("resources", "static", "js", "pages-renderers.js"));
        assertTrue(Files.exists(renderer), "the renderer is not where it should be: " + renderer);
        String source = Files.readString(renderer);

        assertFalse(source.contains("Number.isFinite(Number(match?.leagueRound))"),
                "the league round is read with the null-as-zero test again, and a cup or an "
                        + "international tie will read 'Rd null'");
        assertFalse(source.contains("Number.isFinite(Number(match?.round))"),
                "the round is read with the null-as-zero test again");
        assertFalse(source.contains("Number.isFinite(Number(match?.homeGoals))"),
                "a result that has not been revealed yet is read as a 0-0 draw again, and the count "
                        + "of draws will give the hidden result away");
        assertTrue(source.contains("hasNumber(match?.leagueRound)"),
                "the league round is not read through the null-safe helper");
        assertTrue(source.contains("hasNumber(match?.round)"),
                "the round is not read through the null-safe helper");
    }

    /** The node binary, or null when node is not installed. */
    private static String nodeBinary() {
        for (String candidate : new String[]{"node", "/usr/local/bin/node", "/opt/homebrew/bin/node"}) {
            try {
                Process process = new ProcessBuilder(candidate, "--version").start();
                if (process.waitFor() == 0) {
                    return candidate;
                }
            } catch (IOException | InterruptedException ignored) {
                // Try the next one.
            }
        }
        return null;
    }
}
