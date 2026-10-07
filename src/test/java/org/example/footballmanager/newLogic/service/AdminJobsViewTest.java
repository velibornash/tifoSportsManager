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
 * The Jobs view is a tab with a real table in it (owner, 2026-10-07).
 *
 * <p>The first version was reported as *"bas je zbrkano"* and the reason was concrete: the table carried
 * the class {@code fm-table}, **which is defined nowhere in the stylesheet**. No padding, no header
 * styling, no row borders, no hover — so it rendered as a wall of text. Meanwhile {@code fm-squad}, the
 * table style every other table in the application uses, was one class away.
 *
 * <p>And Jobs was one more {@code <section>} in a page that had grown to nine, so finding it meant
 * scrolling. It is a tab now.
 *
 * <p><b>Why a source scan.</b> The properties here are "the table uses the class that has styles" and
 * "the panel is behind a tab". Neither is observable without rendering, and a browser test would need the
 * application running — which needs the Playwright setup this repository warns about. The assertion is
 * on the shipped file, and the companion test is the one that proves the class exists.
 */
class AdminJobsViewTest {

    private static final Path ADMIN_VIEW =
            Path.of("src/main/resources/static/js/pages/views/admin-view.js");
    private static final Path CSS =
            Path.of("src/main/resources/static/css/dashboard.css");

    @Test
    @DisplayName("the jobs table uses the table style that actually has styles")
    void theTableUsesAStyleThatExists() throws IOException {
        String view = stripComments(view());

        assertTrue(view.contains("fm-squad fm-jobs-table"),
                "the jobs table carries `fm-squad`, the table class every other table in this "
                        + "application uses. The first version used `fm-table`, which is defined nowhere in "
                        + "the stylesheet - that is why it read as a wall of text.");

        assertTrue(!view.contains("class=\"fm-table\">"),
                "nothing in the admin panel should still use the undefined `fm-table` class");
    }

    @Test
    @DisplayName("the classes the view uses are actually defined in the stylesheet")
    void everyClassTheViewUsesIsDefined() throws IOException {
        String css = stripComments(css());
        String view = stripComments(view());

        for (String token : new String[]{"fm-squad fm-jobs-table", "fm-admin-tabs", "fm-admin-tabpanel",
                "fm-table-wrap", "fm-job-status--failed"}) {
            assertTrue(css.contains(token.split(" ")[token.split(" ").length - 1]),
                    "the view uses '" + token + "' and the stylesheet defines it");
        }
        assertTrue(view.contains("fm-table-wrap"),
                "the table scrolls sideways inside its own wrapper rather than the page, so the header "
                        + "stays put on a phone");
    }

    @Test
    @DisplayName("Jobs is a tab, and hidden panels are hidden rather than merely invisible")
    void jobsIsATabAndHiddenPanelsAreHidden() throws IOException {
        String view = stripComments(view());

        assertTrue(view.contains("data-admin-tab=\"jobs\""), "there is a Jobs tab");
        assertTrue(view.contains("data-admin-panel=\"jobs\""), "and a panel behind it");
        assertTrue(view.contains("panel.hidden = panel.dataset.adminPanel !== name;"),
                "the panels are toggled with `hidden`, not a class - a display:none panel still fetches, "
                        + "so the Jobs tab would have read the server while invisible");

        assertTrue(view.contains("showAdminTab('jobs')") || view.contains("name === 'jobs'"),
                "opening the tab is what loads the jobs");
    }

    @Test
    @DisplayName("a narrow screen drops the two columns that can be dropped and keeps the rest")
    void narrowScreensKeepTheColumnsThatMatter() throws IOException {
        String css = stripComments(css());

        assertTrue(css.contains("@media (max-width: 640px)"),
                "there is a narrow-screen rule");
        assertTrue(css.contains(".fm-jobs-table th:nth-child(2)") && css.contains(".fm-jobs-table td:nth-child(2)"),
                "the trigger column is hidden on a phone");
        assertTrue(css.contains(".fm-jobs-table th:nth-child(4)") && css.contains(".fm-jobs-table td:nth-child(4)"),
                "and so is the next-trigger column - the job's name, its last outcome and its failure "
                        + "count are what the screen is read for");

        assertTrue(!css.contains(".fm-jobs-table th:nth-child(1)"),
                "but never the job name");
        assertTrue(!css.contains(".fm-jobs-table th:nth-child(3)"),
                "nor the last outcome, which is the whole reason the panel exists");
    }

    private String view() throws IOException {
        return Files.readString(ADMIN_VIEW, StandardCharsets.UTF_8);
    }

    private String css() throws IOException {
        return Files.readString(CSS, StandardCharsets.UTF_8);
    }

    /** Comments out, so a rule explained in prose is not mistaken for a rule in the code. */
    private String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}