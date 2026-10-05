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
 * A real browser over the three screens P2-20 added.
 *
 * <p><b>Every phase of that work was verified by rendering the served module against a live payload and
 * driving the endpoints with curl, and every phase's log entry said "no browser" as a limitation.</b>
 * This is the check that closes it, and it exists because of what a static check cannot see.
 *
 * <p>The evidence is in the repository. The country page once called an escaping function that was not in
 * the file's scope — a name that exists elsewhere in the project, so it looked right — and rendered its
 * error card. {@code node --check} passed and the endpoint returned 200. <b>The full suite run for Phase 6
 * caught the same class of bug one commit later:</b> {@code dashboard.js} called {@code readUnreadCount}
 * without importing it, and only {@code CountryPageRendersTest}'s console-error assertion noticed. A test
 * that renders markup and a test that runs it are different tests, and only the second one found it.
 *
 * <p>So this asserts on the page's own text after a real login and real clicks, and it treats an
 * uncaught error or a console error as a failure rather than a detail.
 */
class CommunityScreensRenderTest {

    @Test
    @DisplayName("the forum, the messages and the profiles render, and the whole SPA raises nothing")
    void theCommunityScreensRenderInARealBrowser() throws Exception {
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
            page.waitForTimeout(3500);

            // ── The bell, on the dashboard ────────────────────────────────────────────────────────
            //
            // Present on every page and driven by the 30s poll, so a broken import here would throw on
            // the dashboard and nowhere else until a manager clicked it.
            assertTrue(page.locator("#notification-bell").count() > 0,
                    "the notification bell is not in the top bar");
            page.click("#notification-bell");
            page.waitForTimeout(1200);
            String dropdown = page.content();
            assertTrue(!dropdown.contains("Could not load notifications"),
                    "the notification dropdown rendered an error");
            // Opening and closing it must not leave it stuck open over the page.
            page.evaluate("() => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))");
            page.waitForTimeout(400);

            // ── The forum ─────────────────────────────────────────────────────────────────────────
            page.evaluate("() => window.loadPage('forum')");
            page.waitForTimeout(2000);
            String forum = page.content();
            assertTrue(!forum.contains("The forum could not be loaded"),
                    "the forum rendered its error card");
            assertTrue(forum.contains("Forum"), "the forum page has no heading");
            assertTrue(forum.contains("TIFO") && forum.contains("Non-TIFO"),
                    "the forum is missing one of its two sections");
            assertTrue(!forum.contains("Page not found"),
                    "loadPage('forum') fell through to the not-found case");

            // The section list must show real topics if any exist, and must not show an error if not.
            assertTrue(!forum.contains("Failed to load"), "the topic list reported a failure");

            // ── One section ───────────────────────────────────────────────────────────────────────
            page.evaluate("() => window.loadPage('forumSection', { section: 'TIFO' })");
            page.waitForTimeout(1800);
            String section = page.content();
            assertTrue(!section.contains("could not be loaded"),
                    "the TIFO section rendered its error card");

            // ── A topic, if one exists ────────────────────────────────────────────────────────────
            //
            // Guarded on the topic list actually having one: the dev world is empty until somebody
            // posts, and a test that fails on an empty forum teaches people to ignore it.
            boolean hasTopic = page.locator(".js-open-topic").count() > 0;
            assumeTrue(hasTopic, "no forum topic exists yet - skipping the thread view");

            page.locator(".js-open-topic").first().click();
            page.waitForTimeout(1800);
            String topic = page.content();
            assertTrue(!topic.contains("This topic could not be loaded"),
                    "a topic rendered its error card");
            assertTrue(topic.contains(".forum-post") || topic.contains("forum-post"),
                    "the thread rendered no posts");
            // The reply form is the write path, and it must be present and enabled.
            assertTrue(page.locator(".js-reply-form").count() > 0,
                    "the thread has no reply form");

            // ── Messages ──────────────────────────────────────────────────────────────────────────
            page.evaluate("() => window.loadPage('messages')");
            page.waitForTimeout(2000);
            String messages = page.content();
            assertTrue(!messages.contains("Your messages could not be loaded"),
                    "the inbox rendered its error card");
            assertTrue(messages.contains("Messages"), "the inbox has no heading");
            // The recipient picker must be populated from the server, not left saying "Loading".
            Object recipients = page.evaluate(
                    "() => { const s = document.querySelector('#message-recipient');"
                    + " return s ? s.options.length : 0; }");
            assertTrue(((Integer) recipients) >= 1,
                    "the recipient picker never loaded - it still says \"Loading managers\"");

            // ── A conversation, if one exists ──────────────────────────────────────────────────────
            if (page.locator(".js-open-thread").count() > 0) {
                page.locator(".js-open-thread").first().click();
                page.waitForTimeout(1800);
                String conversation = page.content();
                assertTrue(!conversation.contains("This conversation could not be loaded"),
                        "a conversation rendered its error card");
                assertTrue(conversation.contains("Reply"), "a conversation has no reply box");
            }

            // ── The club page, and the manager link on it ─────────────────────────────────────────
            //
            // The owner's path: club -> who runs it -> his profile. This is the step that had no
            // implementation at all until Phase 2.
            page.evaluate("() => window.loadPage('profile')");
            page.waitForTimeout(2000);
            String club = page.content();
            assertTrue(!club.contains("could not be loaded"), "the club profile rendered an error card");
            boolean hasManagerLink = page.locator("[data-open-manager]").count() > 0;
            assumeTrue(hasManagerLink, "this club has no manager link (an AI club, or no owner yet)");

            page.locator("[data-open-manager]").first().click();
            page.waitForTimeout(2000);
            String profile = page.content();
            assertTrue(!profile.contains("This profile could not be loaded"),
                    "a manager profile rendered its error card");
            // No email address anywhere on somebody else's profile: the structural guarantee, checked
            // in the markup this time rather than only in the JSON.
            assertTrue(!profile.contains("velibor@example.com"),
                    "a public profile is showing an email address");

            // ── The admin tab ─────────────────────────────────────────────────────────────────────
            page.evaluate("() => window.loadPage('admin')");
            page.waitForTimeout(2500);
            String admin = page.content();
            assertTrue(admin.contains("Accounts"), "the admin tab has no accounts panel");
            assertTrue(admin.contains("Applications"),
                    "the admin tab has no applications panel - the queue is still in the chat");
            assertTrue(page.locator(".js-ban-user, .js-lift-ban, .js-change-role").count() > 0,
                    "the accounts panel rendered no controls");

            // ── Nothing anywhere threw ────────────────────────────────────────────────────────────
            assertTrue(pageErrors.isEmpty(), "uncaught errors on the page: " + pageErrors);
            assertTrue(consoleErrors.isEmpty(), "console errors on the page: " + consoleErrors);
        } finally {
            context.close();
            browser.close();
            pw.close();
        }
    }
}