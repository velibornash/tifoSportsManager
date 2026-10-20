package org.tifo.footballmanager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The collectible player card keeps the shape the owner specified (redesign.md R-16).
 *
 * <p>Two things about the card are decisions rather than implementation details, and both are easy
 * to lose in a later refactor without anything breaking visibly:
 *
 * <ul>
 *   <li><b>The rail order.</b> The owner gave it: four skills down the left (stamina, pace,
 *       technique, passing), four down the right (goalkeeper, defending, playmaker, striker). The
 *       order is the owner's, not the code's, so it is written down here.</li>
 *   <li><b>The colours are tokens.</b> A literal hex in the card rules would be invisible in the
 *       light palette — the same defect the token guard exists for elsewhere.</li>
 * </ul>
 *
 * <p>Broken deliberately once before being trusted: swapping two entries in the left rail made
 * this test fail with the order it expected, and restoring the order passed (2026-10-19).
 */
class PlayerCardTest {

    private static final Path CARD_MODULE =
            Path.of("src/main/resources/static/js/pages/views/player-card.js");
    private static final Path DASHBOARD_CSS =
            Path.of("src/main/resources/static/css/dashboard.css");
    private static final Path PAGES_JS =
            Path.of("src/main/resources/static/js/pages.js");

    /** The left rail, in the owner's order. */
    private static final List<String> LEFT_RAIL =
            List.of("stamina", "pace", "technique", "passing");
    /** The right rail, in the owner's order. */
    private static final List<String> RIGHT_RAIL =
            List.of("goalkeeper", "defending", "playmaker", "shooting");

    @Test
    @DisplayName("the card's rails carry the eight skills in the owner's order")
    void theRailsAreInTheOwnersOrder() throws IOException {
        assertTrue(Files.exists(CARD_MODULE), "the card module must exist at " + CARD_MODULE);
        String source = Files.readString(CARD_MODULE);

        List<String> left = railKeys(source, "RAIL_LEFT");
        List<String> right = railKeys(source, "RAIL_RIGHT");

        if (!left.equals(LEFT_RAIL)) {
            fail("The left rail is " + left + " but the owner's order is " + LEFT_RAIL
                    + " - stamina, pace, technique, passing, top to bottom.");
        }
        if (!right.equals(RIGHT_RAIL)) {
            fail("The right rail is " + right + " but the owner's order is " + RIGHT_RAIL
                    + " - goalkeeper, defending, playmaker, striker, top to bottom.");
        }
    }

    @Test
    @DisplayName("the card's rules colour with tokens, so the light palette can read the card")
    void theCardRulesUseTokens() throws IOException {
        assertTrue(Files.exists(DASHBOARD_CSS), "the dashboard sheet must exist at " + DASHBOARD_CSS);
        String css = Files.readString(DASHBOARD_CSS);

        int start = css.indexOf(".fm-pcard {");
        assertTrue(start != -1, "the card must have a rule of its own");
        int end = css.indexOf("@media (max-width: 480px)", start);
        assertTrue(end > start, "the card rules must be findable as a block");

        String block = css.substring(start, end);
        Matcher hex = Pattern.compile("#[0-9a-fA-F]{3,8}\\b").matcher(block);
        while (hex.find()) {
            fail("The card's rules carry a literal colour (" + hex.group()
                    + "). The card is a first-class surface in every palette, so its colours have to be "
                    + "tokens - a literal here is invisible in the light one.");
        }
    }

    @Test
    @DisplayName("the album is wired: a page, a nav entry and a view")
    void theAlbumIsWired() throws IOException {
        assertTrue(Files.exists(PAGES_JS), "the page router must exist at " + PAGES_JS);
        String pages = Files.readString(PAGES_JS);

        if (!pages.contains("case \"album\"")) {
            fail("The album has no case in the page router - a page nobody can open.");
        }
        if (!pages.contains("{ label: 'Album', page: 'album' }")) {
            fail("The album has no entry in the club navigation - a page nobody can reach.");
        }
        if (!Files.exists(Path.of("src/main/resources/static/js/pages/views/album-view.js"))) {
            fail("The album view module is missing.");
        }
    }

    private static List<String> railKeys(String source, String constantName) throws IOException {
        int marker = source.indexOf("const " + constantName + " = [");
        if (marker == -1) {
            fail(constantName + " is missing from the card module.");
        }
        int end = source.indexOf("];", marker);
        String block = source.substring(marker, end);
        Matcher key = Pattern.compile("key:\\s*'([a-zA-Z]+)'").matcher(block);
        List<String> keys = new java.util.ArrayList<>();
        while (key.find()) {
            keys.add(key.group(1));
        }
        if (keys.isEmpty()) {
            fail(constantName + " has no entries - the card would show no skills at all.");
        }
        return keys;
    }
}
