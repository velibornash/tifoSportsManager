package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things the owner reported about the World page (2026-09-30).
 *
 * <p>None of these is a Java defect, so none of them is reachable from a Spring test. They are all
 * reachable from a text file, which is what this is: a menu that lost an entry, an argument dropped
 * between two functions, and a button that works perfectly and cannot be reached.
 *
 * <p>The third is the reason this file exists at all. "Back to dashboard does not work" turned out to
 * be false in the only sense a developer would check — the click handler fired, the navigation
 * happened, and it worked on a desktop. On a phone the page was 3,227px tall and after 1,200px of
 * scroll the button measured {@code top: -1095}: entirely off-screen. A test that asserted "the button
 * is on the page" would have passed throughout.
 */
class WorldPageNavigationTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path PAGES_JS = STATIC.resolve("js/pages.js");
    private static final Path COUNTRY_VIEW_JS = STATIC.resolve("js/pages/views/country-view.js");
    private static final Path DASHBOARD_HTML = STATIC.resolve("dashboard.html");
    private static final Path DASHBOARD_CSS = STATIC.resolve("css/dashboard.css");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    /** The text between two markers, or empty when the markers are not where this test assumed. */
    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf(to, start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    @Test
    @DisplayName("the mobile menu has a World entry, not only the desktop one")
    void worldIsInTheMobileMenu() {
        String html = read(DASHBOARD_HTML);

        assertTrue(html.contains("loadPage('world')"),
                "the dashboard has no World entry at all");

        // The desktop bar is `desktop-only` and the drawer is `mobile-only`, so the World entry has to
        // exist inside the drawer block or it is invisible on a phone — which is exactly what happened.
        String drawer = between(html, "<div id=\"mobileSidebar\"", "</div>\n        </div>\n\n        <div class=\"mobile-accordion mobile-user-block\">");
        assertFalse(drawer.isEmpty(), "could not find the mobile drawer block");
        assertTrue(drawer.contains("loadPage('world')"),
                "World is missing from the mobile drawer. The desktop bar is desktop-only, so on a "
                        + "phone the page was unreachable — reported by the owner");
    }

    @Test
    @DisplayName("the router's options reach the country view")
    void countryPageOptionsAreNotDropped() {
        String pages = read(PAGES_JS);
        String view = read(COUNTRY_VIEW_JS);

        // The wrapper took no arguments and passed none, so the world page's "this country is
        // represented, not played" note was set at the call site and discarded one frame later. The
        // option existed in three of the four places between the click and the screen.
        assertTrue(pages.contains("await loadCountryPage(options)"),
                "the router passes options to the country page but the wrapper drops them: the "
                        + "simulated-country note can never be rendered");
        assertTrue(Pattern.compile("countryView\\.loadCountryPage\\(\\s*options")
                .matcher(pages).find(),
                "the wrapper must forward its options to the view");
        assertTrue(view.contains("simulatedCountry"),
                "the country view no longer knows about a represented country");
    }

    @Test
    @DisplayName("the country page reads the selected country, not only the manager's own")
    void countryPageHonoursTheWorldSelection() {
        String pages = read(PAGES_JS);

        // It asked for the manager's own country unconditionally, so every row in the world — Croatia,
        // Japan, Brazil — showed Serbia. The world page set the league context and this ignored it.
        assertTrue(pages.contains("getCurrentUserCountryIsoCode: () => resolveCountryIsoCode()"),
                "the country view is still handed the manager's own country, so every country in the "
                        + "world shows the manager's own side");
        assertTrue(pages.contains("function resolveCountryIsoCode()"),
                "there is no resolver for 'the country being looked at'");
    }

    @Test
    @DisplayName("the world page keeps its exit button reachable on a phone")
    void theBackButtonStaysReachable() {
        String css = read(DASHBOARD_CSS);

        // `position: sticky` is the obvious answer and it silently does nothing in this shell: <body>
        // carries `overflow: hidden auto` with scrollHeight === clientHeight, so it is a scroll
        // container that cannot scroll, and a sticky element is confined to its scrollport. Measured at
        // scroll 700: sticky gave top: -587, fixed gave top: 0.
        assertTrue(css.contains("position: fixed"),
                "nothing on the world page is fixed, so the only exit scrolls out of reach");
        // Retargeted 2026-10-01, and the reason matters. The World page's header was rebuilt to match
        // the Country tab — title left, Back hard right — which replaced `.fm-page-toolbar` with
        // `.fm-country-header`. This assertion was on the old class name and failed, which is the whole
        // point of it: a dead selector on a dead element is how the off-screen button came back once.
        //
        // It must therefore name the header the page now uses, and still assert the scoping, because
        // scoping is the part that protects every other page in the game.
        assertTrue(css.contains(".fm-page--world .fm-country-header"),
                "the fixed bar must be scoped to the world page. A fixed bar on every page in the game "
                        + "is a change nobody asked for, and this selector no longer matches anything "
                        + "the world page renders");
        assertTrue(css.contains("max-height"),
                "the 48-row list is not capped, so the page is 3,000px tall and the exit is a scroll "
                        + "away even when it is fixed");

        // The bar must not eat taps meant for the page behind it.
        assertTrue(css.contains("pointer-events: none"),
                "a fixed bar across the top of the page will swallow taps on whatever is under it");

        // And the markup has to use that header, or the rule above styles nothing at all.
        assertTrue(read(PAGES_JS).contains("fm-page--world"),
                "the world page is not tagged fm-page--world, so no world-scoped rule can reach it");
        assertTrue(read(PAGES_JS).contains("fm-country-header-back"),
                "the world page's header has no back button, so the exit this whole test defends is gone");
    }

    @Test
    @DisplayName("the country list is ranked and the whole row is the link")
    void theCountryListIsRankedAndTheRowIsTheLink() {
        String pages = read(PAGES_JS);

        // The ordinal is the reason this table exists. Without it the list is 48 names in alphabetical
        // order beside a column of ratings, which is a list and not a ranking.
        assertTrue(pages.contains("const ranked = [...countries].sort"),
                "the country list is not sorted by rating, so the position column would be arbitrary");
        assertTrue(pages.contains("<td class=\"st-pos\">${index + 1}</td>"),
                "no ordinal in the row: the country ranking has no position column");

        // The row carries the target, not a button wrapped around the name. A button inside the name is
        // what made this read as a list of names, and it is also why the row could not be tabbed to.
        assertTrue(pages.contains("js-load-world-country"),
                "the country row is not marked as the clickable row");
        assertFalse(pages.contains("<button type=\"button\" class=\"fm-link\" data-world-country="),
                "the country name is wrapped in a button again, so the row is a list of names rather than "
                        + "a ranking table");
        assertTrue(pages.contains("tabindex=\"0\" role=\"link\""),
                "the country row is not reachable by keyboard, so it is clickable but not operable");

        // And the keyboard case has to actually do something, or the tabindex is decoration.
        assertTrue(pages.contains("event.key === 'Enter'"),
                "the country row handles no key, so tabbing to it and pressing Enter does nothing");
    }

    @Test
    @DisplayName("show results opens the goals, not the pre-match preview")
    void showResultsOpensTheResultAndNotThePreview() {
        String reveal = read(STATIC.resolve("js/reveal-ui.js"));
        String matchView = read(STATIC.resolve("js/pages/views/match-view.js"));

        // The caller's request...
        assertTrue(reveal.contains("initialTab: 'goals'"),
                "Show results no longer asks for the goals tab");

        // ...and the callee honouring it. It used to be a two-way test on one tab:
        // `options.initialTab === 'report' ? 'report' : 'preview'`, so every other request - including
        // 'goals' - silently became Preview. A manager who pressed Show results landed on the
        // pre-match screen, which is the one screen a result should never open on.
        // Anchored on the statement starting the line, so the sentence *about* the old expression - which
        // is in the comment right above it, deliberately, so the next reader knows what changed - does not
        // satisfy this check. A substring match on the expression alone matched that comment and failed a
        // correct implementation, which is the mirror of the bug this codebase has already paid for once:
        // a scan that counts a mention.
        assertFalse(matchView.contains("\n        const initialTab = options.initialTab === 'report'"),
                "the match view still defaults every unrecognised tab to Preview");
        assertTrue(matchView.contains("MATCH_TABS.includes(requestedTab)"),
                "the requested tab is not validated against the three that exist, so a typo or a fourth "
                        + "tab would land on Preview");
        for (String tab : new String[]{"preview", "goals", "report"}) {
            assertTrue(matchView.contains("'" + tab + "'"),
                    "the tab list does not mention '" + tab + "'");
        }
    }

    @Test
    @DisplayName("watch your match still opens the replay viewer")
    void watchYourMatchStillWatchesTheMatch() {
        String reveal = read(STATIC.resolve("js/reveal-ui.js"));

        // Deliberately unchanged, and pinned because the previous commit came close to redirecting it.
        // "Watch your match" opens the viewer so you watch the game happen; "Show results" opens the
        // match page on the goals. They are two different questions and they were briefly at risk of
        // being merged.
        assertTrue(reveal.contains("VIEWER_PATH"),
                "Watch your match no longer opens the viewer");
        assertTrue(reveal.contains("window.location.href = `${VIEWER_PATH}"),
                "Watch your match does not navigate to the viewer path");
    }

    @Test
    @DisplayName("the league table shows a rating and its movement")
    void theLeagueTableShowsRatingAndMovement() {
        String renderers = read(STATIC.resolve("js/pages-renderers.js"));

        // Headers and cells, or one of them is a column of nothing. `node --check` cannot see this and a
        // MockMvc test cannot either - they both pass with a header and no <td>.
        assertTrue(renderers.contains("<th class=\"st-rating\""),
                "the league table has no Elo header");
        assertTrue(renderers.contains("<th class=\"st-delta\""),
                "the league table has no movement header");
        assertTrue(renderers.contains("<td class=\"st-rating\">${CTeam.rating"),
                "the league table row does not render the rating");
        assertTrue(renderers.contains("formatRatingDelta(CTeam.ratingDelta)"),
                "the league table row does not render the movement");
    }
}
