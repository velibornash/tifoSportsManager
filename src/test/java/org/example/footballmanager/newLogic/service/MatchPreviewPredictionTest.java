package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview predicts again.
 *
 * <p>The owner reported:
 *
 * > **preview vise ne daje prognoze a radile su pre i da ih treba prilagoditi izmenama kad zavrsis**
 *
 * and the screen showed `Not predicted`, `0%0%0%`, `xG 0.00 : 0.00` and `Nothing known yet.` The
 * arithmetic had not moved — the fixture preview was returning every computed field null. These tests
 * exist because "the endpoint returns 200" is not the same claim as "the endpoint predicts", and only
 * the second one is what the owner asked for.
 */
class MatchPreviewPredictionTest extends BaseTest {

    @Autowired
    MatchPreviewService previews;

    @Autowired
    TeamRepository teams;

    @Autowired
    PlayerRepository players;

    @Test
    @Transactional
    @DisplayName("an unplayed fixture is predicted, not left blank")
    void anUnplayedFixtureIsPredicted() {
        Team home = aTeam("Preview home " + UUID.randomUUID(), 78);
        Team away = aTeam("Preview away " + UUID.randomUUID(), 78);
        MatchFixture fixture = aFixture(home, away);

        Map<String, Object> preview = previews.previewForFixture(fixture);

        assertNotNull(preview, "a fixture with two teams must produce a preview");
        assertNotNull(preview.get("expectedResult"),
                "expectedResult was null - this is the 'Not predicted' the owner reported");
        assertTrue(expectedGoals(preview) > 0.0,
                "expected goals were 0.00 : 0.00 - xG must be a real forecast, not a placeholder");

        assertProbability(preview, "homeWinProbability");
        assertProbability(preview, "drawProbability");
        assertProbability(preview, "awayWinProbability");
        assertEquals(1.0, probabilitySum(preview), 0.02,
                "the three probabilities must add up to 1, or the card shows nonsense");
    }

    @Test
    @Transactional
    @DisplayName("probabilities are fractions, because the renderer multiplies them by 100")
    void probabilitiesAreFractions() {
        Team home = aTeam("Fraction home " + UUID.randomUUID(), 80);
        Team away = aTeam("Fraction away " + UUID.randomUUID(), 80);
        Map<String, Object> preview = previews.previewForFixture(aFixture(home, away));

        for (String key : new String[]{"homeWinProbability", "drawProbability", "awayWinProbability"}) {
            double value = probability(preview, key);
            assertTrue(value >= 0.0 && value <= 1.0,
                    key + " is " + value + " - the renderer does Number(value) * 100, so a whole "
                            + "percentage would render as 6200% and a zero as 0%");
        }
    }

    @Test
    @Transactional
    @DisplayName("a stronger side is forecast to win, and the weaker side to lose")
    void strengthDecidesTheForecast() {
        Team strong = aTeam("Strong " + UUID.randomUUID(), 92);
        Team weak = aTeam("Weak " + UUID.randomUUID(), 40);
        Map<String, Object> preview = previews.previewForFixture(aFixture(strong, weak));

        assertEquals("HOME_WIN", preview.get("expectedResult"),
                "a 92-rated side against a 40-rated one is not a coin flip");
        assertTrue(probability(preview, "homeWinProbability") > 0.6,
                "the favourite should be a clear favourite, was " + preview.get("homeWinProbability"));
        assertTrue((Double) preview.get("expectedHomeGoals")
                        > (Double) preview.get("expectedAwayGoals"),
                "and it should be forecast to score more");
    }

    /**
     * The top rung of the ranking ladder must be reachable by some real fixture.
     *
     * <p>This exists because {@code EXPECTED_WIN_MARGIN} was 2.0 and the forecast can only ever produce
     * a margin between -1.10 and +1.79 — measured across every pairing of the 38-92 strength range. So
     * <i>expected to win</i> was unreachable, and the strongest possible favourite was scored as a coin
     * flip. Nothing in the arithmetic would ever have said so; it took measuring the model to find it.
     */
    @Test
    @Transactional
    @DisplayName("the top rung is reachable: the strongest fixture counts as an expected win")
    void theTopRungIsReachable() {
        Team strongest = aTeam("Reachable strong " + UUID.randomUUID(), 92);
        Team weakest = aTeam("Reachable weak " + UUID.randomUUID(), 38);

        double margin = previews.expectedMargin(strongest, weakest);

        assertTrue(margin > RankingPointsEngine.EXPECTED_WIN_MARGIN,
                "the widest gap the model can produce forecasts a margin of " + margin
                        + ", which must clear the " + RankingPointsEngine.EXPECTED_WIN_MARGIN
                        + " threshold - otherwise expected-to-win never happens and the ladder's top "
                        + "rungs are dead code");
        assertEquals(RankingPointsEngine.Expected.WIN, RankingPointsEngine.expected(margin));
    }

    /**
     * The reason the forecast lives here and not in the ZOX stub: this is the number the ranking-points
     * ladder compares an actual result against.
     */
    @Test
    @Transactional
    @DisplayName("the expected margin is the forecast goals difference the ranking ladder needs")
    void theExpectedMarginIsAvailable() {
        Team strong = aTeam("Margin strong " + UUID.randomUUID(), 92);
        Team weak = aTeam("Margin weak " + UUID.randomUUID(), 40);

        Double margin = previews.expectedMargin(strong, weak);

        assertNotNull(margin);
        assertTrue(margin > RankingPointsEngine.EXPECTED_WIN_MARGIN,
                "expected to win by " + margin + ", so RankingPointsEngine.expected() must call it a win "
                        + "- otherwise the ladder pays out on the wrong results");
    }

    /**
     * The original all-null fixture preview was right about these. They are not knowable before a match
     * and inventing them is worse than omitting them.
     */
    @Test
    @Transactional
    @DisplayName("fitness and absences stay null, because they are not knowable before a match")
    void unknowableFieldsStayNull() {
        Team home = aTeam("Honest home " + UUID.randomUUID(), 75);
        Team away = aTeam("Honest away " + UUID.randomUUID(), 75);
        Map<String, Object> preview = previews.previewForFixture(aFixture(home, away));

        assertNull(preview.get("homeFormationFitness"), "a placeholder fitness is what this fixes");
        assertNull(preview.get("awayFormationFitness"));
        assertNull(preview.get("homeAvailabilityScore"));
        assertNull(preview.get("awayAvailabilityScore"));
        assertEquals(List0(), preview.get("homeAbsentees"));
    }

    @Test
    @Transactional
    @DisplayName("the analysis says why, which is what 'Why this prediction' renders")
    void theAnalysisExplainsItself() {
        Team home = aTeam("Reason home " + UUID.randomUUID(), 80);
        Team away = aTeam("Reason away " + UUID.randomUUID(), 80);
        Map<String, Object> preview = previews.previewForFixture(aFixture(home, away));

        assertNotNull(preview.get("analysisText"), "'Why this prediction' showed nothing known");
        @SuppressWarnings("unchecked")
        java.util.List<String> reasons = (java.util.List<String>) preview.get("predictionReasons");
        assertTrue(reasons != null && reasons.stream().anyMatch(r -> r.toLowerCase().contains("quality")),
                "the reasons should describe the quality gap, as the owner's screenshot did: " + reasons);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private static java.util.List<String> List0() {
        return java.util.List.of();
    }

    private static double probability(Map<String, Object> preview, String key) {
        return (Double) preview.get(key);
    }

    private static double expectedGoals(Map<String, Object> preview) {
        return (Double) preview.get("expectedHomeGoals") + (Double) preview.get("expectedAwayGoals");
    }

    private static double probabilitySum(Map<String, Object> preview) {
        return probability(preview, "homeWinProbability")
                + probability(preview, "drawProbability")
                + probability(preview, "awayWinProbability");
    }

    private static void assertProbability(Map<String, Object> preview, String key) {
        double value = probability(preview, key);
        assertTrue(value > 0.0, key + " was " + value + "; a forecast where one outcome is impossible "
                + "is not a forecast, and the card would show 0% for it");
    }

    private MatchFixture aFixture(Team home, Team away) {
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setMatchDate(java.time.LocalDateTime.now().plusDays(1));
        return fixture;
    }

    private Team aTeam(String name, int strength) {
        Team team = new Team();
        team.setName(name);
        team.setFormation("4-4-2");
        team = teams.save(team);
        // Eleven players, so the snapshot has a core eleven to average rather than an empty squad.
        for (int i = 0; i < 11; i++) {
            Player player = new Player();
            player.setName(name + " player " + i);
            player.setTeam(team);
            player.setPosition(i == 0 ? Position.GK : Position.MID);
            player.setRating(strength);
            player.setAge(20 + (i % 10));
            players.save(player);
        }
        return team;
    }
}