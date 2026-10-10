package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The match preview, for a fixture that has not been played as much as for one that has.
 *
 * <p><b>Why this exists.</b> The owner reported that the preview had stopped predicting:
 *
 * > **preview vise ne daje prognoze a radile su pre i da ih treba prilagoditi izmenama kad zavrsis**
 *
 * with the screen showing `Not predicted`, `0%0%0%`, `xG 0.00 : 0.00` and `Nothing known yet.` The
 * arithmetic had not moved. What had moved is that the preview was being served by
 * {@code ZoxApiController.previewForFixture}, which returns **every computed field null** by design,
 * while the values on the owner's screenshot came from {@link ScheduleInsightService}.
 *
 * <p>The original reasoning for those nulls was right and is kept: a screen that opens on a fixture must
 * not show a 92% fitness or a 25% draw probability as though it had been worked out. But it was
 * applied too broadly. Squad fitness, absences, position mismatches and the starting eleven genuinely
 * are not knowable before a match. <b>A prediction is exactly the thing that is knowable</b>, and
 * withholding it left the tab with nothing but its own absence.
 *
 * <p>So this computes the prediction for real and leaves the rest null. The prediction is
 * competition-agnostic: it works from two {@link Team}s and their form, so it serves a league fixture, a
 * national cup tie, an international club cup tie, a senior international and a U-21 international
 * identically — which is what the owner asked for when he said every generated match must open to a
 * preview.
 *
 * <p>It is also the input the ranking-points ladder needs: {@link #expectedMargin} is the forecast goals
 * difference that {@link RankingPointsEngine} compares the actual result against.
 */
@Service
public class MatchPreviewService {

    private final ScheduleInsightService insights;
    private final ShapePreviewService shapePreview;
    private final org.example.footballmanager.newLogic.repository.MatchFixtureRepository matchFixtureRepository;

    public MatchPreviewService(
            ScheduleInsightService insights,
            ShapePreviewService shapePreview,
            org.example.footballmanager.newLogic.repository.MatchFixtureRepository matchFixtureRepository) {
        this.insights = insights;
        this.shapePreview = shapePreview;
        this.matchFixtureRepository = matchFixtureRepository;
    }

    /**
     * The forecast goals difference: forecast goals for minus forecast goals against.
     *
     * <p>The one number the ranking system needs from a forecast. Exposed here rather than recomputed at
     * the call site so the ladder and the screen can never disagree about what was predicted.
     */
    public Double expectedMargin(Team home, Team away) {
        ScheduleInsightService.Prediction prediction =
                insights.buildFixtureInsights(home, away).prediction();
        return prediction.expectedHomeGoals() - prediction.expectedAwayGoals();
    }

    /**
     * The full preview payload for an unplayed fixture.
     *
     * @return the preview map, or {@code null} when the fixture has no two teams to forecast
     */
    @Transactional(readOnly = true)
    public Map<String, Object> previewForFixture(MatchFixture fixture) {
        Team home = fixture.getHomeTeam();
        Team away = fixture.getAwayTeam();
        if (home == null || away == null) {
            return null;
        }
        return preview(home, away, fixture.getMatchDate() == null ? null : fixture.getMatchDate().toString(),
                false, fixture.getId());
    }

    /**
     * The same payload for a pair of teams, which is also what a played match needs.
     *
     * <p>Probabilities are emitted as <b>fractions</b> ({@code 0.62}), not percentages, because that is
     * what the preview renderer multiplies by 100 before displaying. {@link ScheduleInsightService}
     * returns whole percentages, so the division happens here, once — and it is the kind of unit
     * mismatch that silently renders every forecast as 0% when it is changed in the wrong place.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Team home, Team away, String matchDate, boolean played, Long fixtureId) {
        ScheduleInsightService.FixtureInsights fixture = insights.buildFixtureInsights(home, away);
        ScheduleInsightService.Prediction prediction = fixture.prediction();

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("homeTeamName", home.getName());
        preview.put("awayTeamName", away.getName());

        // The "OVR" and "Form" on the home/away edge cards.
        preview.put("homeTeamRating", (double) fixture.homeTeamStrength());
        preview.put("awayTeamRating", (double) fixture.awayTeamStrength());
        preview.put("homeRecentForm", fixture.homeTeamForm());
        preview.put("awayRecentForm", fixture.awayTeamForm());

        preview.put("expectedResult", prediction.mostLikelyResult());
        preview.put("homeWinProbability", prediction.homeWinProbability() / 100.0);
        preview.put("drawProbability", prediction.drawProbability() / 100.0);
        preview.put("awayWinProbability", prediction.awayWinProbability() / 100.0);
        preview.put("expectedHomeGoals", prediction.expectedHomeGoals());
        preview.put("expectedAwayGoals", prediction.expectedAwayGoals());
        preview.put("confidence", prediction.confidence());

        // Fitness, mismatches and bench quality are measured against the shape and the eleven the
        // manager has actually chosen (T0-UI-8). They were hardcoded null on the grounds that they were
        // "not knowable before a match", which was true while a fixture had neither a lineup nor a tactic
        // and stopped being true the moment it could have both. They move as the lineup is edited.
        //
        // **Availability stays null**, and that one is genuinely unknowable: it depends on injuries that
        // may happen during the match. Inventing it is what the original all-null preview was right about.
        // Resolved from the id this method is given rather than passed down: the local `fixture` here is
        // the insights record, and a MatchFixture of the same name in the same method is how the shape
        // preview would have been wired to a forecast instead of a fixture.
        org.example.footballmanager.newLogic.model.MatchFixture theFixture = fixtureId == null ? null
                : matchFixtureRepository.findById(fixtureId).orElse(null);
        Map<String, Object> homeShape = shapePreview.forSide(home, theFixture, "HOME");
        Map<String, Object> awayShape = shapePreview.forSide(away, theFixture, "AWAY");

        preview.put("homeFormation", homeShape.getOrDefault("formation", home.getFormation()));
        preview.put("awayFormation", awayShape.getOrDefault("formation", away.getFormation()));
        preview.put("homeFormationFitness", homeShape.get("formationFitness"));
        preview.put("awayFormationFitness", awayShape.get("formationFitness"));
        preview.put("homeBenchQuality", homeShape.get("benchQuality"));
        preview.put("awayBenchQuality", awayShape.get("benchQuality"));
        preview.put("homePositionMismatches", homeShape.get("positionMismatches"));
        preview.put("awayPositionMismatches", awayShape.get("positionMismatches"));
        preview.put("homeHasLineup", homeShape.get("hasLineup"));
        preview.put("awayHasLineup", awayShape.get("hasLineup"));
        preview.put("homeAvailabilityScore", null);
        preview.put("awayAvailabilityScore", null);
        preview.put("homePlayStyle", null);
        preview.put("awayPlayStyle", null);

        preview.put("analysisText", prediction.analysis());
        preview.put("predictionReasons", reasonsFor(prediction, fixture));
        preview.put("homeInsights", insightsFor(fixture, true));
        preview.put("awayInsights", insightsFor(fixture, false));
        preview.put("homeAbsentees", List.of());
        preview.put("awayAbsentees", List.of());
        preview.put("homeLineup", List.of());
        preview.put("awayLineup", List.of());

        preview.put("matchDate", matchDate);
        preview.put("played", played);
        preview.put("fixtureId", fixtureId);
        return preview;
    }

    /** The two strength figures the owner's screenshot showed as "Both sides are of similar quality". */
    private List<Map<String, String>> insightsFor(ScheduleInsightService.FixtureInsights fixture, boolean home) {
        int strength = home ? fixture.homeTeamStrength() : fixture.awayTeamStrength();
        double form = home ? fixture.homeTeamForm() : fixture.awayTeamForm();
        return List.of(
                Map.of("label", "Squad strength", "value", String.valueOf(strength)),
                Map.of("label", "Form", "value", String.valueOf(form)));
    }

    /**
     * Why the forecast came out as it did, in the owner's terms rather than the engine's.
     *
     * <p>A close strengths gap is the single most useful thing to say about a fixture, and it is what
     * the screenshot read: <i>"Both sides are of similar quality"</i>.
     */
    private List<String> reasonsFor(ScheduleInsightService.Prediction prediction,
                                    ScheduleInsightService.FixtureInsights fixture) {
        int gap = Math.abs(fixture.homeTeamStrength() - fixture.awayTeamStrength());
        List<String> reasons = new java.util.ArrayList<>();
        if (gap <= 3) {
            reasons.add("Both sides are of similar quality.");
        } else if (gap >= 15) {
            reasons.add("One side is clearly stronger than the other.");
        } else {
            reasons.add("There is a noticeable gap in squad strength.");
        }
        reasons.add(prediction.analysis());
        return reasons;
    }
}