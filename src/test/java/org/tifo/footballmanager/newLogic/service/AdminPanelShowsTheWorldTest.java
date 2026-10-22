package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.ProductionSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin panel has to show what the world holds, and say which button builds it.
 *
 * <p><b>What this exists for.</b> The panel's world readout counted the countries, the national
 * sides, the squads on them and the legacy rows, and <b>not one club, player or fixture</b> — so it
 * said <em>"World is whole"</em> for a world with no football in it, and <em>"World needs repair"</em>
 * for a world that was fine, without saying which country was at fault or which of the seven tools
 * would put it right. The club count was in the payload the whole time and the panel never read it.
 *
 * <p><b>Why a source scan.</b> The property is about which fields of the answer the panel reads and
 * which buttons it names — neither is observable from the response alone, and rendering the panel
 * needs a browser this repository's tests do not have. The scan reads the real file, so it cannot
 * pass against a panel that renders nothing.
 *
 * <p><b>Read with the comments stripped.</b> The first version of an assertion in this repository
 * matched a sentence inside a handler's own comment, which quotes the old text precisely in order
 * to record that it was wrong — so fixing the code turned the guard red. Three times in one session
 * that happened. The fix is always to strip the comments, never to delete the explanation.
 */
class AdminPanelShowsTheWorldTest {

    private static final Path ADMIN_VIEW =
            ProductionSource.staticFile("js/pages/views/admin-view.js");

    /**
     * The world's substance. Each one is a field the backend sends and the panel has to show.
     *
     * <p><b>The markup, not the field name.</b> The bare field name also appears in the sentence
     * that explains what is missing, so a guard that looked for the name alone passed against a
     * panel that showed nothing at all — it was made to fail against exactly that, and it did not.
     * What has to be on the panel is the card.
     */
    private static final String[] COUNT_CARDS = {
            "activeClubs", "activePlayers", "activeFixtures"
    };

    @Test
    @DisplayName("the panel shows the world's substance")
    void thePanelShowsTheSubstance() throws IOException {
        String code = code();

        for (String field : COUNT_CARDS) {
            assertTrue(code.contains("<strong>${w." + field + "}</strong>"),
                    "the '" + field + "' count is shown as a card in the grid — a number in the "
                            + "sentence but not on the panel is a number nobody is shown");
        }
        assertTrue(code.contains("w.sidesWithoutSquad"),
                "and the panel names the national sides that have no squad, so that 'the world needs "
                        + "repair' says which country is at fault");
    }

    @Test
    @DisplayName("the panel labels the counts with the country that is played")
    void thePanelLabelsTheCounts() throws IOException {
        String code = code();

        assertTrue(code.contains("w.activeCountries"),
                "the counts are the played country's, and the label has to say which country that "
                        + "is — the world holds fourteen thousand clubs and the country you play "
                        + "holds three hundred and ten, and a panel that shows the first number "
                        + "under the second country's name is a lie with a font on it");
    }

    @Test
    @DisplayName("the panel names the button that builds what is missing")
    void thePanelNamesTheButton() throws IOException {
        String code = code();

        assertTrue(code.contains("w.nextStep"),
                "the backend decides which button answers the world — it is the only place that "
                        + "knows what is missing — and the panel has to read that decision");
        assertTrue(code.contains("Press <strong>Initialize DB</strong>"),
                "and it has to say which button that is: a world with no clubs is built by "
                        + "Initialize DB, and 'Repair world' does not build clubs at all");
        assertTrue(code.contains("Press <strong>Repair world</strong>"),
                "and the other half: a world whose catalogue is damaged is repaired by Repair world");
    }

    @Test
    @DisplayName("the panel says the world is whole only when the backend says so")
    void thePanelDoesNotDecideForItself() throws IOException {
        String code = code();

        assertTrue(code.contains("w.healthy"),
                "the verdict is the backend's — the panel asks and shows the answer, and it must not "
                        + "decide on its own that a world is whole");
        assertFalse(Pattern.compile("\\$\\{\\s*w\\.clubs\\s*\\}").matcher(code).find(),
                "and it must not show the world-wide club count where the played country's count "
                        + "belongs");
    }

    // ── parsing ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The file with its comments removed.
     *
     * <p>String literals are preserved: a quoted path inside a string is code, and stripping quotes
     * as well would hide a real defect. Block comments go first so that a {@code //} inside one
     * cannot confuse the line pass.
     *
     * <p><b>No DOTALL on the two line patterns.</b> With it, {@code .} matches a newline and
     * {@code .*} runs to the end of the file, so the strip deletes everything below the first
     * {@code //} comment and the guard is left looking at nothing. Without it, {@code .} stops at
     * the line end and only the comment goes. {@code \s*} still spans newlines, which is harmless
     * here — it takes blank lines with the comment, never code.
     */
    private String code() throws IOException {
        String src = Files.readString(ADMIN_VIEW, StandardCharsets.UTF_8);
        src = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(src).replaceAll(" ");
        src = Pattern.compile("(?m)^\\s*//.*$").matcher(src).replaceAll("");
        src = Pattern.compile("(?m)[ \\t]+//.*$").matcher(src).replaceAll("");
        return src;
    }
}
