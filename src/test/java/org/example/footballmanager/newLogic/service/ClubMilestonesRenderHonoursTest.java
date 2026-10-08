package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Club page must actually render the medals it is sent (P2-TROPHY-1).
 *
 * <p><b>Why this is a test that reads a JavaScript file.</b> Because the failure it guards is invisible
 * from Java: the medals were derived, stored, returned by the endpoint, and then the page quietly did not
 * draw them. The Club page looked complete the whole time — five milestone cards, all of them populated —
 * and the trophy row was simply absent. A payload that nobody renders is the same class of defect as the
 * four services with no caller, and it is only visible where the markup is.
 *
 * <p>So these assert the <b>three things that must all be true</b> for the row to exist at all: the board
 * reads the field, it draws a colour per medal, and it names the competition and season beside it.
 */
class ClubMilestonesRenderHonoursTest {

    private static final Path UTILS = Path.of("src/main/resources/static/js/pages/views/utils.js");

    private String utils() throws IOException {
        return Files.readString(UTILS, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the milestone board reads the trophies the endpoint sends it")
    void theBoardReadsTrophies() throws IOException {
        String js = utils();

        assertTrue(js.contains("milestones?.trophies"),
                "buildMilestoneBoardHtml never reads milestones.trophies, so a medal row cannot appear no "
                        + "matter what the endpoint returns");
        assertTrue(js.contains("buildHonoursCardHtml"),
                "and the honours card it delegates to is not there");
    }

    @Test
    @DisplayName("a medal is drawn in its own colour, gold silver and bronze")
    void medalsAreColoured() throws IOException {
        String js = utils();
        assertTrue(js.contains("MEDAL_ORDER"), "no order of medals, so the three are not distinguished");
        for (String medal : List.of("GOLD", "SILVER", "BRONZE")) {
            assertTrue(js.contains(medal),
                    medal + " is never named, so it cannot be styled or filtered");
        }
        assertTrue(js.contains("fm-honour--"),
                "and no per-medal class is emitted, so every medal would look the same");
    }

    @Test
    @DisplayName("the competition and the season are named beside the medal")
    void theRowNamesWhatWasWonAndWhen() throws IOException {
        String js = utils();

        assertTrue(js.contains("t.competitionName"),
                "the row does not name the competition the medal was won in");
        assertTrue(js.contains("t.seasonYear"),
                "the row does not name the season, which is the other half of what was asked for");
    }

    @Test
    @DisplayName("a club with no medals says so rather than drawing an empty box")
    void noMedalsIsSaidOutLoud() throws IOException {
        assertTrue(utils().contains("No medals yet."),
                "a club that has won nothing gets an empty card instead of a sentence");
    }
}