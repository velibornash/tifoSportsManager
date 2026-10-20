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
 * The substitution plan is actually reachable from the surface a manager uses (owner, 2026-10-10).
 *
 * <p><b>Why this class exists separately from {@link SubstitutionOutcomeIsShownTest}.</b> That one drives
 * {@code loadPlan} directly, which is the wrong level to look at this feature from. The plan was mounted
 * on {@code fixture-view.js}, and no route reached that view for a fixture: a played fixture gets
 * {@code js-load-match}, and an unplayed one tries {@code loadMatch} before {@code loadFixture} and always
 * wins. So the module was exercised, the controller was exercised with MockMvc, both suites were green,
 * and the feature was completely unreachable — the whole of T0-UI-4 and T1-16, dead in the application.
 *
 * <p>The defect lived in the <b>routing</b>, and nothing looked at the routing. This harness reads the
 * real routing function and the real mount, then drives the real module through both routes.
 *
 * <p><b>It has been seen to fail.</b> Deleting the single line {@code void mountSubstitutionPlan();} turns
 * {@code theMountIsCalled} red, and dropping the home-club test turns
 * {@code thePanelIsOfferedOnlyToTheHomeClub} red. Both are the defects themselves, not paraphrases of them.
 */
class SubstitutionPlanIsReachableTest {

    private static final Path HARNESS = Path.of("src/test/resources/js/route-to-substitution-plan.mjs");

    @Test
    @DisplayName("a manager reaches the substitution plan from the screen they actually open")
    void thePlanIsReachable() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertEquals(0, result.exitCode(),
                "the routing harness reported failures:\n" + result.output()
                        + "\n\nThis is the check that should have existed before T0-UI-4 was closed. The plan "
                        + "was mounted on a page no route renders, so a manager could not set a "
                        + "substitution condition at all — and neither could they read how one went after "
                        + "the whistle. Nothing failed, because nothing was reachable to fail.");
    }

    @Test
    @DisplayName("the mount is wired, not merely defined")
    void theMountIsCalled() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the mount is called"),
                "match-view.js defines mountSubstitutionPlan but never calls it.\n" + result.output()
                        + "\n\nThat is precisely how the fixture sheet died: the panel was mounted correctly "
                        + "on a view that nothing routed to, and defining a mount is not the same as "
                        + "reaching one.");
    }

    @Test
    @DisplayName("the panel is offered to the home club only, decided by id")
    void onlyTheHomeClubIsOfferedThePlan() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the panel is offered only to the home club"),
                "the ownership test is missing or does not compare club ids.\n" + result.output()
                        + "\n\nThe plan is the home club's instruction and the engine reads it for the home "
                        + "side. Comparing club *names* to decide that is how this codebase has been bitten "
                        + "repeatedly — T1-13b added a team id to the qualifying table for that reason.");
    }

    @Test
    @DisplayName("the panel is mounted in one place, not two")
    void oneMountingPoint() throws Exception {
        assumeTrue(nodeIsAvailable(), "Node is not on this machine's PATH");

        Result result = runHarness();

        assertTrue(result.output().contains("ok   the fixture sheet no longer mounts the plan"),
                "fixture-view.js still mounts the plan as well as match-view.js.\n" + result.output()
                        + "\n\nTwo mounting points drift, and the drift is silent. The failure mode of this "
                        + "feature has already been shown to be silence.");
    }

    @Test
    @DisplayName("the harness reads the real routing, so it cannot pass by finding nothing")
    void theHarnessCanFail() throws IOException {
        String harness = Files.readString(HARNESS, StandardCharsets.UTF_8);

        assertTrue(harness.contains("pages-renderers.js") && harness.contains("match-view.js"),
                "the harness must read the real routing and the real match view, or it proves nothing");
        assertTrue(harness.contains("bindScheduleInteractions"),
                "and it must look at the function that decides where a fixture opens");
        assertTrue(harness.contains("loadPlanForMatch") && harness.contains("loadPlan("),
                "both id spaces must be driven, or one of the two surfaces is untested");
        assertTrue(!harness.contains("slice(-1)")
                        && !harness.contains("renderers.indexOf('export function"),
                "and it must not search the stripped source for 'export function': that returns -1, and "
                        + "slice(-1) then hands every check a one-character string that matches nothing. "
                        + "This harness made exactly that mistake while it was being written.");
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

    /** Node on the PATH, or the Homebrew install this repository is built on. */
    private String nodeBinary() {
        Path homebrew = Path.of("/usr/local/bin/node");
        if (Files.isExecutable(homebrew)) {
            return homebrew.toString();
        }
        return "node";
    }
}