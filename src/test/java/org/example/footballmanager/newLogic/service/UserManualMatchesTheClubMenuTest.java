package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manual describes the Club menu, so the two are compared rather than trusted (owner, 2026-10-10,
 * T1-18).
 *
 * <p><b>Why this exists.</b> T1-18 was found by hand: {@code userManual.md} listed Club options that have
 * no menu entry, while {@code Loans} — which has been in the menu the whole time — was not documented at
 * all. Both halves are the same defect, and both are silent: the manual is prose, so nothing complains
 * when it drifts.
 *
 * <p><b>What it compares.</b> The labels in {@code buildClubActionsHtml} — the function that builds the
 * row the manager actually clicks — against the {@code ###} headings of manual §4. It reads the source
 * rather than a copy, so a menu entry added tomorrow fails here rather than in a support question.
 *
 * <p><b>Stadium and Friendlies are not failures.</b> Both work; neither is a Club menu entry. Stadium is
 * opened from Club Profile and Friendlies is a panel on the Club page, and the manual says so under a
 * heading that is deliberately not an option. They are excluded by name rather than by pattern, because
 * "not a menu entry" is the fact being asserted and not something to be inferred.
 */
class UserManualMatchesTheClubMenuTest {

    private static final Path MENU_SOURCE =
            Path.of("src/main/resources/static/js/pages-renderers.js");
    private static final Path MANUAL = Path.of("userManual.md");

    /** Reached from elsewhere in the Club area, and documented as such. */
    private static final Set<String> REACHED_ELSEWHERE = Set.of("Stadium", "Friendlies");

    @Test
    @DisplayName("the Club menu and manual section 4 list the same options, in the same order")
    void theMenuAndTheManualAgree() throws IOException {
        List<String> menu = clubMenuLabels();
        List<String> manual = manualClubOptionHeadings();

        assertEquals(menu, manual,
                "the Club action row and userManual.md section 4 disagree.\n"
                        + "  menu:   " + menu + "\n"
                        + "  manual: " + manual + "\n\n"
                        + "A manual that promises a button which is not there sends a manager looking for "
                        + "it, and a manual that omits a button which is there is worse — it reads as a "
                        + "missing feature. Both directions have happened in this file.");
    }

    @Test
    @DisplayName("every documented Club option is either a menu entry or named as reached elsewhere")
    void nothingIsPromisedThatCannotBeFound() throws IOException {
        Set<String> menu = new LinkedHashSet<>(clubMenuLabels());

        for (String heading : manualClubOptionHeadings()) {
            assertTrue(menu.contains(heading) || REACHED_ELSEWHERE.contains(heading),
                    "the manual lists '" + heading + "' as a Club option, but there is no menu entry for it "
                            + "and it is not one of " + REACHED_ELSEWHERE + ", which are documented as "
                            + "reached from elsewhere. A manager looking for the button will not find it.");
        }
    }

    @Test
    @DisplayName("Stadium and Friendlies are documented as reached from elsewhere, not as menu entries")
    void theTwoExceptionsSayWhereTheyAre() throws IOException {
        String manual = Files.readString(MANUAL, StandardCharsets.UTF_8);

        assertTrue(manual.contains("### Two screens that are not in that row"),
                "the manual needs a heading for the screens that work but are not in the Club action row, "
                        + "or a reader assumes the option list above is exhaustive and wrong");
        assertTrue(manual.contains("opened from the **Club Profile** page"),
                "Stadium is opened from Club Profile, and the manual should say so rather than leave the "
                        + "manager to find it");
        assertTrue(manual.contains("a panel on the **Club page** itself"),
                "Friendlies is a panel on the Club page, and the manual should say so");
    }

    @Test
    @DisplayName("the check is reading the real menu, not a list someone maintained by hand")
    void theCheckReadsTheRealMenu() throws IOException {
        String source = Files.readString(MENU_SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("export function buildClubActionsHtml"),
                "buildClubActionsHtml is the function that builds the row a manager clicks; if it has been "
                        + "renamed this test is reading nothing and passing vacuously");
        assertTrue(!clubMenuLabels().isEmpty(),
                "no labels were parsed out of the menu, which means the extraction broke and every "
                        + "assertion above would pass against an empty list");
    }

    /**
     * The {@code label} of every entry in the Club action row, in source order.
     *
     * <p>Read from {@code buildClubActionsHtml} only — the League, Training and Community rows are built
     * by sibling functions with the same shape, and a whole-file scan would pull all of them in.
     */
    private List<String> clubMenuLabels() throws IOException {
        String source = Files.readString(MENU_SOURCE, StandardCharsets.UTF_8);
        int start = source.indexOf("export function buildClubActionsHtml");
        int end = source.indexOf("export function buildTrainingActionsHtml");
        assertTrue(start >= 0 && end > start,
                "could not locate buildClubActionsHtml in pages-renderers.js");

        List<String> labels = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{ label: '([^']+)'").matcher(source.substring(start, end));
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        return labels;
    }

    /** The {@code ###} headings of manual §4, excluding the heading that explains the two exceptions. */
    private List<String> manualClubOptionHeadings() throws IOException {
        String manual = Files.readString(MANUAL, StandardCharsets.UTF_8);
        int start = manual.indexOf("## 4. Club options");
        int end = manual.indexOf("## 5. League options");
        assertTrue(start >= 0 && end > start, "could not locate section 4 of userManual.md");

        List<String> headings = new ArrayList<>();
        Matcher matcher = Pattern.compile("^### (.+)$", Pattern.MULTILINE)
                .matcher(manual.substring(start, end));
        while (matcher.find()) {
            String heading = matcher.group(1).trim();
            if (!heading.startsWith("Two screens")) {
                headings.add(heading);
            }
        }
        return headings;
    }
}