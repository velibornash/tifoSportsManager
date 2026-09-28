package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S8.3 #1: two files bound the same sidebar, and it broke the navigation.
 *
 * <p>The backlog described this as "loadPage fires twice per click", which is the <i>lesser</i> of the
 * two consequences and the reason it was still there. The same two files both bound
 * {@code .accordion-header} to {@code toggleAccordion}, and that function is <b>not idempotent</b> —
 * it reads the open state and then writes the opposite. Called twice on one click it opens the panel
 * and immediately closes it again, so all three accordion groups in the desktop sidebar did nothing
 * at all. Not "flawed", inert. The drill-down navigation looked like a design choice because the
 * alternative — a working accordion that happens to be closed — is indistinguishable from a broken
 * one after a single click.
 *
 * <p>So the rule is not "do not bind the same element twice", which is easy to satisfy by accident
 * and impossible to see in review. It is: <b>exactly one file may bind a given element</b>, and the
 * accordion toggle is a named hazard because its double-call is silent.
 */
class SidebarBindingTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path APP_HTML = Path.of("src/main/resources/static/dashboard.html");

    /** Files that are allowed to bind sidebar elements. */
    private static final List<String> BINDERS = List.of("sidebar.js");

    @Test
    @DisplayName("only sidebar.js binds the sidebar - a second binder silently breaks the accordions")
    void onlyOneFileBindsTheSidebar() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (var files = Files.walk(JS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                String name = file.getFileName().toString();
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (BINDERS.contains(name)) {
                    continue;
                }
                // Only count a real binding, not a mention: an addEventListener call on a sidebar
                // element selector, in a file that is not the designated binder.
                if (Pattern.compile("addEventListener\\s*\\([^)]*(accordion|sidebar)").matcher(code).find()
                        || Pattern.compile("getElementById\\s*\\(\\s*['\\\"](clubSidebar|mobileSidebar)").matcher(code).find()) {
                    offenders.add(name);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                () -> "These files bind sidebar elements: " + offenders + ". sidebar.js already binds "
                        + "#clubSidebar, so a second binder means every click fires twice. For "
                        + "toggleAccordion that is not a double render - it is a no-op, because the "
                        + "function reads the open state and then writes the opposite, so the "
                        + "accordion opens and closes again in the same tick and appears dead.");
    }

    @Test
    @DisplayName("no HTML page loads a second sidebar script")
    void noPageLoadsASecondSidebarScript() throws IOException {
        String html = Files.readString(APP_HTML, StandardCharsets.UTF_8);

        assertTrue(!html.contains("app.js"),
                "app.js was deleted because every binding in it was duplicated in sidebar.js - see the "
                        + "class comment. If a sidebar script tag comes back, check it does not bind again.");
    }

    @Test
    @DisplayName("a real accordion header is bound exactly once - inline onclick is the only path")
    void realAccordionsAreBoundOnce() throws IOException {
        // The real cause of the inert accordions, and the reason this has to look at the HTML as well
        // as the JS: each header was bound BOTH by an inline onclick and by an addEventListener, and
        // toggleAccordion is not idempotent, so two bindings cancel out.
        //
        // "A real accordion" means a header inside an `.accordion` wrapper. The class is also used to
        // style ten flat mobile navigation buttons that call loadPage directly, so counting every
        // `.accordion-header` makes a class-reuse look like eight missing handlers. It is not.
        String html = Files.readString(APP_HTML, StandardCharsets.UTF_8);

        Matcher wrapped = Pattern
                .compile("<div class=\"accordion\">\\s*<div class=\"accordion-header\"([^>]*)>")
                .matcher(html);
        int[] counts = {0, 0};
        while (wrapped.find()) {
            counts[0]++;
            if (!Pattern.compile("onclick=[\"']toggle(Mobile)?Accordion\\(")
                    .matcher(wrapped.group(1)).find()) {
                counts[1]++;
            }
        }

        final int total = counts[0];
        final int withoutToggle = counts[1];
        assertTrue(total > 0, "no wrapped .accordion headers found - the markup shape changed?");
        assertTrue(withoutToggle == 0,
                () -> withoutToggle + " of " + total + " real accordion headers have no toggle "
                        + "handler, so they can never open.");

        // And the JS side must not add a second binding on top of the inline one.
        String sidebar = Files.readString(JS.resolve("sidebar.js"), StandardCharsets.UTF_8);
        assertTrue(!Pattern.compile("querySelectorAll\\s*\\(\\s*[\"']\\.accordion-header[\"']")
                        .matcher(stripComments(sidebar)).find(),
                "sidebar.js must not addEventListener on .accordion-header: the inline onclick already "
                        + "does it, and two bindings make every click a no-op. toggleAccordion reads the "
                        + "open state and writes the opposite, so the panel opens and closes in one tick "
                        + "- which is indistinguishable, from outside, from a collapsed accordion.");
    }

    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
