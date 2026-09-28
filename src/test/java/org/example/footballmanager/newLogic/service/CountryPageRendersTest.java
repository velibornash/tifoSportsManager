package org.example.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The country page must actually render.
 *
 * <p>Exists because a page can pass every static check and still be broken. The schedule panel added
 * to the country page called an escaping function that is not in that file's scope — a name that
 * exists elsewhere in the project, so it looked right — and the page rendered its
 * "Could not load your country overview" error instead. {@code node --check} passed, the endpoint
 * returned 200, and the bug was found by a person clicking the tab.
 *
 * <p>So: a real browser, a real login, and the page's own text on screen. This is the only kind of
 * check that catches an undefined identifier in a template string.
 */
class CountryPageRendersTest {

    @Test
    @SuppressWarnings("unchecked")
    @org.junit.jupiter.api.DisplayName("the country page renders, and the week shows every day")
    void countryPageRendersWithTheSchedule() throws Exception {
        Playwright pw;
        Browser browser;
        try {
            pw = Playwright.create();
            browser = pw.chromium().launch(
                    new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            assumeTrue(false, "Chromium unavailable: " + e.getMessage());
            return;
        }

        BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setViewportSize(1280, 900));
        Page page = context.newPage();

        List<String> consoleErrors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(e -> pageErrors.add(String.valueOf(e)));

        try {
            // Skip rather than fail when nothing is on :8080. The rest of the suite runs headless,
            // and a test that reports ERR_CONNECTION_REFUSED as an error teaches people to ignore
            // errors - the same failure mode as the flaky test fixed earlier.
            try {
                page.navigate("http://localhost:8080/login.html");
            } catch (Exception e) {
                assumeTrue(false, "application is not running on :8080 - skipping the live check");
                return;
            }
            assumeTrue(page.content().contains("loginBtn"), "the login page did not render");
            page.fill("input[name='email']", "velibor@example.com");
            page.fill("input[name='password']", "A12345!");
            page.click("#loginBtn");
            page.waitForTimeout(4000);

            page.navigate("http://localhost:8080/dashboard.html");
            page.waitForTimeout(3500);
            // Go straight to the country page. Clicking through the sidebar and then going back was
            // a timeout waiting to happen, and it tested navigation rather than the thing under test.
            page.evaluate("() => { if (window.loadPage) window.loadPage('country'); }");
            page.waitForTimeout(3000);

            String body = page.content();
            assertTrue(!body.contains("Could not load your country overview"),
                    "the country page rendered its error card. Page errors: " + pageErrors);
            assertTrue(body.contains("Serbia"),
                    "the country page should name the manager's own country");

            // The schedule lives on the Calendar tab now (owner, 2026-09-28), so this has to open it.
            // Asserting the week grid on the default tab was passing before the split and failing
            // after it, which is the test doing its job - it was the test that was wrong.
            page.click("button[data-country-tab='calendar']");
            // Default Playwright timeout is fine here: the tab click re-renders from cached payloads.
            page.waitForSelector(".fm-week-strip");
            String calendarBody = page.content();

            // Seven days, each named. If the schedule silently failed to load, the grid is absent and
            // this is what notices.
            for (String day : List.of("Day 1", "Day 2", "Day 3", "Day 4", "Day 5", "Day 6", "Day 7")) {
                assertTrue(calendarBody.contains(day), "the week grid is missing " + day);
            }
            for (String kind : List.of("International", "Finance update", "League", "Training", "Cup",
                    "Form &amp; morale")) {
                assertTrue(calendarBody.contains(kind), "the week grid is missing the " + kind + " day");
            }

            // The four country cards must be capped, not spread. The owner reported them still
            // stretching after an earlier fix that had the wrong specificity and lost on source order.
            Object cardWidth = page.evaluate(
                    "() => { const g = document.querySelector('.fm-country-hero .fm-country-stat-grid');"
                    + " if (!g) return null; const c = g.querySelector('div');"
                    + " return { card: Math.round(c.getBoundingClientRect().width),"
                    + "          grid: Math.round(g.getBoundingClientRect().width) }; }");
            assertTrue(cardWidth != null, "the country stat cards were not found");
            @SuppressWarnings("unchecked")
            Map<String, Object> widths = (Map<String, Object>) cardWidth;
            int card = (Integer) widths.get("card");
            int grid = (Integer) widths.get("grid");
            assertTrue(card <= 150, "each card is " + card + "px wide inside a " + grid
                    + "px grid - they are stretching again");

            // The season view, so weeks 6 and 12 are visible as deliberate rather than as a gap.
            assertTrue(body.contains("Week 6") && body.contains("Week 12"),
                    "the season grid should show all twelve weeks");
            assertTrue(body.contains("no league football"),
                    "weeks 6 and 12 must say they have no league football");

            assertTrue(!body.contains("Top-level squad hub placeholder"),
                    "the placeholder copy is still on the country page");

            assertTrue(pageErrors.isEmpty(), "uncaught errors on the page: " + pageErrors);
            assertTrue(consoleErrors.isEmpty(), "console errors on the page: " + consoleErrors);
        } finally {
            context.close();
            browser.close();
            pw.close();
        }
    }
}
