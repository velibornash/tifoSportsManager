package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every admin action the panel handles is reachable, and every action it renders is handled.
 *
 * <p><b>What this exists for.</b> Three finished endpoints had a handler in {@code handleTool} and
 * <b>no button anywhere</b>, so no administrator could reach them: seeding the national competitions,
 * resetting the national ratings, and reading the rating violations. A fourth finished endpoint —
 * {@code POST /admin/national-tournaments/advance}, whose own javadoc calls it "the manual counterpart
 * to the week-12 draw job" — had neither a button nor a handler, and was recorded nowhere.
 *
 * <p>Finished code that cannot be reached is the most expensive defect class in this repository. It is
 * expensive because it is invisible: the endpoint answers 200, the service is correct, the javadoc
 * explains what it is for, and no screen leads to it. {@code MatchEventRepository},
 * {@code ConditionalSubstitutionRules}, {@code SubstitutionPlanController}, {@code RankingPointsRebuildService},
 * the medals, the loan offers and the friendly invites were all this shape before they were found.
 *
 * <p><b>Why a source scan, and why that is not a weak check.</b> Both properties are about which strings
 * appear in one shipped file, and neither is observable by rendering: a button that exists but is not
 * wired, and a handler that exists but has no button, both produce a page that looks correct. The scan
 * reads the real file, so it cannot pass against a panel that renders nothing.
 *
 * <p>The two directions are asserted <b>separately and against each other</b>. One direction is the whole
 * bug — a handler with no button. The other is the mirror: a button whose action falls through
 * {@code handleTool} silently does nothing when clicked, which looks exactly like a working button.
 */
class AdminActionsAreReachableTest {

    private static final Path ADMIN_VIEW =
            Path.of("src/main/resources/static/js/pages/views/admin-view.js");

    /** `if (action === 'x')` — the dispatch at the top of `handleTool`. */
    private static final Pattern HANDLED = Pattern.compile("action\\s*===\\s*'([a-z0-9-]+)'");

    /** Every `data-admin-action="x"` the file emits, however it emits it. */
    private static final Pattern EMITTED =
            Pattern.compile("data-admin-action=\"([a-z0-9-]+)\"");

    /** `action: 'x'` — the `toolCard` helper's field. */
    private static final Pattern TOOL_CARD_ACTION = Pattern.compile("action:\\s*'([a-z0-9-]+)'");

    /**
     * Actions that are legitimately handled but rendered somewhere other than a tool card, so their
     * button is emitted by a different block. Each is named with where it lives rather than excused by
     * a wildcard, because a wildcard is how the next orphan hides.
     */
    private static final Set<String> HANDLED_ELSEWHERE = Set.of(
            "initialize",        // dispatched by handleDatabaseAction, not handleTool
            "refresh-jobs",      // emitted inline in the Jobs toolbar
            "run-due-jobs",      // emitted inline in the Jobs toolbar
            "restore-backup",    // emitted per row in the backups table, after the panel's own pass
            "approve",           // emitted per row in the registration queue
            "reject"             // emitted per row in the registration queue
    );

    @Test
    @DisplayName("every admin action the panel handles has a button")
    void handledActionsAreRendered() throws IOException {
        Set<String> rendered = emittedActions();

        Set<String> orphans = new LinkedHashSet<>();
        for (String action : handledActions()) {
            if (rendered.contains(action)) continue;
            if (HANDLED_ELSEWHERE.contains(action)) continue;
            orphans.add(action);
        }

        assertTrue(orphans.isEmpty(),
                "these actions are handled but no button renders them: " + orphans
                        + "\n\nA handler with no button is unreachable code. The endpoint exists, the "
                        + "service is correct, and no administrator can reach any of it. If one of these "
                        + "is meant to be reached from somewhere other than the Tools tab, name it in "
                        + "HANDLED_ELSEWHERE with where it lives — do not delete the assertion.");
    }

    @Test
    @DisplayName("every button the panel renders has a handler")
    void renderedActionsAreHandled() throws IOException {
        Set<String> handled = handledActions();
        // handleDatabaseAction covers 'reset' and 'initialize'; the approval queue has its own path.
        handled.add("reset");
        handled.add("initialize");
        handled.add("approve");
        handled.add("reject");

        Set<String> dead = new LinkedHashSet<>();
        for (String action : emittedActions()) {
            if (!handled.contains(action)) dead.add(action);
        }

        assertTrue(dead.isEmpty(),
                "these buttons render but no handler acts on them: " + dead
                        + "\n\nA button whose action falls through handleTool does nothing when clicked, "
                        + "which is indistinguishable from a working one until an administrator reports "
                        + "that it is dead.");
    }

    @Test
    @DisplayName("the rating-violation handler asks the path that exists")
    void theViolationHandlerCallsTheRealPath() throws IOException {
        String src = code();

        // It asked `/admin/national-ratings/violations`; the route is `/offenders`. And it read the
        // answer as `v.violations || v.length || 'none'` against a response shaped
        // `{ startingRating, offenders: [...] }` — so both terms were undefined and it printed "none"
        // with offenders on the board. A diagnostic that reports all clear while the data disagrees
        // is worse than no diagnostic.
        //
        // Read with the comments stripped, and that is the whole point rather than a detail. The first
        // version of this assertion matched the sentence inside the handler's own javadoc, which quotes
        // the wrong path precisely in order to explain why it was wrong — so fixing the code broke the
        // guard. This repository has already lost a day to exactly that: P0-RANK-4's "no head-to-head"
        // guard failed on `RankingPointsEngine`'s own documentation, and the fix was to strip comments
        // rather than to delete the explanation. A guard that breaks when somebody documents why a
        // defect was fixed is a guard that gets deleted to let the documentation land.
        assertFalse(src.contains("/admin/national-ratings/violations"),
                "the handler calls a path that does not exist; the route is /admin/national-ratings/offenders");
        assertTrue(src.contains("/admin/national-ratings/offenders"),
                "and it must call the real one, or the panel has no read of the ratings at all");
        assertTrue(src.contains("body.offenders"),
                "and it must read the field the endpoint actually sends. `offenders()` returns a list "
                        + "of strings under `offenders`, so anything else reads as an empty world.");
    }

    @Test
    @DisplayName("the tool-group count is read from the markup, not written down")
    void theToolGroupCountIsDerived() throws IOException {
        String src = code();

        // It said "4" and there were seven panels before this panel was added. The same drift as the
        // academy limit that was hardcoded four times in academy.js: a number nobody recomputes.
        assertFalse(Pattern.compile("<span>Tool groups</span><strong>\\d").matcher(src).find(),
                "the tool-group count must not be a literal in the markup — it goes stale the moment a "
                        + "panel is added, and it was stale for three panels before anyone looked");
        assertTrue(src.contains("data-admin-tool-groups"),
                "and the cell must be marked so it can be filled in from the rendered panel count");
        assertTrue(src.contains("[data-admin-panel=\"tools\"] > .fm-panel"),
                "counting the panels means counting the markup, so the selector has to scope to the "
                        + "Tools tab rather than to the whole page — the Jobs tab has panels too");
    }

    // ── parsing ─────────────────────────────────────────────────────────────────────────────────────

    private String read() throws IOException {
        return Files.readString(ADMIN_VIEW, StandardCharsets.UTF_8);
    }

    /**
     * The file with its comments removed.
     *
     * <p>String literals are preserved: a quoted path inside a string is code, and stripping quotes as
     * well would hide a real defect. Block comments go first so that a {@code //} inside one cannot
     * confuse the line pass.
     */
    private String code() throws IOException {
        String src = read();
        src = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(src).replaceAll(" ");
        src = Pattern.compile("(?m)^\\s*//.*$").matcher(src).replaceAll("");
        src = Pattern.compile("(?m)[ \\t]+//.*$").matcher(src).replaceAll("");
        return src;
    }

    private Set<String> handledActions() throws IOException {
        return collect(HANDLED);
    }

    /** Both the inline buttons and the `toolCard` helper's `action:` field. */
    private Set<String> emittedActions() throws IOException {
        Set<String> out = collect(EMITTED);
        out.addAll(collect(TOOL_CARD_ACTION));
        return out;
    }

    private Set<String> collect(Pattern pattern) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(code());
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}