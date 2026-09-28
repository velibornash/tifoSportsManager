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

            // Seven days, each named. If the schedule silently failed to load, the grid is absent and
            // this is what notices.
            for (String day : List.of("Day 1", "Day 2", "Day 3", "Day 4", "Day 5", "Day 6", "Day 7")) {
                assertTrue(body.contains(day), "the week grid is missing " + day);
            }
            for (String kind : List.of("International", "Finance update", "League", "Training", "Cup",
                    "Form &amp; morale")) {
                assertTrue(body.contains(kind), "the week grid is missing the " + kind + " day");
            }

            assertTrue(pageErrors.isEmpty(), "uncaught errors on the page: " + pageErrors);
            assertTrue(consoleErrors.isEmpty(), "console errors on the page: " + consoleErrors);
        } finally {
            context.close();
            browser.close();
            pw.close();
        }
    }
}
