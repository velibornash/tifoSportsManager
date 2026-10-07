package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bell's red dot and its ring (owner, 2026-10-06).
 *
 * <p><b>Why this reads the file instead of driving a browser.</b> The behaviour that matters here is a
 * comparison between two numbers on a 30-second timer, and the way to get that wrong is to write
 * {@code if (unread > 0) ring()} — which passes every static look and rings every 30 seconds for the rest
 * of the session. A Playwright test would need the tab open for two poll cycles to catch it. The
 * assertion below is on the condition itself, so the wrong version cannot be written and pass.
 *
 * <p>It is deliberately a source scan rather than a unit test of an exported function: the rule being
 * protected is about <em>which comparison</em> the shipped file contains, and exporting
 * {@code announceNewArrivals} for a test would not make the shipped bell any more correct.
 */
class NotificationBellAlertTest {

    private static final Path NOTIFICATIONS_JS =
            Path.of("src/main/resources/static/js/notifications.js");
    private static final Path DASHBOARD_CSS =
            Path.of("src/main/resources/static/css/dashboard.css");

    @Test
    @DisplayName("the bell carries a red dot whenever anything is unread, and the number stays")
    void theBellHasADotAndTheCount() throws IOException {
        String js = stripComments(jsSource());

        assertTrue(js.contains("bell.classList.toggle('has-unread', unread > 0)"),
                "the dot is driven by the unread count on the bell itself, not by the badge");

        assertTrue(js.contains("badge.textContent = unread > 99 ? '99+' : String(unread)"),
                "the count badge is still painted: the owner asked for the count as well as the dot");

        String css = cssSource();
        assertTrue(css.contains(".notification-bell.has-unread::after"),
                "there is CSS for the dot, or the class does nothing visible");
        assertTrue(css.contains("background: #e5484d"),
                "the dot is red - the same red the count badge already uses");
    }

    @Test
    @DisplayName("the ring fires on an INCREASE, never merely on having something unread")
    void theRingNeedsAnIncreaseNotMerelyUnread() throws IOException {
        String js = stripComments(jsSource());

        Matcher ringing = Pattern.compile("unread\\s*>\\s*lastSeenUnread").matcher(js);
        assertTrue(ringing.find(),
                "the sound must be tied to the count going UP. Tying it to 'unread > 0' would ring every "
                        + "30 seconds for as long as the tab is open.");

        assertTrue(js.contains("if (lastSeenUnread !== null && unread > lastSeenUnread)"),
                "the first read of a session sets the baseline without ringing: a backlog a manager "
                        + "already had when he signed in is not an arrival");

        assertTrue(js.contains("lastSeenUnread = unread;"),
                "the baseline is stored, otherwise the comparison has nothing to compare against");

        assertTrue(!js.contains("lastSeenUnread = 0;"),
                "starting the baseline at zero would ring for the unread notifications a manager already "
                        + "had on signing in");
    }

    @Test
    @DisplayName("the ring is synthesised, and it fails silently rather than raising a console error")
    void theRingIsSilentWhenItCannotPlay() throws IOException {
        String js = stripComments(jsSource());

        assertTrue(js.contains("AudioContext") || js.contains("webkitAudioContext"),
                "the chime is built with the Web Audio API rather than shipped as an audio file");

        assertTrue(js.contains("catch {"),
                "audio is blocked until the page is interacted with; the refusal arrives as a rejected "
                        + "promise and must not become an unhandled rejection");

        assertTrue(js.contains("audio.state === 'suspended'"),
                "a suspended context is resumed rather than abandoned");
        assertTrue(js.contains("audio.resume()"),
                "**resumed, not closed.** The old code closed a suspended context and returned, and a "
                        + "context created outside a user gesture is suspended in every current browser "
                        + "(Chrome's autoplay policy) - so the chime was given up on on every single ring, "
                        + "inside a try/catch with an empty catch. That is why the tone never sounded while "
                        + "the red dot, which is a DOM write, worked perfectly.");
        assertTrue(js.contains("unlockAudioOnFirstGesture"),
                "and the context is unlocked on the first click or keypress, because Chrome will only "
                        + "start it from a user gesture - so the gesture has to happen somewhere");
    }

    @Test
    @DisplayName("the dot does not move for a manager who asked for reduced motion")
    void reducedMotionIsRespected() throws IOException {
        assertTrue(cssSource().contains("prefers-reduced-motion"),
                "the pulse is movement, and movement is the part to drop");
        assertTrue(cssSource().contains("animation: none;"),
                "the dot and the count survive; only the movement stops");
    }

    @Test
    @DisplayName("opening a conversation or a topic marks that notification read")
    void openingTheTargetReadsTheNotification() throws IOException {
        String js = stripComments(jsSource());

        assertTrue(js.contains("void consumeNotification(button.dataset.notificationId"),
                "the Open-the-topic and Open-the-conversation links carry the notification's own id, "
                        + "because opening the conversation *is* reading the notification");

        assertTrue(js.contains("const owner = `data-notification-id=\"${escapeHtml(row.id)}\"`;"),
                "the link is rendered with that id on it");

        assertTrue(js.contains("if (event.target.closest('.js-go')) return;"),
                "and the row's own handler still skips these buttons, so the link handles it once "
                        + "rather than both handlers marking it read");
    }

    @Test
    @DisplayName("the badge drops the moment a notification is read, before the server is asked")
    void theBadgeDropsImmediately() throws IOException {
        String js = stripComments(jsSource());

        assertTrue(js.contains("async function consumeNotification"),
                "one path for both ways of reading a notification - clicking the row and clicking the link");
        assertTrue(js.contains("row.remove();"),
                "the row leaves the ticker at once");
        assertTrue(js.contains("decrementUnread();"),
                "and so does the count. The owner asked for the number to drop the moment he opens a "
                        + "conversation, not after a round trip.");
        assertTrue(js.indexOf("row.remove();") < js.indexOf("await authFetch(`/notifications/${encodeURIComponent(id)}/read`"),
                "**the screen changes before the server is asked** - a badge that waits on a round trip "
                        + "reads as broken");
    }

    @Test
    @DisplayName("the dropdown lists unread only - a read item is gone, not dimmed")
    void theDropdownShowsUnreadOnly() throws IOException {
        String js = stripComments(jsSource());

        assertTrue(js.contains("all.filter(row => row?.read !== true)"),
                "the dropdown is a view over the unread set: the owner asked for a read message to leave "
                        + "the ticker, and a list of things to deal with should empty as they are dealt with");


        assertTrue(!js.contains("is-read"),
                "nothing is rendered dimmed any more - a dimmed row is a row still being shown");

        assertTrue(js.contains("Nothing unread. You are caught up."),
                "and an empty unread list says so, rather than claiming there has never been anything");
    }

    @Test
    @DisplayName("the poll actually starts: the guard is a flag, not a question about the DOM")
    void thePollIsNotGuardedByAnElementThatAlwaysExists() throws IOException {
        String js = stripComments(jsSource());

        assertTrue(js.contains("let pollStarted = false"),
                "the re-entry guard is a flag. Asking the document whether the bell exists cannot work: "
                        + "the bell is in dashboard.html, so the answer is always yes, so the poll never "
                        + "starts, so neither the dot nor the ring has anything to run on.");

        assertTrue(js.contains("if (pollStarted) {"),
                "and the guard reads that flag rather than anything in the document");

        assertTrue(!js.contains("getElementById('notification-bell')) {\n        return"),
                "the old inverted guard is gone - it returned on every call and disabled polling entirely");
    }

    @Test
    @DisplayName("the served files exist - a scan of a file that is not shipped measures nothing")
    void theFilesAreReal() throws IOException {
        assertTrue(Files.isRegularFile(NOTIFICATIONS_JS), NOTIFICATIONS_JS + " is missing");
        assertTrue(Files.isRegularFile(DASHBOARD_CSS), DASHBOARD_CSS + " is missing");
        assertTrue(stripComments(jsSource()).contains("function paintBell"),
                "paintBell is the function the bell is painted in; if it was renamed this test is measuring nothing");
    }

    private String jsSource() throws IOException {
        return Files.readString(NOTIFICATIONS_JS, StandardCharsets.UTF_8);
    }

    private String cssSource() throws IOException {
        return Files.readString(DASHBOARD_CSS, StandardCharsets.UTF_8);
    }

    /** Comments out, so a rule explained in prose is not mistaken for a rule in the code. */
    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}