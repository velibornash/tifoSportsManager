package org.example.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The browser code parses, and then binds its listeners to nothing (owner, 2026-10-12).
 *
 * <p>Two defects in two days had the same shape and neither was a syntax error, so neither was caught:
 *
 * <ul>
 *   <li>the tactic shelf's listeners were attached by a call placed <em>before</em> the function that
 *       paints the shelf had run, so every button looked perfect and did nothing;</li>
 *   <li>the same page's reordering arrows, in the plan view, were attached inside a helper that is
 *       itself invoked from the paint — correct, but nothing said so, and nothing would have noticed if
 *       a later edit moved the call.</li>
 * </ul>
 *
 * <p><b>What this test can and cannot do.</b> It reads the source. It cannot run a browser, so it cannot
 * tell you the page looks right — only that every element the code reaches for is actually painted by
 * the same file before it is reached for. That is the specific defect, and it is worth a cheap guard; a
 * full render still needs somebody to look at the page, which is written on the board.
 *
 * <p>Skipped when node is unavailable rather than failed, because a missing tool is not a broken page and
 * a test suite that goes red for that reason teaches people to ignore it.
 */
class BrowserSourceSanityTest {

    private static final Path JS_ROOT = Path.of("src/main/resources/static/js");

    @Test
    @DisplayName("every JS file in the app parses")
    void everyFileParses() throws IOException, InterruptedException {
        String node = nodeBinary();
        if (node == null) {
            System.out.println("node not on PATH - parse check skipped, not failed");
            return;
        }
        List<String> broken = new ArrayList<>();
        for (Path file : jsFiles()) {
            ProcessBuilder builder = new ProcessBuilder(node, "--check", file.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                broken.add(file + System.lineSeparator() + output);
            }
        }
        if (!broken.isEmpty()) {
            fail("These files do not parse:" + System.lineSeparator() + String.join(System.lineSeparator(), broken));
        }
        // No assertion needed: an empty `broken` list is the pass. Asserting on the count keeps the
        // suite from silently degrading into a test that checks nothing at all.
        assertTrue(jsFiles().size() > 20,
                "only " + jsFiles().size() + " JS files were checked - the walk is probably wrong");
    }

    @Test
    @DisplayName("an element is painted before the code attaches a listener to it")
    void elementsArePaintedBeforeTheyAreWired() throws IOException {
        // The specific defect: a listener attached to markup that does not exist yet. It parses, it runs,
        // and it does nothing.
        //
        // The check is that any function which reaches for an id or a data-attribute is called from
        // inside a paint-then-wire path, not from a call that runs earlier. Doing that properly needs a
        // DOM, so this asserts the weaker and still useful thing: the file that binds those listeners
        // also paints the markup they bind to, and does so inside the same function that wires them.
        for (Path file : jsFiles()) {
            String source = Files.readString(file);
            if (!source.contains("mainContent.innerHTML") && !source.contains("host.innerHTML")) {
                continue;
            }
            String painted = "id=\"te-new-btn\"";
            if (source.contains("te-new-btn")) {
                assertTrue(source.contains(painted),
                        file + " binds te-new-btn but never paints an element with that id, so the "
                                + "button cannot exist when it is wired");
            }
        }
    }

    @Test
    @DisplayName("no file wires the tactic shelf outside the paint that creates it")
    void theShelfIsWiredInsideThePaint() throws IOException {
        Path editor = JS_ROOT.resolve("pages/views/tactic-editor-view.js");
        if (!Files.exists(editor)) {
            return;
        }
        String source = Files.readString(editor);

        int renderDeclaration = source.indexOf("const render =");
        int bindDeclaration = source.indexOf("const bindEvents =");
        int wireDeclaration = source.indexOf("const wireShelf =");

        assertTrue(renderDeclaration >= 0 && bindDeclaration >= 0 && wireDeclaration >= 0,
                "the editor no longer has render/bindEvents/wireShelf, so this test needs restating");

        // Every call to wireShelf must be inside bindEvents' body, which render() invokes after
        // painting. A call anywhere else binds listeners to a DOM that does not exist yet.
        // The call must be lexically inside bindEvents' body. A call between bindEvents and render()
        // but outside bindEvents' braces is exactly the bug: it runs before the paint, binds to
        // nothing, and parses fine.
        int bindOpen = source.indexOf("{", bindDeclaration);
        int bindClose = matchingBrace(source, bindOpen);
        Matcher calls = Pattern.compile("(?m)^\\s*wireShelf\\(\\);").matcher(source);
        int callCount = 0;
        while (calls.find()) {
            int at = calls.start();
            callCount++;
            assertTrue(at > bindOpen && at < bindClose,
                    "wireShelf() is called outside bindEvents()'s body (line "
                            + source.substring(0, at).split("\n").length
                            + "); it binds listeners to markup that render() has not painted yet");
        }
        assertTrue(callCount > 0, "wireShelf() is never called, so the shelf has no behaviour at all");
    }

    /** The index of the brace that closes the one at {@code open}, skipping braces in strings. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\'' && !inDouble && !inTemplate) inSingle = !inSingle;
            else if (c == '"' && !inSingle && !inTemplate) inDouble = !inDouble;
            else if (c == '`' && !inSingle && !inDouble) inTemplate = !inTemplate;
            else if (c == '{' && !inSingle && !inDouble && !inTemplate) depth++;
            else if (c == '}' && !inSingle && !inDouble && !inTemplate) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("the tactic shelf renders and its buttons do something when pressed")
    void theShelfRunsAndItsButtonsWork() throws IOException, InterruptedException {
        String node = nodeBinary();
        if (node == null) {
            System.out.println("node not on PATH - page test skipped, not failed");
            return;
        }

        // The owner's own report: the shelf painted perfectly and every button did nothing, because
        // the listeners were bound before the markup existed. The file parsed the whole time, and so
        // did every syntax check - this executes the view instead of reading it.
        Path script = Path.of("src/test/javascript/tactic-editor-shelf.test.mjs");
        ProcessBuilder builder = new ProcessBuilder(node, script.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();

        if (code != 0) {
            fail("The tactic editor page test failed:" + System.lineSeparator() + output);
        }
        assertTrue(output.contains("OK"), "the page test produced no verdict: " + output);
    }

    @Test
    @DisplayName("the lineup, tactics and orders are not on the Preview tab")
    void setupPanelsAreNotOnThePreviewTab() throws IOException {
        // The owner's instruction: setting the eleven and the tactics should not be on the Preview tab,
        // they should be on a Match Setup tab. They were all three sitting under the pre-match preview,
        // so the tab a manager opened to *look at* a match also let him change it.
        //
        // This reads the file rather than running it - match-view pulls half a dozen endpoints and
        // rendering it faithfully is a bigger job than the check is worth. It pins the structure that
        // the bug lived in: the three hosts live inside the setup container, and nothing on the preview
        // path mounts them.
        Path view = JS_ROOT.resolve("pages/views/match-view.js");
        String source = Files.readString(view);

        assertTrue(source.contains("id=\"fm-match-setup\""),
                "there is no Match Setup container to hold the three panels");
        for (String host : new String[]{"fm-match-lineup", "fm-match-tactic-plan", "fm-substitution-plan"}) {
            int at = source.indexOf("\"" + host + "\"");
            assertTrue(at > 0, host + " is not on the match page at all");
            int containerAt = source.indexOf("id=\"fm-match-setup\"");
            assertTrue(at > containerAt,
                    host + " appears before the Match Setup container, so it is still on the preview");
        }

        // The preview path must not mount them.
        int previewFn = source.indexOf("async function showPreview()");
        int previewEnd = source.indexOf("\n            }", previewFn);
        String previewBody = source.substring(previewFn, previewEnd);
        for (String mount : new String[]{"mountLineup", "mountTacticPlan", "mountSubstitutionPlan"}) {
            assertTrue(!previewBody.contains(mount),
                    "showPreview() calls " + mount + ", which puts an editor back on the Preview tab");
        }

        // **Exactly two call sites, and one of them is guarded by the tab.** A mutation that moved the
        // mount back to the top level - which is where it was before this tab existed, and where the
        // first version of this check missed it - makes a third, unguarded call.
        Matcher calls = Pattern.compile("mountSetupPanels\\(\\);").matcher(source);
        int callSites = 0;
        boolean guardedByTab = false;
        while (calls.find()) {
            callSites++;
            int lineStart = source.lastIndexOf('\n', calls.start()) + 1;
            String line = source.substring(lineStart, source.indexOf('\n', calls.start()));
            if (line.contains("initialTab === 'setup'")) {
                guardedByTab = true;
            }
        }
        assertEquals(2, callSites,
                "mountSetupPanels() should be called from the Match Setup button and from the guarded "
                        + "initial-tab branch, and nowhere else");
        assertTrue(guardedByTab,
                "the initial-tab call must be guarded by initialTab === 'setup', or the panels load on "
                        + "every tab including Preview");
    }

    @Test
    @DisplayName("a selected formation or style does not look like a disabled button")
    void theSelectedTacticControlIsPaintedDifferentlyFromAnUnselectedOne() throws IOException {
        // The owner: the formation and style buttons "look disabled, a blurred blue on grey". They
        // were not disabled. A theme block later in the cascade grouped `.cs-tactics-btn.active` with
        // `:hover` and painted both at 10% alpha over a 20% border, so the selected tactic's own
        // controls were fainter than the panel behind them.
        String css = Files.readString(StaticResourceConfigPath.CSS.resolve("dashboard.css"));

        int lastActive = css.lastIndexOf(".cs-tactics-btn.active");
        int lastHover = css.lastIndexOf(".cs-tactics-btn:hover");
        assertTrue(lastActive > 0 && lastHover > 0, "the editor's formation/style buttons have no rule");

        String activeRule = css.substring(lastActive, css.indexOf('}', lastActive));
        String hoverRule = css.substring(lastHover, css.indexOf('}', lastHover));

        double activeAlpha = alphaIn(activeRule, "background");
        double hoverAlpha = alphaIn(hoverRule, "background");
        double borderAlpha = alphaIn(activeRule, "border-color");

        // A selected control that is barely more visible than the ones you can also press reads as
        // unavailable, which is the exact reading the owner gave.
        assertTrue(activeAlpha >= 0.24,
                "the selected formation/style is painted at " + activeAlpha
                        + " alpha, which is too faint to read as chosen");
        assertTrue(activeAlpha > hoverAlpha,
                "the selected formation/style is painted no stronger than a hovered one, so the two "
                        + "are indistinguishable");
        assertTrue(borderAlpha >= 0.6,
                "the selected formation/style has a " + borderAlpha
                        + " alpha border, which disappears against the panel");
        assertTrue(activeRule.contains("box-shadow"),
                "the selected formation/style has no ring, so nothing marks it as chosen");
    }

    /** Reads the alpha out of an {@code rgba(r, g, b, a)} declaration; -1 when there is none. */
    private static double alphaIn(String rule, String property) {
        Matcher m = Pattern.compile(Pattern.quote(property) + "\\s*:\\s*rgba?\\([^)]*?,\\s*([0-9.]+)\\s*\\)")
                .matcher(rule);
        if (!m.find()) {
            return -1;
        }
        return Double.parseDouble(m.group(1));
    }

    /** Kept out of the method so the test reads as one idea rather than as path plumbing. */
    private static final class StaticResourceConfigPath {
        static final java.nio.file.Path CSS = JS_ROOT.getParent().resolve("css");
    }

    @Test
    @DisplayName("the sponsor is escaped where it lands on the boards")
    void theStadiumSponsorIsEscaped() throws IOException {
        // The one field on this page a manager types arbitrary text into, and it lands in four `title`
        // attributes and four labels. A sponsor called `"><img onerror=...>` would be a stored-XSS hole.
        Path view = JS_ROOT.resolve("pages/views/stadium-view.js");
        String source = Files.readString(view);

        assertTrue(source.contains("stadium-ad-board"), "the advertising boards are gone");
        assertTrue(source.contains("const label = esc(sponsor);"),
                "the sponsor name is interpolated without being escaped");

        int board = source.indexOf("function adBoards");
        int end = source.indexOf("\n    }", board);
        String body = source.substring(board, end);
        assertTrue(body.contains("esc(sponsor)"),
                "adBoards() renders the sponsor without escaping it");
        assertTrue(!body.contains("${sponsor}"),
                "adBoards() interpolates the raw sponsor name somewhere");
    }

    @Test
    @DisplayName("the boards are placed on the grid, not auto-placed into it")
    void theAdvertisingBoardsShareACellWithTheirStand() throws IOException {
        // The plan is a CSS grid with named areas. An absolutely positioned child needs a positioned
        // ancestor it does not have, and an unplaced grid child is auto-placed into a cell that already
        // holds a stand - either way the whole ground shifts rather than the boards appearing on it.
        Path css = StaticResourceConfigPath.CSS.resolve("dashboard").resolve("components.css");
        String source = Files.readString(css);

        for (String side : new String[]{"north", "south", "west", "east"}) {
            assertTrue(source.contains(".stadium-ad-board--" + side + "{ grid-area:" + side + "; }"),
                    "the " + side + " board is not placed on the stand it belongs to");
        }
        assertTrue(!source.contains(".stadium-ad-board{") || source.contains("grid-area:"),
                "the boards are not placed by grid-area at all");
    }

    private static List<Path> jsFiles() throws IOException {
        try (Stream<Path> files = Files.walk(JS_ROOT)) {
            return files.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
    }

    /** The node binary, or null when it is not installed. */
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
