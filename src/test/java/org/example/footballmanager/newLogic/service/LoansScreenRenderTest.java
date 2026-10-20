package org.example.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Loans screen, rendered in a real browser against a real login.
 *
 * <p><b>Every other check on this feature had missed something.</b> The endpoints answered, the Java
 * compiled, the JavaScript parsed, and 23 unit tests were green — and the screen still would not have
 * worked, because:
 *
 * <ul>
 *   <li>it read {@code Player.getRating()}, the <b>stored column</b>, which is 0 for every player in
 *       the owner's world, so the lending table was a column of zeros;</li>
 *   <li>it read {@code d.eligible === true} against a server that sent {@code null} for eligible, so the
 *       destination dropdown was <b>empty</b> — which reads as "there is nobody to loan to";</li>
 *   <li>and {@code /loans/destinations} shipped <b>14,626 clubs, 2.38 MB</b>, to deliver one option.</li>
 * </ul>
 *
 * <p>All three were found by calling the running application, not by a test, and none of them could have
 * been found by a unit test: they are defects in the world and in the contract between two processes,
 * and the fixtures a unit test builds are always correct.
 *
 * <p>So this drives the page and asserts on what a manager would actually see. An uncaught error or a
 * console error is a failure, not a detail — the country page once rendered its error card because it
 * called an escaping function that existed elsewhere in the project, and {@code node --check} passed.
 */
class LoansScreenRenderTest {

    @Test
    @DisplayName("the Loans screen renders, states the rules, and offers a real destination")
    void theLoansScreenRendersInARealBrowser() throws Exception {
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
            page.waitForTimeout(3000);

            // The route must exist: it was once a feature with no page, which is the same as no feature.
            page.evaluate("() => window.loadPage('loans')");
            page.waitForTimeout(3000);

            String body = page.content();
            assertTrue(pageErrors.isEmpty(), "the page raised: " + pageErrors);
            assertTrue(!body.contains("Page not found"), "loadPage('loans') fell through to not-found");
            assertTrue(body.contains("Loans"), "the screen has no heading");
            assertTrue(!body.contains("Could not load loan data"), "it rendered its error card");

            // The rules, in words. The owner asked for the mechanism to be legible; if the copy is
            // missing then the feature exists and the manager cannot use it.
            assertTrue(body.contains("younger than 24"),
                    "the age rule is not stated, so a manager cannot tell who is loanable");
            assertTrue(body.contains("Tier 1 lends into 2-5"),
                    "the tier ladder is not stated");
            assertTrue(body.contains("pays him"),
                    "the wage rule is not stated, and it is the one that surprises people");
            assertTrue(body.contains("week 12"),
                    "the end of a loan is not stated");

            // The lending table must show real ratings. Every player in the owner's world reads 0 in the
            // stored column, so this is the assertion that would have caught the first version.
            assumeTrue(page.locator(".fm-page--loans tbody tr").count() > 0,
                    "this club has no loanable players - the rating check needs at least one row");

            //
            // Column 3, and deliberately not "any two-digit cell in the row". The first version of this
            // assertion scanned every cell for a 1-2 digit number greater than zero — and the AGE column
            // is 22, 20, 23. It passed against the stored rating column while every rating on screen was
            // zero, and a mutation that reverted careerRating() to getRating() left it green. That is
            // the whole point of a mutation: an assertion that cannot fail is worse than no assertion,
            // and this one looked like it was checking the thing.
            //
            // Header-driven rather than a hardcoded index, so a column added later moves the check with
            // the header instead of silently checking whatever ends up in position three.
            boolean anyRatings = Boolean.TRUE.equals(page.evaluate(
                    // No '//' comments inside the JS: Java concatenation leaves this on one line,
                    // so a line comment would swallow the entire expression and Playwright reports
                    // "Unexpected end of input" rather than anything about ratings.
                    //
                    // The table that HAS a Rating column, not the first one on the page - 'Players
                    // loaned in' comes first and is usually empty, and selecting it gave zero rows.
                    "() => {"
                            + "  const table = Array.from(document.querySelectorAll('.fm-page--loans table'))"
                            + "      .find(t => Array.from(t.querySelectorAll('thead th'))"
                            + "          .some(th => th.textContent.trim().toUpperCase() === 'RATING'));"
                            + "  if (!table) return false;"
                            + "  const heads = Array.from(table.querySelectorAll('thead th'))"
                            + "      .map(th => th.textContent.trim().toUpperCase());"
                            + "  const idx = heads.indexOf('RATING');"
                            + "  if (idx < 0) return false;"
                            + "  return Array.from(table.querySelectorAll('tbody tr'))"
                            + "      .some(tr => { const c = tr.children[idx];"
                            + "        return c && Number(c.textContent.trim()) > 0; });"
                            + " }"));
            assertTrue(anyRatings,
                    "every rating in the lending table is zero. The endpoint reads the STORED rating "
                            + "column; the real value is careerRating() and PlayerRatingBackfill has "
                            + "never been run over this world.");

            // The destination dropdown. This is the assertion that would have caught the contract
            // mismatch: an empty dropdown is silent, and it reads as "there is nobody to loan to".
            Integer options = (Integer) page.evaluate(
                    "() => { const sel = document.querySelector('[data-loan-destination-for]');"
                            + " if (!sel) return -1;"
                            + " return Array.from(sel.options).filter(o => Number(o.value) > 0).length; }");
            assertTrue(options != null && options >= 0,
                    "the lending table has no destination dropdown at all");

            boolean loanableRows = Boolean.TRUE.equals(page.evaluate(
                    "() => Array.from(document.querySelectorAll('.fm-page--loans tbody tr'))"
                            + ".some(tr => Array.from(tr.querySelectorAll('button'))"
                            + ".some(b => b.textContent.trim() === 'Lend out' && !b.disabled))"));

            if (loanableRows) {
                assertTrue(options != null && options > 0,
                        "there is a loanable player with an enabled Lend out button, but the "
                                + "destination dropdown is empty - the screen would offer a button that "
                                + "cannot do anything. Either the eligible payload is empty or the "
                                + "screen is reading the wrong field.");
            }

            // And the refusals are explained rather than silently dropped.
            if (options != null && options == 0) {
                assertTrue(body.contains("could not be loaned to") || body.contains("No eligible club"),
                        "there are no destinations and the screen does not say why");
            }

            assertTrue(consoleErrors.isEmpty(), "console errors: " + consoleErrors);
        } finally {
            browser.close();
            pw.close();
        }
    }
}