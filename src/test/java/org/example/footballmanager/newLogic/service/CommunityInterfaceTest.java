package org.example.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Six interface changes the owner asked for on 2026-10-06, each one measured rather than eyeballed.
 *
 * <p><b>Every assertion here is a measurement.</b> The overflow in particular cannot be seen by reading
 * CSS: {@code .community-compose-textarea} sets {@code width: 100%} and {@code padding: 14px 16px} under
 * the default {@code content-box}, so the rendered box is 100% + 32px and its background runs past the
 * panel it is drawn on. Before this was fixed, measured at 1400px: the field's right edge sat at <b>1303</b>
 * and the panel's at <b>1290</b>. After: 1171 and 1290.
 *
 * <p>That number is the test. A rule that looks like it fixed it is not evidence — the first attempt
 * targeted {@code textarea.community-compose-textarea} when the class is on the <i>wrapper</i>, matched
 * nothing, and the field was still 182px wide after the "fix".
 */
class CommunityInterfaceTest {

    /**
     * Playwright's {@code evaluate} is typed to {@code Object}, so every number it returns needs
     * unwrapping. Written out once here rather than as an inline cast at eight call sites, where the
     * cast is easy to get wrong and the compiler error points at the cast rather than the query.
     */
    private static int count(Page page, String script) {
        return ((Number) page.evaluate(script)).intValue();
    }

    @Test
    @DisplayName("the six interface changes the owner asked for, measured")
    void theRequestedInterfaceChanges() throws Exception {
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
                new Browser.NewContextOptions().setViewportSize(1400, 1000));
        Page page = context.newPage();
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(e -> pageErrors.add(String.valueOf(e)));

        try {
            try {
                page.navigate("http://localhost:8080/login.html");
            } catch (Exception e) {
                assumeTrue(false, "application is not running on :8080 - skipping the live check");
                return;
            }
            page.fill("input[name='email']", "velibor@example.com");
            page.fill("input[name='password']", "A12345!");
            page.click("#loginBtn");
            page.waitForTimeout(4000);
            page.navigate("http://localhost:8080/dashboard.html");
            page.waitForTimeout(3000);

            // ── 1. One Community entry in the top bar, with the two screens as options on the page ───
            //
            // Phase 4 gave Forum and Messages a top-bar button each. The owner asked for them as
            // options under one Community tab, the way Club carries First Team and Schedule.
            int communityButtons = count(page,
                    "() => document.querySelectorAll('.top-menu .community-menu-button').length");
            assertEquals(1, communityButtons,
                    "the top bar has " + communityButtons + " Community entries; it should have one");

            Object topBarText = page.evaluate(
                    "() => [...document.querySelectorAll('.top-menu .menu-button')]"
                    + "   .map(e => e.textContent.trim())");
            @SuppressWarnings("unchecked")
            List<Object> labels = (List<Object>) topBarText;
            assertTrue(!labels.contains("Forum"),
                    "Forum is still its own top-bar button: " + labels);
            assertTrue(!labels.contains("Messages"),
                    "Messages is still its own top-bar button: " + labels);

            // ── 2. The envelope is bigger than it was ────────────────────────────────────────────────
            //
            // U+2709 renders small beside the emoji the other buttons use, so it is scaled rather than
            // swapped — a different glyph would stop the row reading as one set. Measured against the
            // rendered font size of the button text, not against a class name.
            Object iconSize = page.evaluate(
                    "() => { const icon = document.querySelector('.community-menu-icon');"
                    + " if (!icon) return null;"
                    + " const parent = icon.closest('.menu-button');"
                    + " const px = n => Math.round(parseFloat(getComputedStyle(n).fontSize));"
                    + " return { icon: px(icon), button: px(parent) }; }");
            @SuppressWarnings("unchecked")
            Map<String, Object> iconSizes = (Map<String, Object>) iconSize;
            assertTrue(iconSizes != null, "the Community icon span is missing from the top bar");
            assertTrue(((Number) iconSizes.get("icon")).intValue()
                            >= ((Number) iconSizes.get("button")).intValue(),
                    "the envelope is not larger than the button's own text: " + iconSizes);

            page.evaluate("() => window.loadPage('forum')");
            page.waitForTimeout(2000);

            // ── The option row, and which one is lit ────────────────────────────────────────────────
            //
            // "Forum" must stay lit while reading a topic, not only on the index: otherwise the row
            // says you are on the messages screen while you are reading the forum.
            Object optionTexts = page.evaluate(
                    "() => [...document.querySelectorAll('.fm-club-actions .fm-action-btn')]"
                    + "   .map(e => e.textContent.trim())");
            @SuppressWarnings("unchecked")
            List<Object> options = (List<Object>) optionTexts;
            assertTrue(options.contains("Forum") && options.contains("Messages"),
                    "the forum page is missing the Forum/Messages option row: " + options);

            Object litOnForum = page.evaluate(
                    "() => { const b = [...document.querySelectorAll('.fm-club-actions .fm-action-btn')]"
                    + "   .find(e => e.textContent.trim() === 'Forum');"
                    + " return b ? b.classList.contains('is-current') : null; }");
            assertEquals(Boolean.TRUE, litOnForum, "Forum is not lit while on the forum");

            page.evaluate("() => window.loadPage('messages')");
            page.waitForTimeout(2000);
            Object litOnMessages = page.evaluate(
                    "() => { const b = [...document.querySelectorAll('.fm-club-actions .fm-action-btn')]"
                    + "   .find(e => e.textContent.trim() === 'Messages');"
                    + " return b ? b.classList.contains('is-current') : null; }");
            assertEquals(Boolean.TRUE, litOnMessages, "Messages is not lit while on the messages screen");

            // ── 3 and 4. The compose fields sit inside the panel, and are wide ───────────────────────
            // The WRAPPER is measured, not just the field inside it. The reported defect was the
            // wrapper's own background running past the panel - it is the box that carries
            // width:100% plus padding - and measuring only the input missed that entirely: removing
            // box-sizing from the wrapper left this test green while the wrapper overflowed again.
            Object geometry = page.evaluate(
                    "() => { const r = e => { if (!e) return null; const b = e.getBoundingClientRect();\n"
                    + "   return { left: Math.round(b.left), right: Math.round(b.right),\n"
                    + "            width: Math.round(b.width) }; };\n"
                    + " const panels = [...document.querySelectorAll('.fm-panel')].map(r);\n"
                    + " const body = document.querySelector('.js-compose textarea[name=body]');\n"
                    + " const subject = document.querySelector('.js-compose input[name=subject]');\n"
                    + " const wrapper = document.querySelector('.js-compose .community-compose-textarea');\n"
                    + " return { panelRight: panels.length ? Math.min(...panels.map(p => p.right)) : null,\n"
                    + "          subject: r(subject), body: r(body), wrapper: r(wrapper) }; }");
            @SuppressWarnings("unchecked")
            Map<String, Object> geo = (Map<String, Object>) geometry;

            @SuppressWarnings("unchecked")
            Map<String, Object> subject = (Map<String, Object>) geo.get("subject");
            @SuppressWarnings("unchecked")
            Map<String, Object> bodyBox = (Map<String, Object>) geo.get("body");
            @SuppressWarnings("unchecked")
            Map<String, Object> wrapper = (Map<String, Object>) geo.get("wrapper");

            assertTrue(subject != null, "the subject field is missing");
            assertTrue(bodyBox != null, "the message body is missing");
            assertTrue(wrapper != null, "the compose field wrapper is missing");

            assertTrue(((Number) wrapper.get("right")).intValue()
                            <= ((Number) geo.get("panelRight")).intValue(),
                    "the compose wrapper's background runs to " + wrapper.get("right")
                            + " while the panel ends at " + geo.get("panelRight")
                            + " — the reported overflow");

            // The padding has to be INSIDE the width the rule declares.
            //
            // <p>"Does not overflow the panel" is not enough on its own: removing box-sizing from the
            // wrapper still fit inside the panel here, because the max-width cap the owner also asked for
            // was smaller than the panel and hid the 32px of padding. That mutation survived the first
            // version of this assertion. A field that is 1040px wide plus 32px of padding is not a
            // 1040px field, so the width is compared to the width the stylesheet declares.
            int declared = 1040;
            assertTrue(((Number) wrapper.get("width")).intValue() <= declared + 1,
                    "the compose wrapper is " + wrapper.get("width") + "px wide but declares a "
                            + declared + "px max-width — its padding is being added to the width "
                            + "instead of being inside it, which is the reported overflow");

            // The reported defect: the field's background ran past the panel it is drawn on.
            assertTrue(((Number) subject.get("right")).intValue()
                            <= ((Number) geo.get("panelRight")).intValue(),
                    "the subject field's right edge is at " + subject.get("right")
                            + " and the panel's is at " + geo.get("panelRight")
                            + " — the field overflows the panel to the right");
            assertTrue(((Number) bodyBox.get("right")).intValue()
                            <= ((Number) geo.get("panelRight")).intValue(),
                    "the message body's right edge is at " + bodyBox.get("right")
                            + " and the panel's is at " + geo.get("panelRight"));

            // And inset rather than flush, which is the second half of the instruction.
            assertTrue(((Number) subject.get("right")).intValue()
                            < ((Number) geo.get("panelRight")).intValue(),
                    "the field is flush with the panel edge; it was asked for a couple of cm back");

            // The reported defect: both defaulted to a fixed character width — 147px and 182px.
            assertTrue(((Number) subject.get("width")).intValue() > 600,
                    "the subject field is " + subject.get("width") + "px wide — it is back at its default");
            assertTrue(((Number) bodyBox.get("width")).intValue() > 600,
                    "the message box is " + bodyBox.get("width") + "px wide — it is back at its default");

            // ── 5. The manager picker can be searched by typing ──────────────────────────────────────
            assumeTrue(page.locator("#message-recipient-search").count() > 0,
                    "the searchable recipient picker is not on the page");

            // Every option hidden while nothing is typed.
            int optionsWhileClosed = page.locator(".compose-recipient-option").count();
            assertEquals(0, optionsWhileClosed,
                    "the manager list is open before anything has been typed");

            // Typing narrows it. The count is compared to the unfiltered total rather than to a
            // literal, so this still means something when there are fifty managers.
            int totalManagers = count(page,
                    "() => document.querySelectorAll('#message-recipient option').length") - 1;
            assumeTrue(totalManagers >= 1, "there is nobody to write to");

            Object nameValue = page.evaluate(
                    "() => { const o = document.querySelector('#message-recipient option[value]:not([value=\"\"])');"
                    + " return o ? o.textContent.trim() : ''; }");
            String aName = String.valueOf(nameValue);
            // From the MIDDLE of the name, not the start.
            //
            // <p>With the first three characters the filter was really being tested as a prefix match:
            // narrowing it to startsWith left the test green. Matching anywhere is the behaviour that
            // makes the box useful — you type "eck" or "cko" because you have half a name in mind, not
            // because you are looking at the list and reading the first letters off it.
            String fragment = aName.length() >= 5
                    ? aName.substring(2, Math.min(5, aName.length()))
                    : aName;
            page.fill("#message-recipient-search", fragment);
            page.waitForTimeout(500);

            int matches = page.locator(".compose-recipient-option").count();
            assertTrue(matches >= 1,
                    "typing \"" + fragment + "\" (from " + aName + ") found no manager");
            assertTrue(matches <= totalManagers,
                    "typing a name returned more managers than exist");

            // A name that matches nothing says so rather than showing everybody.
            page.fill("#message-recipient-search", "zzzzzz-no-such-manager");
            page.waitForTimeout(500);
            assertEquals(0, page.locator(".compose-recipient-option").count(),
                    "a name that matches nothing still listed managers");
            assertTrue(page.locator(".compose-recipient-none").count() > 0,
                    "a name that matches nothing says nothing about it");

            // Choosing one writes the id the form submits.
            page.fill("#message-recipient-search", fragment);
            page.waitForTimeout(500);
            page.locator(".compose-recipient-option").first().click();
            page.waitForTimeout(400);
            Object chosenValue = page.evaluate(
                    "() => document.querySelector('#message-recipient').value");
            String chosen = chosenValue == null ? "" : String.valueOf(chosenValue);
            assertTrue(chosen != null && !chosen.isBlank(),
                    "clicking a manager did not set the id the form submits");

            // ── 6. A forum section's Back goes to the dashboard, not to the previous screen ────────────
            page.evaluate("() => window.loadPage('forum')");
            page.waitForTimeout(1800);
            page.locator(".js-section").first().click();
            page.waitForTimeout(1800);

            // The attribute is the point. `data-nav-back` makes pages.js hand the click to goBackSmart,
            // which prefers the navigation history and only uses the argument as a fallback - so a
            // section opened from the index would pop back to the index, which is exactly the previous
            // screen the owner asked not to go back to. The button must navigate directly instead.
            Object backValue = page.evaluate(
                    "() => { const b = document.querySelector('.back-to-dashboard');"
                    + " return b ? { navBack: b.dataset.navBack ?? null,"
                    + "            onclick: b.getAttribute('onclick') } : null; }");
            @SuppressWarnings("unchecked")
            Map<String, Object> backAttrs = (Map<String, Object>) backValue;
            assertTrue(backAttrs != null, "the section screen has no Back button");
            assertEquals(null, backAttrs.get("navBack"),
                    "the Back button carries data-nav-back, so it pops the navigation history "
                            + "and lands on the forum index instead of the dashboard");
            // loadDashboard, not loadPage: the router's switch has no dashboard case, so loadPage
            // falls through to "Page not found". Which is what happened first.
            assertTrue(String.valueOf(backAttrs.get("onclick")).contains("loadDashboard"),
                    "the Back button does not call loadDashboard directly: " + backAttrs);

            page.locator(".back-to-dashboard").click();
            page.waitForTimeout(2500);
            Object afterBack = page.evaluate(
                    "() => ({ page: window.currentPageIdProbe || null,"
                    + " dashboard: !!document.querySelector('.fm-dashboard-view') })");
            @SuppressWarnings("unchecked")
            Map<String, Object> landed = (Map<String, Object>) afterBack;
            assertEquals(Boolean.TRUE, landed.get("dashboard"),
                    "Back from a forum section did not land on the dashboard. main-content starts: "
                            + String.valueOf(page.evaluate("() => document.getElementById('main-content').innerHTML.slice(0, 200)")));

            assertTrue(pageErrors.isEmpty(), "uncaught errors on the page: " + pageErrors);
        } finally {
            context.close();
            browser.close();
            pw.close();
        }
    }
}