package org.example.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S8.3 #1, part two: the desktop sidebar accordions did nothing at all.
 *
 * <p>The backlog framed this as "loadPage fires twice per click", which is the lesser half. Deleting
 * the duplicate {@code app.js} binder removed the double render but left the accordions dead, which is
 * what forced the real diagnosis: every {@code .accordion-header} carries an inline
 * {@code onclick="toggleAccordion(this)"} <i>and</i> sidebar.js bound the same element with
 * addEventListener. {@code toggleAccordion} reads the open state and then writes the opposite, so two
 * calls open the panel and immediately close it.
 *
 * <p>It survived because a collapsed accordion and a dead accordion look identical from outside. The
 * only way to tell them apart is to click one in a browser and read {@code style.maxHeight}, which is
 * why this test exists rather than a source check: a source check cannot see that a control opens
 * zero pixels.
 *
 * <p>It used to click {@code #clubSidebar}, which was rendered at {@code left: -260px} and never
 * reached the screen - all twelve Club entries were invisible, which is why nothing noticed that the
 * navigation was gone. That sidebar was removed, and the same hazard now lives in the mobile drawer,
 * which is the one accordion surface the app has. So this checks the drawer, at phone width.
 *
 * <p>It walks the {@code .accordion-content} panels rather than the headers: in the drawer most
 * {@code .accordion-header} elements are leaf navigation buttons wearing the class, and
 * {@code nextElementSibling} on one of those is null.
 *
 * <p>Needs the application running, so it skips rather than fails when it is not.
 */
class SidebarAccordionOpensTest {

    @Test
    @SuppressWarnings("unchecked")
    void drawerAccordionsOpenOnASingleClick() throws Exception {
        Playwright pw;
        Browser browser;
        try {
            pw = Playwright.create();
            browser = pw.chromium().launch(
                    new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            assumeTrue(false, "no chromium: " + e.getMessage());
            return;
        }
        // Phone width: the drawer is the only accordion surface the app has now.
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(390, 844));
        Page page = ctx.newPage();
        // The rest of the suite runs with no application up, so this has to skip rather than fail when
        // :8080 is closed. A test that reports ERR_CONNECTION_REFUSED as an error teaches people to
        // ignore errors, which is the same failure mode as the flaky test fixed in 7a00522.
        try {
            page.navigate("http://localhost:8080/login.html");
        } catch (Exception e) {
            assumeTrue(false, "application is not running on :8080 - skipping the live sidebar check");
            return;
        }
        assumeTrue(page.content().contains("loginBtn"), "login page did not render as expected");
        page.fill("input[name='email']", "velibor@example.com");
        page.fill("input[name='password']", "A12345!");
        page.click("#loginBtn");
        page.waitForTimeout(5000);
        page.navigate("http://localhost:8080/dashboard.html");
        page.waitForTimeout(4000);
        Object raw = page.evaluate(JS);
        List<Map<String, Object>> out = (List<Map<String, Object>>) raw;

        assertFalse(out.isEmpty(),
                "no accordion panel found in #mobileSidebar - if the drawer has no expandable panel "
                        + "at all, this is testing nothing and should be deleted rather than passed");
        for (Map<String, Object> row : out) {
            assertTrue(Boolean.TRUE.equals(row.get("opened")),
                    "accordion '" + row.get("header") + "' did not open on a single click: " + row);
        }
        ctx.close();
        browser.close();
        pw.close();
    }

    /**
     * One click per panel, and the panel's own header is what gets clicked.
     *
     * <p>Going through the content panels is deliberate. In the drawer most {@code .accordion-header}
     * elements are leaf navigation buttons that expand nothing, and taking their
     * {@code nextElementSibling} yields null - a test that threw on those would report a broken
     * selector rather than a broken accordion.
     */
    private static final String JS = """
            () => {
              const out = [];
              document.querySelectorAll('#mobileSidebar .accordion-content').forEach(c => {
                const h = c.previousElementSibling;
                if (!h) return;
                h.click();
                const after = c.style.maxHeight || '(unset)';
                out.push({
                  header: (h.textContent || '').trim().slice(0, 30),
                  after: after,
                  opened: after !== '0px' && after !== '(unset)'
                });
              });
              return out;
            }
            """;
}
