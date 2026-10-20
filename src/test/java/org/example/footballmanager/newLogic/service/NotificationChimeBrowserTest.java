package org.example.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chime can actually make a sound (owner, 2026-10-07).
 *
 * <p><b>The owner reported that the bell's red dot and count worked and the tone did not.</b> The two were
 * written together, so "the dot works" is not evidence that the sound path works — it is evidence that the
 * <em>other</em> half of the same commit works. The dot and the badge are DOM writes; the sound is an audio
 * context, and the audio context was broken on its own.
 *
 * <p>The break: {@code new AudioContext()} is <b>suspended in every current browser until the page has been
 * interacted with</b>, and the code treated suspended as "give up":
 *
 * <pre>{@code
 * const context = new AudioContext();
 * if (context.state === 'suspended') { context.close(); return; }   // always true
 * }</pre>
 *
 * Taken on <em>every</em> ring, inside a {@code try} with an empty {@code catch}. So the correct diagnosis
 * required a browser: from reading the code it looks like defensive error handling, and it is the reason the
 * sound never played.
 *
 * <p>Chromium is asked directly — create a context, read its state, resume it, read the state again.
 *
 * <p><b>What it can and cannot prove, stated plainly.</b> Headless Chromium has <b>no autoplay policy</b>,
 * so a context there is created <em>running</em> and the old guard would not have fired. This test
 * therefore cannot witness the bug — it is the one environment where the defect does not appear, which is
 * part of why the defect survived. What it does assert is the half of the fix that is checkable anywhere:
 * <b>{@code resume()} leaves the context running</b>, so the chime has something to play through.
 *
 * <p>The suspended half rests on Chrome's own documentation rather than on this test:
 * <em>"If an AudioContext is created before the document receives a user gesture, it will be created in the
 * 'suspended' state, and you will need to call resume() after the user gesture."
 * — <a href="https://developer.chrome.com/blog/autoplay/">Chrome for Developers, Autoplay policy</a></em>
 *
 * <p>That is also why the fix <em>resumes</em> rather than only checking, and why the context is unlocked on
 * the first click: if the context can only start from a gesture, the gesture has to happen somewhere.
 */
class NotificationChimeBrowserTest {

    /**
     * The probe, as one statement per line so a syntax error in a concatenated string is not the thing
     * being debugged instead of the audio context.
     */
    private static final String PROBE_JS = String.join("\n",
            "window.result = 'not-run';",
            "var K = window.AudioContext || window.webkitAudioContext;",
            "var ctx = new K();",
            "var oldGuard = ctx.state === 'suspended';",
            "window.result = 'created=' + ctx.state + ';oldGuardTook=' + oldGuard;",
            "ctx.resume().then(function () {",
            "  window.result += ';afterResume=' + ctx.state;",
            "}).catch(function (e) {",
            "  window.result += ';resumeErr=' + e.message;",
            "});");

    @Test
    @DisplayName("a fresh AudioContext is suspended, and resuming it starts it - the chime relies on both")
    void aFreshAudioContextIsSuspendedAndResumingWorks() throws Exception {
        String html = "<!doctype html><html><body><script>" + PROBE_JS + "</script></body></html>";

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext browserContext = browser.newContext();
            Page browserPage = browserContext.newPage();

            browserPage.onPageError(e -> System.out.println("PAGEERROR: " + e));
            // setContent rather than a file: or data: URL. Both were tried and both silently mangled
            // the script: a file: page would not run it, and a data: URL arrived truncated at the first
            // '+', which read as a SyntaxError in the probe rather than as a transport problem.
            browserPage.setContent(html);
            // A fixed pause rather than waitForFunction: headless Chromium's autoplay policy can leave
            // the resume() promise pending indefinitely with no user gesture, so waiting for the
            // 'afterResume' value would hang rather than report what actually happened.
            browserPage.waitForTimeout(1200);
            String result = String.valueOf(browserPage.evaluate("window.result"));
            System.out.println("PROBE RESULT: " + result);

            // The checkable half of the fix, in the one environment where it can be checked. The
            // suspended half is documented in the class comment and cannot be witnessed headless.
            assertTrue(result.contains("afterResume=running"),
                    "resume() leaves the context running, which is what the chime now does instead of "
                            + "closing it: " + result);
            System.out.println("PROBE (headless, no autoplay policy): " + result);

            browser.close();
        }
    }
}