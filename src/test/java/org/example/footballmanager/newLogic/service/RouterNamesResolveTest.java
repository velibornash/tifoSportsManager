package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The router and the legacy globals it publishes both resolve to something real.
 *
 * <p><b>What this task found.</b> {@code loadPage}'s switch carried 47 route names. Four had **no caller
 * anywhere in the application**, and two of them were duplicates that rendered something other than their
 * name promised:
 *
 * <ul>
 *   <li>{@code teamStats} called the <em>assists</em> loader, and {@code describePage} called it "team
 *       statistics" — it was a second copy of {@code topAssists}</li>
 *   <li>{@code playerStats} called the <em>scorers</em> loader — a second copy of {@code topScorers}</li>
 *   <li>{@code friendlies} called a loader that the club page's fully-wired friendly panel already
 *       replaced</li>
 *   <li>{@code analytics} redirected to a standalone page that duplicates what the match view already
 *       fetches inline, and that nothing links to</li>
 * </ul>
 *
 * <p><b>And the removal nearly caused a new crash of the same class this repository has already paid
 * for twice.</b> {@code pages.js} publishes ~60 functions on {@code window} for legacy callers. Deleting
 * {@code loadFriendlies} and {@code loadAnalytics} left {@code window.loadFriendlies = loadFriendlies} and
 * {@code window.loadAnalytics = loadAnalytics} pointing at nothing — a {@code ReferenceError} thrown while
 * the module loads, on every page. That is the same shape as the country page, where
 * {@code renderRepresentedCountry} was called at line 759 and defined nowhere, and it was introduced
 * twice in one session by an agent who deleted a function without looking at what referenced it.
 *
 * <p><b>Why a source scan.</b> This repository has <b>no JavaScript test infrastructure at all</b> — no
 * runner, no {@code *.test.js}. Executing the router needs a browser and a server. The properties here are
 * about which names appear next to which in the shipped files, and a scan of the real files cannot pass
 * against a router that renders nothing.
 */
class RouterNamesResolveTest {

    private static final Path PAGES = Path.of("src/main/resources/static/js/pages.js");
    private static final Path JS_ROOT = Path.of("src/main/resources/static/js");

    private static final Pattern ROUTE_CASE = Pattern.compile("case\\s+\"([A-Za-z0-9_]+)\"");

    /** `window.name = ...` — the legacy globals pages.js publishes. */
    private static final Pattern WINDOW_GLOBAL = Pattern.compile("window\\.(\\w+)\\s*=\\s*([^;]+);");

    private static final Pattern FUNCTION_DEF = Pattern.compile("(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern IMPORTED = Pattern.compile("import\\s*\\{([^}]*)}\\s*from");

    /**
     * Routes with no caller that are nevertheless real entry points.
     *
     * <p>Each is named with <b>where it is entered from</b>, because an allowlist without a reason is how the
     * next orphan hides — the same objection {@code HANDLED_ELSEWHERE} in
     * {@code AdminActionsAreReachableTest} exists to raise.
     */
    private static final Set<String> ENTRY_POINTS = Set.of(
            // Typed into the browser console by the owner while debugging.
            "dashboard",
            // A second path to the national-team screen, which the country page also reaches as a tab.
            // Kept because a shared link or a bookmark is a real way in, and deleting a route does not
            // un-bookmark it: the manager gets "API Error" instead of the screen.
            "nationalTeam",
            "u21Team"
    );

    @Test
    @DisplayName("every route in the switch can be reached from somewhere")
    void everyRouteIsReachable() throws IOException {
        Set<String> routes = routes();
        assertTrue(routes.size() > 30, "the switch should carry the whole application, found " + routes.size());

        // The case labels are removed first. They are not a caller: a route whose only mention in the
        // whole application is its own `case` is an orphan, and leaving the labels in made every route
        // trivially "reachable" - which is how the first version of this assertion passed against a
        // route called `legacyArchive` that nothing anywhere navigated to.
        String pagesSource = read(PAGES).replaceAll("case\s+\"[A-Za-z0-9_]+\"\s*:", "");
        Set<String> elsewhere = otherSources();

        List<String> orphans = new ArrayList<>();
        for (String route : routes) {
            if (ENTRY_POINTS.contains(route)) continue;
            if (pagesSource.contains("'" + route + "'") || pagesSource.contains("\"" + route + "\"")) {
                // Named somewhere in its own file outside the switch: a data- attribute, a menu entry.
                continue;
            }
            if (elsewhere.contains(route)) continue;
            orphans.add(route);
        }

        assertEquals(List.of(), orphans,
                "these routes are in the loadPage switch and nothing navigates to them: " + orphans
                        + "\n\nA route name in the switch is not a screen. Two of the four found by this "
                        + "task were duplicates of a live route that rendered something other than their "
                        + "name promised - `teamStats` drew the assists table and was described as 'team "
                        + "statistics'. If one of these is a real entry point, name it in ENTRY_POINTS with "
                        + "where it is entered from; do not delete the assertion.");
    }

    @Test
    @DisplayName("every window global pages.js publishes points at something that exists")
    void noDanglingWindowGlobal() throws IOException {
        String src = read(PAGES);

        Set<String> inScope = definedNames(src);
        List<String> dangling = new ArrayList<>();

        Matcher m = WINDOW_GLOBAL.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            String rhs = m.group(2).trim();
            // Only the simple `window.x = x` form can dangle. `window.x = (...args) => view.y(...)`
            // calls through an imported view object and is not a bare reference.
            if (!rhs.matches("\\w+")) continue;
            if (!inScope.contains(rhs)) dangling.add("window." + name + " = " + rhs);
        }

        assertEquals(List.of(), dangling,
                "these globals are published but the thing on the right does not exist: " + dangling
                        + "\n\nAssigning a removed function to a window property throws a ReferenceError "
                        + "while the module loads — on every page, not on the route that used it. This is "
                        + "the same class as the country page, where renderRepresentedCountry was called at "
                        + "line 759 and defined nowhere.");
    }

    @Test
    @DisplayName("the page-name table describes only routes that exist")
    void pageNamesMatchTheSwitch() throws IOException {
        String src = read(PAGES);
        Set<String> routes = routes();

        // PAGE_NAMES is the object literal describePage reads.
        int start = src.indexOf("const PAGE_NAMES");
        assertTrue(start > 0, "PAGE_NAMES must exist: describePage is what turns a route name into words on "
                + "the error card, and without it every load failure says 'page'");
        int open = src.indexOf('{', start);
        int depth = 0;
        int end = open;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        String table = src.substring(open, end);

        List<String> orphans = new ArrayList<>();
        Matcher m = Pattern.compile("^\\s*(\\w+):", Pattern.MULTILINE).matcher(table);
        while (m.find()) {
            String key = m.group(1);
            if (!routes.contains(key)) orphans.add(key);
        }

        assertEquals(List.of(), orphans,
                "PAGE_NAMES describes routes that are not in the switch: " + orphans
                        + "\n\nA name in the table with no case behind it is a route that was deleted and "
                        + "its description was left behind — which is how `teamStats` came to be described "
                        + "as 'team statistics' for a screen that drew the assists table.");
    }

    // ── parsing ─────────────────────────────────────────────────────────────────────────────────────

    private Set<String> routes() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ROUTE_CASE.matcher(stripComments(read(PAGES)));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Every name pages.js can see: its own functions, its own consts, and everything it imports.
     *
     * <p>The imports matter. A first version of the dangling-global check looked only at local
     * definitions and reported five false alarms on names that were perfectly well imported — the same
     * "assume, then verify" error this repository records in most of its entries.
     */
    private Set<String> definedNames(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher fn = FUNCTION_DEF.matcher(src);
        while (fn.find()) out.add(fn.group(1));
        Matcher cst = Pattern.compile("(?:const|let)\\s+(\\w+)\\s*=").matcher(src);
        while (cst.find()) out.add(cst.group(1));
        Matcher imp = IMPORTED.matcher(src);
        while (imp.find()) {
            for (String part : imp.group(1).split(",")) {
                String name = part.trim().split("\\s+as\\s+").length > 1
                        ? part.trim().split("\\s+as\\s+")[1]
                        : part.trim();
                if (!name.isEmpty()) out.add(name);
            }
        }
        return out;
    }

    /** Every string literal in the other application files — the names that can be navigated to. */
    private Set<String> otherSources() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(JS_ROOT)) {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".js"))
                    .filter(f -> !f.toString().contains("/demo/"))
                    .filter(f -> !f.endsWith("pages.js"))
                    .toList()) {
                String src = stripComments(read(p));
                Matcher m = Pattern.compile("'([A-Za-z0-9_]+)'|\"([A-Za-z0-9_]+)\"").matcher(src);
                while (m.find()) {
                    String v = m.group(1) != null ? m.group(1) : m.group(2);
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Comments removed, so a route named only in a javadoc does not count as reachable. */
    private static String stripComments(String src) {
        return Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(src)
                .replaceAll(" ")
                .replaceAll("(?m)^\\s*//.*$", "")
                .replaceAll("(?m)[ \\t]+//.*$", "");
    }
}