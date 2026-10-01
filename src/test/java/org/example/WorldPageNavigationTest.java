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
}
