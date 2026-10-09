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
 * The country page is rendered in a real JavaScript engine, so an undefined name fails here (owner,
 * 2026-10-08).
 *
 * <p><b>What this exists for.</b> A national warm-up panel was written into {@code buildGeneralTab},
 * which destructures only {@code sortedLeagues, senior, u21} out of its context. The panel referenced
 * {@code tab} and four other names that do not exist there, so <b>every tab of the country page</b> died
 * with {@code ReferenceError: tab is not defined}. Found by a person opening the page.
 *
 * <p><b>Why nothing else caught it.</b> Four static guards on that file were green throughout:
 * the endpoint strings were present, the copy was present, the reads were still gated on the two
 * national-team tabs, and {@code node --check} passed — it parses, and the name was only undefined at
 * run time. A {@code ReferenceError} inside a template string needs an <b>engine</b>.
 *
 * <p>{@code CountryPageRendersTest} does exactly this with Chromium and a running application, and it is
 * the right check — but it needs a browser and a server, so it is skipped in CI. This one needs only
 * Node: it loads the real module with the fetch layer stubbed and drives the real
 * {@code loadCountryPage} over every tab. Same failure, no infrastructure.
 */
class CountryPageRendersWithoutReferenceErrorTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/render-country-view.mjs");

    @Test
    @DisplayName("every country page tab renders without a ReferenceError")
    void everyTabRenders() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertEquals(List.of(), result.failedTabs(),
                "these tabs did not render: " + result.output()
                        + "\\n\\nA tab that fails here throws a ReferenceError in a template string — the "
                        + "kind of defect no static check on the file can see, and the kind that was found "
                        + "by a person opening the page.");
    }

    @Test
    @DisplayName("the warm-up panel appears on the national-team tabs and not on the others")
    void theWarmUpPanelIsWhereItBelongs() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.warmUpTabs().contains("senior"),
                "the senior tab is the national team's own page and must carry the warm-up panel; the "
                        + "harness reported it on " + result.warmUpTabs() + "\n" + result.output());
        assertTrue(result.warmUpTabs().contains("u21"),
                "and so must U-21, which is a separate side with its own warm-ups");
        assertTrue(!result.warmUpTabs().contains("general"),
                "a warm-up is a thing the national team does, so it does not belong on the country "
                        + "overview — and that is exactly where it was first written, which broke every "
                        + "tab of the page");
    }

    @Test
    @DisplayName("the represented-country path renders, and it is reached before the try block")
    void theRepresentedCountryRenders() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        // Twenty-four of the forty-eight countries on the World page have no club pyramid, and a
        // manager reaches this path by clicking a country name in a national-tournament group table.
        // It dispatches to its own function rather than to the tab builders, and it does so from
        // OUTSIDE the try block, so a ReferenceError there is not caught and the manager gets a blank
        // page rather than an error card.
        //
        // Its function was deleted by the commit that restored the country page (73aafa6) and never
        // restored with it. All six tabs worked, every test was green, and `node --check` passed,
        // because nothing rendered this path.
        assertTrue(!result.representedFailed(),
                "the represented-country page did not render:\n" + result.output()
                        + "\n\nThis is a different code path from the six tabs: it is reached before the "
                        + "try block, so a ReferenceError there is uncaught and the manager gets nothing.");
    }

    @Test
    @DisplayName("the country being viewed is marked in its own group table")
    void theViewedCountryIsMarked() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.representedMarkedOwnRow(),
                "the country being viewed carried no is-highlighted class in its qualifying group:\n"
                        + result.output()
                        + "\n\n\"Who are we drawn with\" is the question this page answers, and a table "
                        + "in which your own row looks like every other row answers it by name-matching. "
                        + "The class was emitted and had no definition in the stylesheet, so it was "
                        + "invisible either way.");
    }

    @Test
    @DisplayName("the harness itself fails when the panel is put back in the general tab")
    void theHarnessCanFail() throws IOException {
        // Not a runtime mutation of the source: this asserts the harness is wired to the real file, so
        // that a harness reading nothing would not pass by rendering nothing.
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("country-view.js") && harness.contains("readFileSync"),
                "the harness must load the real country-view.js from disk, or it proves nothing");
        assertTrue(harness.contains("loadCountryPage"),
                "and it must drive the page's own entry point rather than calling a builder directly, "
                        + "because the defect was about which function referenced what");
        assertTrue(List.of("general", "calendar", "clubs", "qualifying", "senior", "u21")
                        .stream().allMatch(harness::contains),
                "every tab has to be in the list: a harness that renders one tab cannot see a break in another");
        assertTrue(harness.contains("simulatedCountry"),
                "and it must drive the represented-country path, which is a different function reached "
                        + "before the try block — the one that was deleted and never restored");
    }

    // ── harness plumbing ──────────────────────────────────────────────────────────────────────────

    private record Result(List<String> failedTabs, List<String> lines, String output) {
        /** The tabs whose rendered HTML carried the warm-up panel. */
        List<String> warmUpTabs() {
            List<String> withPanel = new ArrayList<>();
            for (String line : lines) {
                if (line.contains("[warm-up present]")) {
                    withPanel.add(line.trim().split("\\s+")[0]);
                }
            }
            return withPanel;
        }

        boolean representedFailed() {
            return lines.stream().anyMatch(line -> line.startsWith("represented")
                    && !line.contains("ok"));
        }

        boolean representedMarkedOwnRow() {
            return lines.stream().anyMatch(line -> line.startsWith("represented")
                    && line.contains("[own row marked]"));
        }
    }

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

        List<String> lines = List.of(output.split("\\R")).stream().map(String::trim).toList();
        List<String> failed = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("general") || trimmed.startsWith("calendar") || trimmed.startsWith("clubs")
                    || trimmed.startsWith("qualifying") || trimmed.startsWith("senior")
                    || trimmed.startsWith("u21")) {
                if (!trimmed.contains("ok")) {
                    failed.add(trimmed.split("\\s+")[0]);
                }
            }
        }
        return new Result(failed, lines, output);
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