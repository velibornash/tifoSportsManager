package org.tifo.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The recent matches' own lines start below the label floating over them, on a desktop - and the phone
 * is left alone.
 *
 * <p>The owner, 2026-10-23: <i>"Recent matches rec na widgetu je potpuno prekrila datum i vreme na
 * desktopu: spusti datum za jedan red i imena timova za jedan red i tip meca za jedan red ... a desno
 * ispod logoa isto imamo datum? jedan je visak ... na mobilnom izgleda ok"</i>.
 *
 * <p><b>What was covering what.</b> Since 2026-10-19 the section's label has floated over the top edge of
 * its first card - absolutely positioned at {@code top: -11px}, {@code z-index: 2} - so the card starts
 * on the same line as the tiles beside it. The card never reserved room for it: its padding-top is 12px,
 * so its first line began at y=349 while the label's pill ran from 341 to 363 and the crest from 312 to
 * 366. The title lay straight across the date (only its tail, <i>"3 · 19:00"</i>, showed to the right)
 * and the crest covered the end of the compact date under it. The fix moves the card's lines down one
 * line of its own rhythm and drops the second date - both in a {@code min-width: 1025px} block, because
 * on a phone the label is in the flow above the card and there is nothing over it.
 *
 * <p><b>Why geometry, from the page's own stylesheet.</b> "Covered" is a question about boxes, not about
 * declarations: a rule can be present and still lose to a later one, and a rule can move without
 * anybody noticing. This loads {@code dashboard.css} and {@code dashboard/overrides.css} in the shipped
 * order, builds the widget's real markup, and asks where the boxes land. It fails if the spacing is
 * taken away, and it fails if the two dates come back.
 */
class RecentMatchesLinesClearTheFloatingLabelTest {

    private static final int DESKTOP_WIDTH = 1440;
    private static final int DESKTOP_HEIGHT = 900;
    private static final int PHONE_WIDTH = 390;
    private static final int PHONE_HEIGHT = 844;

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launch() {
        Path css = Path.of("src/main/resources/static/css/dashboard.css");
        assumeTrue(Files.exists(css), "dashboard.css not on disk - run from the project root");
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            // No browser binary here. The geometry below is the point of this class, so it is skipped
            // rather than passed: "green" must never mean "the developer has no Chromium".
            assumeTrue(false, "Chromium unavailable: " + e.getMessage());
        }
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    @DisplayName("the date, the team names and the competition each sit below the label and the crest")
    void theLinesClearTheLabel() throws Exception {
        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setViewportSize(DESKTOP_WIDTH, DESKTOP_HEIGHT))) {
            Page page = context.newPage();
            page.setContent(widgetHtml());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> violations =
                    (List<Map<String, Object>>) (Object) page.evaluate("""
                    () => {
                        const label = document.querySelector('.fm-dashboard-section-title').getBoundingClientRect();
                        const crest = document.querySelector('.fm-dashboard-section--recent .fm-dashboard-crest').getBoundingClientRect();
                        const covers = (a, b) => !(a.bottom <= b.top || a.top >= b.bottom
                                || a.right <= b.left || a.left >= b.right);
                        const bad = [];
                        for (const sel of ['.fx-date', '.fx-main', '.fx-competition-type']) {
                            const el = document.querySelector('.fm-dashboard-section--recent ' + sel);
                            if (!el) { bad.push({ line: sel, missing: true }); continue; }
                            const r = el.getBoundingClientRect();
                            if (covers(r, label)) bad.push({ line: sel, coveredBy: 'the Recent Matches label',
                                lineTop: Math.round(r.top), labelBottom: Math.round(label.bottom) });
                            if (covers(r, crest)) bad.push({ line: sel, coveredBy: 'the club crest',
                                lineTop: Math.round(r.top), crestBottom: Math.round(crest.bottom) });
                        }
                        const date = document.querySelector('.fm-dashboard-section--recent .fx-date').getBoundingClientRect();
                        if (date.top < label.bottom) bad.push({ line: '.fx-date',
                            reason: 'the date starts above the bottom of the label',
                            dateTop: Math.round(date.top), labelBottom: Math.round(label.bottom) });
                        return bad;
                    }
                    """);

            assertTrue(violations.isEmpty(),
                    () -> "On a " + DESKTOP_WIDTH + "px desktop the label or the crest still sits over a "
                            + "line of the recent matches card:\n  " + violations);
        }
    }

    @Test
    @DisplayName("the card carries one date on a desktop, not two")
    void theSecondDateIsGone() throws Exception {
        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setViewportSize(DESKTOP_WIDTH, DESKTOP_HEIGHT))) {
            Page page = context.newPage();
            page.setContent(widgetHtml());

            String date = page.textContent(".fm-dashboard-section--recent .fx-date");
            String display = String.valueOf(page.evaluate(
                    "getComputedStyle(document.querySelector('.fm-dashboard-section--recent .fx-calendar')).display"));

            assertEquals("none", display,
                    "the compact date (\"S1 · D3 · Wk 1 · Rd 1\") is back under the crest on a desktop, "
                            + "and it says again what the full line already says: " + date);
        }
    }

    @Test
    @DisplayName("the phone keeps its own spacing and its second date")
    void thePhoneIsUntouched() throws Exception {
        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
                        .setDeviceScaleFactor(3)
                        .setIsMobile(true))) {
            Page page = context.newPage();
            page.setContent(widgetHtml());

            String padding = String.valueOf(page.evaluate(
                    "getComputedStyle(document.querySelector('.fm-dashboard-section--recent .fm-fixture')).paddingTop"));
            String calendar = String.valueOf(page.evaluate(
                    "getComputedStyle(document.querySelector('.fm-dashboard-section--recent .fx-calendar')).display"));
            @SuppressWarnings("unchecked")
            Map<String, Object> layout = (Map<String, Object>) (Object) page.evaluate("""
                    () => {
                        const label = document.querySelector('.fm-dashboard-section-title').getBoundingClientRect();
                        const date = document.querySelector('.fm-dashboard-section--recent .fx-date').getBoundingClientRect();
                        return { labelBottom: Math.round(label.bottom), dateTop: Math.round(date.top) };
                    }
                    """);

            assertEquals("12px", padding,
                    "the desktop's 36px of top padding has reached the phone, where the label floats over nothing");
            assertEquals("block", calendar, "the phone lost its second date - the owner says that side is right");
            assertTrue(((Number) layout.get("dateTop")).intValue() >= ((Number) layout.get("labelBottom")).intValue(),
                    "on a phone the label is in the flow, so the date must start below it: " + layout);
        }
    }

    /**
     * The widget as the app writes it: the dashboard's own wrappers, the section head with its title and
     * crest, and one fixture card with all four lines - including the competition type, which the card
     * with a hidden result does not render and which the owner nevertheless asked to have moved.
     *
     * <p>The stylesheet is inlined in the shipped order, because the responsive overrides live in
     * {@code dashboard/overrides.css} while {@code dashboard.css}'s own rules come <i>after</i> its
     * imports - and the order is what decides which of two rules wins.
     */
    private String widgetHtml() throws Exception {
        String dashboard = Files.readString(Path.of("src/main/resources/static/css/dashboard.css"));
        String overrides = Files.readString(Path.of("src/main/resources/static/css/dashboard/overrides.css"));

        return """
                <!doctype html>
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>%s</style><style>%s</style></head>
                <body>
                  <div class="dashboard-content">
                    <div>
                      <div class="fm-dashboard-view">
                        <div class="fm-dashboard-main">
                          <section class="fm-dashboard-section fm-dashboard-section--recent">
                            <div class="fm-dashboard-section-head">
                              <h3 class="fm-dashboard-section-title">Recent Matches</h3>
                              <div class="fm-dashboard-crest">
                                <img src="/images/default-team.png" class="team-logo" alt="">
                              </div>
                            </div>
                            <div class="fm-dashboard-cards fm-dashboard-cards--single">
                              <div class="fm-fixtures">
                                <div class="fm-fixture fm-fixture--league" data-match-id="3">
                                  <div class="fx-topline">
                                    <span class="fx-date">Season 1 &middot; Week 1 &middot; Day 3 &middot; 19:00</span>
                                    <span class="fx-calendar">S1 &middot; D3 &middot; Wk 1 &middot; Rd 1</span>
                                    <span class="fx-venue">OFK Omladinac Stadium</span>
                                  </div>
                                  <div class="fx-main">
                                    <span class="fx-home">OFK Omladinac</span>
                                    <span class="fx-score pending">Result not shown yet</span>
                                    <span class="fx-away">ZFK Pobeda 1913</span>
                                  </div>
                                  <div class="fx-competition-type">League</div>
                                  <div class="fx-result-chip"><span class="result-badge win">1</span></div>
                                </div>
                              </div>
                            </div>
                          </section>
                        </div>
                      </div>
                    </div>
                  </div>
                </body></html>
                """
                .formatted(dashboard, overrides);
    }
}
