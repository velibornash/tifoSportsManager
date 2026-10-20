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
 * The match preview must not turn a club name into markup.
 *
 * <p><b>What this exists for.</b> The preview page builds its whole body by assigning one template
 * literal to {@code content.innerHTML}, and the strings in it — a club name, a formation, a play style,
 * an analyst's headline, a turning point, a player's name, a timeline entry — all come from the server.
 * The helper that wrapped them, {@code safe()}, only coalesced nulls, so every one of them went into
 * the DOM as markup. A club named {@code <img src=x onerror=...>} was not a name; it was a tag.
 *
 * <p><b>Why it needs an engine.</b> The file parses, {@code node --check} passes, and no static guard
 * on it ever noticed: the defect is not that a string is missing but that a string is <em>unescaped</em>,
 * and the only thing that can see that is the rendered output. So this drives the real module in Node
 * with a DOM stub and a hostile payload, and reads what reached {@code innerHTML}.
 *
 * <p><b>It was proven able to fail.</b> With the escaping removed from {@code safe()}, the same
 * harness reported <b>20 raw {@code <img src=x>} tags</b> and <b>0 escaped</b>; with the fix it reports
 * <b>0 raw</b> and <b>20 escaped</b>. The harness loads the real file from disk, so it measures the code
 * as it is and not a copy of it.
 */
class ZoxMatchPreviewEscapesServerStringsTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/render-match-preview-escaped.mjs");

    @Test
    @DisplayName("a club name that looks like a tag is rendered as text")
    void serverStringsAreEscaped() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        String output = runHarness();

        assertEquals(0, rawTagCount(output),
                "the preview put a raw <img> tag into innerHTML:\n" + output
                        + "\n\nA server string that reaches innerHTML unescaped is a hole: a club named "
                        + "<img src=x onerror=...> is markup, not a name.");
        assertTrue(escapedCount(output) > 0,
                "nothing was escaped, which means the hostile string never reached the page at all and "
                        + "this test is proving nothing:\n" + output);
    }

    @Test
    @DisplayName("the harness is wired to the real file, so it can fail")
    void theHarnessIsWiredToTheRealFile() throws IOException {
        // Not a runtime mutation of the source: this asserts the harness reads the real module, so a
        // harness reading nothing would not pass by rendering nothing.
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("zox-match-preview.js") && harness.contains("readFileSync"),
                "the harness must load the real zox-match-preview.js from disk, or it proves nothing");
        assertTrue(harness.contains("renderDashboard"),
                "and it must drive the page's own renderer, because the defect was in what that renderer "
                        + "put into innerHTML");
        assertTrue(harness.contains("innerHTML"),
                "and it must read the innerHTML it produced, because that is where the defect lands");
    }

    // ── harness plumbing ──────────────────────────────────────────────────────────────────────────

    private int rawTagCount(String output) {
        return count(output, "[raw-tag-count]");
    }

    private int escapedCount(String output) {
        return count(output, "[escaped-count]");
    }

    private int count(String output, String marker) {
        for (String line : List.of(output.split("\\R"))) {
            if (line.trim().startsWith(marker)) {
                return Integer.parseInt(line.trim().substring(marker.length()).trim());
            }
        }
        return -1;
    }

    private String runHarness() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(nodeBinary(), HARNESS.toString());
        builder.directory(Path.of(".").toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor(120, TimeUnit.SECONDS);
        return output;
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
