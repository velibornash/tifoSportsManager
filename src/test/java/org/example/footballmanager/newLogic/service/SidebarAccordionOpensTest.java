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
 * <p>Needs the application running, so it skips rather than fails when it is not.
 */
class SidebarAccordionOpensTest {

    @Test
    @SuppressWarnings("unchecked")
    void desktopAccordionsOpenOnASingleClick() throws Exception {
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
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 900));
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
        System.out.println("PROBE url=" + page.url());
        System.out.println("PROBE headers=" + page.locator("#clubSidebar .accordion-header").count());

        Object raw = page.evaluate(JS);
        List<Map<String, Object>> out = (List<Map<String, Object>>) raw;
        out.forEach(m -> System.out.println("PROBE " + m));

        assertFalse(out.isEmpty(), "no accordion headers found in #clubSidebar");
        for (Map<String, Object> row : out) {
            assertTrue(Boolean.TRUE.equals(row.get("opened")),
                    "accordion '" + row.get("header") + "' did not open on a single click: " + row);
        }
        ctx.close();
        browser.close();
        pw.close();
    }

    private static final String JS = "() => { const out = []; document.querySelectorAll('#clubSidebar .accordion-header').forEach(h => { const c = h.nextElementSibling; h.click(); const after = c.style.maxHeight || '(unset)'; out.push({ header: (h.textContent||'').trim().slice(0,30), after: after, opened: after !== '0px' && after !== '(unset)' }); }); return out; }";
}
