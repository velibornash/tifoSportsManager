package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The "this row is yours" mark has to survive every palette (redesign.md R-6).
 *
 * <p>The convention is one class, one meaning: {@code is-own} on the row, a left bar and a tint.
 * That convention was implemented with hardcoded colours - a light blue text colour and an amber
 * tint written as literals - which meant the mark read perfectly on the dark cards and became
 * unreadable the moment a light palette existed: light blue text on a cream row.
 *
 * <p>So the rule this test protects is narrow on purpose: the own-row rules may not colour text
 * with a literal. A literal there is how the mark disappears again, and it disappears silently -
 * the page still renders, it just stops saying anything.
 *
 * <p>Broken deliberately once before being trusted: an own-row rule with a literal
 * {@code color: #cfe4ff} failed this test as written, and the rule was tokenised (2026-10-19).
 */
class OwnRowMarkingTest {

    private static final Path DASHBOARD_CSS =
            Path.of("src/main/resources/static/css/dashboard.css");

    /** A hex colour or an rgb()/rgba() literal - anything that is not a token reference. */
    private static final Pattern LITERAL_COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\s*\\(");

    @Test
    @DisplayName("the own-row mark colours its text with tokens, so every palette can read it")
    void ownRowRulesDoNotHardcodeTextColours() throws IOException {
        assertTrue(Files.exists(DASHBOARD_CSS), "the dashboard sheet must exist at " + DASHBOARD_CSS);

        List<String> lines = Files.readAllLines(DASHBOARD_CSS);
        boolean inOwnRowRule = false;
        int depth = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue; // a comment explains the rule; it does not style anything
            }
            if (trimmed.endsWith("{")) {
                // A selector that marks the manager's own row opens a rule that must stay token-driven.
                inOwnRowRule = trimmed.contains("is-own") || trimmed.contains("is-highlighted");
                depth = 1;
                continue;
            }
            if (inOwnRowRule) {
                if (trimmed.startsWith("}")) {
                    inOwnRowRule = false;
                    depth = 0;
                    continue;
                }
                if (trimmed.contains("color:") && LITERAL_COLOUR.matcher(trimmed).find()) {
                    fail("The own-row mark colours its text with a literal (line " + (i + 1) + "):\n  "
                            + trimmed
                            + "\nUse var(--text) / var(--warn-soft) so the mark reads in every palette - "
                            + "a literal here is invisible in the light one.");
                }
            }
        }
    }

    @Test
    @DisplayName("every table that marks the manager's own row also names it")
    void ownRowTablesCarryTheYourClubChip() throws IOException {
        // The chip is what answers "which row was that?" after the table has scrolled away.
        // One implementation, used by the league table and the club cup table.
        Path renderers = Path.of("src/main/resources/static/js/pages-renderers.js");
        Path cupView = Path.of("src/main/resources/static/js/pages/views/club-cup-view.js");
        Path utils = Path.of("src/main/resources/static/js/pages/views/utils.js");

        for (Path file : List.of(renderers, cupView, utils)) {
            assertTrue(Files.exists(file), "missing source file: " + file);
        }

        String utilsSource = Files.readString(utils);
        assertTrue(utilsSource.contains("ownClubChipHtml"),
                "the chip must have one implementation (utils.js) - a second copy is how YOUR CLUB "
                        + "ends up spelled three ways");

        for (Path file : List.of(renderers, cupView)) {
            String source = Files.readString(file);
            Matcher matcher = Pattern.compile("ownClubChipHtml\\s*\\(").matcher(source);
            assertTrue(matcher.find(),
                    file + " marks the own row but never renders the chip - a mark you have to find "
                            + "by eye is the thing this task exists to remove");
        }
    }

    @Test
    @DisplayName("the chip does not wrap: a wrapped chip breaks the row's height and columns")
    void theChipDoesNotWrap() throws IOException {
        String css = Files.readString(DASHBOARD_CSS);
        int start = css.indexOf(".fm-own-chip {");
        assertTrue(start != -1, "the chip needs a rule of its own");
        int end = css.indexOf('}', start);
        assertTrue(end != -1, "the chip rule must be closed");
        String rule = css.substring(start, end);
        assertFalse(rule.isBlank(), "the chip rule is empty");
        assertTrue(rule.contains("white-space: nowrap"),
                "the chip must be nowrap - the club cell is narrow on a phone and a wrapped chip "
                        + "pushes the row's height and columns out of line");
    }
}
