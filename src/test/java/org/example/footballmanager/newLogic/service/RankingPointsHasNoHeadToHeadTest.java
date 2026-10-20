package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking-points path has no head-to-head term in it ({@code P0-RANK-4}).
 *
 * <p>The owner's rule, stated once: *"snaga tima moze da utice na projekciju rezultata ali ne i na
 * rejting poene"* — a team's strength may move the forecast, never the points.
 *
 * <p>The gap-weighting the owner rejected is {@code RatingEngine.clubK(value, own, opponent)}, which
 * scales a result by the rating gap. Asserted <b>structurally, by reading the source</b>, because the
 * arithmetic version of this check cannot fail: a gap term added inside `RankingPointsEngine` would
 * change some numbers, and the existing 25 tests would simply record the new ones as correct. A source
 * guard is the only assertion that says the term is not there at all.
 *
 * <p><b>Deliberately not asserting that {@code clubK} loses its gap weighting.</b> That weighting is
 * load-bearing for a different, separately-requested behaviour — a club's <i>rating</i> is meant to
 * reward an underdog beating a giant, and the league table shows that rating. Removing it would satisfy
 * the letter of this card by deleting a feature somebody asked for. What must not happen is the rating's
 * opponent-weighting leaking into the points, and that is what these guards hold.
 */
class RankingPointsHasNoHeadToHeadTest {

    /** The services that decide ranking points. If a gap term appears, it appears in one of these. */
    private static final String[] POINTS_SERVICES = {
            "RankingPointsEngine",
            "ClubRankingPointsService",
            "NationalRankingPointsService",
            "MatchPreviewService",
    };

    @Test
    @DisplayName("no service that decides ranking points reads the old head-to-head rating")
    void noPointsServiceUsesTheGapWeightedRating() throws IOException {
        for (String service : POINTS_SERVICES) {
            String source = read(service);
            assertFalse(source.contains("RatingEngine"),
                    service + " references RatingEngine. The old rating is head-to-head - it weights a "
                            + "result by the gap - so reaching for it from the points path is exactly how "
                            + "opponent strength gets back in: " + firstHit(source, "RatingEngine"));
            assertFalse(source.contains("eloRating") || source.contains("EloRating"),
                    service + " reads a club Elo, which is the head-to-head number: "
                            + firstHit(source, "loRating"));
        }
    }

    /**
     * The gap weighting is gone from the rating as well, so there is genuinely one system.
     *
     * <p>It was defended in this file on the grounds that a club's rating is meant to reward an underdog
     * beating a giant, and that removing it would satisfy a card by deleting a feature somebody asked
     * for. The owner overruled that in one line — <b>JEDAN JEDINI REJTING SISTEM</b> — and the distinction
     * I was protecting turns out not to be lost: the forecast already knows a giant is a giant, so a
     * result is rewarded for beating what was predicted rather than for beating a bigger name.
     *
     * <p>Asserted over a sweep of rating gaps rather than at one point, because a reintroduced term
     * would be smallest at equal ratings and largest at the extremes.
     */
    @Test
    @DisplayName("no rating gap survives anywhere in the rating or the points")
    void noGapTermSurvivesInEitherSystem() {
        for (int gap = 0; gap <= 800; gap += 100) {
            double weight = RatingEngine.clubK(
                    org.example.footballmanager.newLogic.model.MatchValue.LEAGUE,
                    1500.0 - gap / 2.0, 1500.0 + gap / 2.0);
            assertEquals(RatingEngine.CLUB_BASE_K, weight, 1e-9,
                    "a gap of " + gap + " still changes the weight (" + weight + "); the rating is "
                            + "head-to-head again and there are two systems");
        }
    }

    private static String firstHit(String source, String needle) {
        int at = source.indexOf(needle);
        if (at < 0) {
            return "";
        }
        int lineStart = source.lastIndexOf('\n', at) + 1;
        int lineEnd = source.indexOf('\n', at);
        return lineEnd < 0 ? source.substring(lineStart) : source.substring(lineStart, lineEnd);
    }

    private static String read(String className) throws IOException {
        Path path = Path.of("src/main/java/org/example/footballmanager/newLogic/service", className + ".java");
        assertTrue(Files.exists(path), "cannot find the source of " + className + " at " + path.toAbsolutePath());
        return stripComments(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Comments out, so this guards the code and not the prose.
     *
     * <p>The first version of this test failed on {@code RankingPointsEngine}'s own javadoc, which names
     * {@code RatingEngine} in a sentence explaining that the old rating is head-to-head and that this one
     * is not. A guard that breaks when somebody documents <i>why</i> a term was removed is a guard that
     * gets deleted to let the documentation land, which is how the term comes back a week later.
     *
     * <p>Block comments including javadoc, then line comments. String literals are left alone: none of
     * these services contains one containing a comment opener, and handling that properly is more code
     * than the risk is worth for a guard over four hand-written files.
     */
    private static String stripComments(String source) {
        String withoutBlocks = source
                .replaceAll("/\\*[\\s\\S]*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
        return withoutBlocks;
    }
}