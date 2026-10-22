package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.ProductionSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The away squad table keeps its numbers on a phone, in the First Team's column order.
 *
 * <p><b>What the owner saw.</b> *"losa je tabela. ime je lose prelomljeno, tabela se na pravom mob
 * uredjajku ne skrola udesno (na developee mode moze)"* — and then, for the shape: *"bolje ime da se
 * skalira i raspored kolona kao kod first team ... isto kao sto je za first team"*.
 *
 * <p><b>One cause, two symptoms.</b> {@code html.is-mobile-nav} hides the appearances, the goals and the
 * assists of every squad table, and this table was no exception. The three numbers the screen exists for
 * were gone, the columns that remained fitted the screen, and a table that fits has nothing to scroll
 * sideways; in a narrow desktop window the class is not set, the columns stay, and the table scrolls.
 * That is the difference between his phone and developer mode.
 *
 * <p><b>Why a source guard.</b> What has to hold is a shape in two files: the table carries the class the
 * First Team carries, the header cells are in the First Team's order, and the name has a floor under it.
 * The rendered result was measured in the browser — at 393px the table is 509px inside a 347px wrap,
 * scrolls 162px, shows all eight columns, and "Ivica Tomić" sits on one line as it does on the First
 * Team's table while the longer names take two. This test is the part that stays: remove the class or
 * reorder the header and it fails.
 */
class AwaySquadKeepsItsStatsOnAPhoneTest {

    /** The First Team's order, and this page's own Form column after the four numbers it was asked for. */
    private static final List<String> FIRST_TEAM_ORDER =
            List.of("Name", "Av Rat", "Apps", "Gls", "Ast", "Form", "Position", "Age");

    @Test
    @DisplayName("the away table carries the class that keeps Apps, Gls and Ast on a phone")
    void theStatsColumnsSurviveAPhone() throws IOException {
        String table = awayTableTag();

        assertTrue(table.contains("fm-squad--away"),
                "the away table lost its own marker class, so the fix and its CSS no longer have a target: " + table);
        assertTrue(table.contains("fm-squad--stats-first"),
                "without fm-squad--stats-first, html.is-mobile-nav hides Apps, Gls and Ast on a phone - "
                        + "the table then fits the screen, nothing scrolls sideways, and the name is squeezed: " + table);
    }

    @Test
    @DisplayName("its columns are the First Team's order, with Form after Ast")
    void theColumnsFollowTheFirstTeam() throws IOException {
        assertEquals(FIRST_TEAM_ORDER, awayHeaderLabels(),
                "the away table's columns are not the order the owner asked for on 2026-10-22: "
                        + "First Team order - Name, Av Rat, Apps, Gls, Ast, Position, Age - with this page's Form after Ast");
    }

    @Test
    @DisplayName("the name is not squeezed into what is left beside the photo")
    void theNameIsNotSqueezed() throws IOException {
        String rule = cssRule(".fm-squad--away .sq-player-cell .sq-player-link");

        assertTrue(rule.contains("min-width"),
                "the name link has no minimum width, so it shares the line with the 30px photo and every name "
                        + "in the world breaks into two: " + rule);
    }

    /**
     * The away squad table's opening tag, as the renderer writes it.
     *
     * <p>Found by the marker class rather than by position: a renderer that grows a second table must not
     * silently move this test onto the wrong one.
     */
    private static String awayTableTag() throws IOException {
        String source = renderer();
        int marker = source.indexOf("fm-squad--away");
        assertTrue(marker >= 0, "no table in " + "js/pages-renderers.js" + " carries fm-squad--away");

        int open = source.lastIndexOf("<table", marker);
        int close = source.indexOf('>', marker);
        assertTrue(open >= 0 && close > open, "the marker class is not on a <table ...> tag");

        return source.substring(open, close + 1);
    }

    /** The header labels of the away table, in source order, with the sort arrows stripped. */
    private static List<String> awayHeaderLabels() throws IOException {
        String source = renderer();
        int marker = source.indexOf("fm-squad--away");
        assertTrue(marker >= 0, "no away squad table in js/pages-renderers.js");

        int head = source.indexOf("<thead>", marker);
        int end = source.indexOf("</thead>", head);
        assertTrue(head >= 0 && end > head, "the away table has no <thead>");

        // "<th" must not match "<thead": the label cells are <th class=...> or <th>.
        Matcher cells = Pattern.compile("<th(?![A-Za-z])[^>]*>(.*?)</th>", Pattern.DOTALL)
                .matcher(source.substring(head, end));
        List<String> labels = new ArrayList<>();
        while (cells.find()) {
            labels.add(cells.group(1)
                    .replace("\\u21c5", "")
                    .replace("\u21c5", "")
                    .replaceAll("\\s+", " ")
                    .trim());
        }
        return labels;
    }

    /** The declaration block of one CSS rule, so a rule can be read as the browser would see it. */
    private static String cssRule(String selector) throws IOException {
        String css = Files.readString(ProductionSource.staticFile("css/dashboard.css"), StandardCharsets.UTF_8);
        int start = css.indexOf(selector);
        assertTrue(start >= 0, "no CSS rule for " + selector + " in css/dashboard.css");

        int open = css.indexOf('{', start);
        int close = css.indexOf('}', open);
        assertTrue(open > start && close > open, "the rule for " + selector + " has no declaration block");

        return css.substring(open, close);
    }

    private static String renderer() throws IOException {
        return Files.readString(ProductionSource.staticFile("js/pages-renderers.js"), StandardCharsets.UTF_8);
    }
}
