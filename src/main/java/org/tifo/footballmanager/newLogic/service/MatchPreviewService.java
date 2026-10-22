package org.tifo.footballmanager.newLogic.service;

import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.model.ManagerTenure;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.ManagerTenureRepository;
import org.tifo.commonmanager.repository.UserRepository;
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
    private final org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository matchFixtureRepository;
    private final UserRepository userRepository;
    private final ManagerTenureRepository managerTenureRepository;

    public MatchPreviewService(
            ScheduleInsightService insights,
            ShapePreviewService shapePreview,
            org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository matchFixtureRepository,
            UserRepository userRepository,
            ManagerTenureRepository managerTenureRepository) {
        this.insights = insights;
        this.shapePreview = shapePreview;
        this.matchFixtureRepository = matchFixtureRepository;
        this.userRepository = userRepository;
        this.managerTenureRepository = managerTenureRepository;
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
        // The edges the prediction was actually built from, so the cards and the number under them
        // cannot disagree. They used to show formation fitness and bench quality, which are null
        // until a manager has picked eleven for this specific fixture - hence "Not known yet" beside
        // a prediction that was confident from the squad average alone.
        preview.put("homeEdgeOvr", prediction.homeEdgeOvr());
        preview.put("awayEdgeOvr", prediction.awayEdgeOvr());
        preview.put("edgeSwing", prediction.edgeSwing());
        preview.put("homeAdvantageOvr", prediction.homeAdvantage());
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
        org.tifo.footballmanager.newLogic.model.MatchFixture theFixture = fixtureId == null ? null
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

        // **Manager H2H (P0-21).** The two managers in charge, their tenure at the club, and the
        // meetings between them. All existing H2H in the code is club-level; this is the managers'.
        preview.put("managerH2H", managerH2H(home, away, fixtureId));

        preview.put("matchDate", matchDate);
        preview.put("played", played);
        preview.put("fixtureId", fixtureId);
        return preview;
    }

    /**
     * The two managers in charge and their record against each other.
     *
     * <p><b>P0-21.</b> The prerequisite ({@code manager_tenure}) is already written by
     * {@link JobOfferService}; this surfaces it. For each club we find the manager who holds it now and
     * his current tenure. For their past meetings we take the played fixtures between these two clubs
     * and count those in which <i>both</i> of these two managers were in charge at the time - so a
     * meeting counts only when it was genuinely between them, not between their predecessors.
     */
    private Map<String, Object> managerH2H(Team home, Team away, Long fixtureId) {
        Map<String, Object> out = new LinkedHashMap<>();

        User homeManager = managerOf(home);
        User awayManager = managerOf(away);

        out.put("homeManagerName", homeManager == null ? null : homeManager.getUsername());
        out.put("awayManagerName", awayManager == null ? null : awayManager.getUsername());

        ManagerTenure homeTenure = currentTenure(homeManager, home);
        ManagerTenure awayTenure = currentTenure(awayManager, away);
        out.put("homeManagerStartedSeason", homeTenure == null ? null : homeTenure.getStartedSeason());
        out.put("homeManagerStartedWeek", homeTenure == null ? null : homeTenure.getStartedWeek());
        out.put("awayManagerStartedSeason", awayTenure == null ? null : awayTenure.getStartedSeason());
        out.put("awayManagerStartedWeek", awayTenure == null ? null : awayTenure.getStartedWeek());

        int meetings = 0, homeWins = 0, draws = 0, awayWins = 0;
        try {
            List<org.tifo.footballmanager.newLogic.model.MatchFixture> past =
                    matchFixtureRepository.findPlayedBetween(home.getId(), away.getId());
            if (past != null) {
                for (org.tifo.footballmanager.newLogic.model.MatchFixture f : past) {
                    if (f.getPlayedMatch() == null) continue;
                    org.tifo.footballmanager.newLogic.model.Match played = f.getPlayedMatch();
                    Integer season = f.getSeasonYear();
                    Integer week = f.getRoundNumber();
                    // Only count it if both of these two managers were in charge at the time.
                    if (!inCharge(homeManager, home, season, week)) continue;
                    if (!inCharge(awayManager, away, season, week)) continue;
                    meetings++;
                    int hg = played.getHomeGoals();
                    int ag = played.getAwayGoals();
                    boolean homeWasHome = f.getHomeTeam() != null
                            && f.getHomeTeam().getId().equals(home.getId());
                    if (hg == ag) draws++;
                    else if ((homeWasHome && hg > ag) || (!homeWasHome && ag > hg)) homeWins++;
                    else awayWins++;
                }
            }
        } catch (RuntimeException e) {
            // A failure here must not take the whole preview down.
        }
        out.put("meetings", meetings);
        out.put("homeWins", homeWins);
        out.put("draws", draws);
        out.put("awayWins", awayWins);
        return out;
    }

    private User managerOf(Team team) {
        if (team == null || team.getId() == null) return null;
        List<User> users = userRepository.findAllByFootballTeamId(team.getId());
        return users.isEmpty() ? null : users.get(0);
    }

    private ManagerTenure currentTenure(User user, Team team) {
        if (user == null || team == null) return null;
        List<ManagerTenure> open = managerTenureRepository.findByUserIdAndEndedAtIsNull(user.getId());
        for (ManagerTenure t : open) {
            if (t.getTeam() != null && t.getTeam().getId().equals(team.getId())) return t;
        }
        // Fall back to the most recent spell at this club.
        List<ManagerTenure> all = managerTenureRepository.findByTeamIdOrderByStartedAtDesc(team.getId());
        for (ManagerTenure t : all) {
            if (t.getUser() != null && t.getUser().getId().equals(user.getId())) return t;
        }
        return null;
    }

    private boolean inCharge(User manager, Team team, Integer season, Integer week) {
        if (manager == null || team == null || season == null || week == null) return false;
        for (ManagerTenure t : managerTenureRepository.findByUserIdOrderByStartedAtDesc(manager.getId())) {
            if (t.getTeam() == null || !t.getTeam().getId().equals(team.getId())) continue;
            int s0 = t.getStartedSeason() == null ? Integer.MIN_VALUE : t.getStartedSeason();
            int w0 = t.getStartedWeek() == null ? 0 : t.getStartedWeek();
            int s1 = t.getEndedSeason() == null ? Integer.MAX_VALUE : t.getEndedSeason();
            int w1 = t.getEndedWeek() == null ? Integer.MAX_VALUE : t.getEndedWeek();
            if (season < s0 || season > s1) continue;
            if (season == s0 && week < w0) continue;
            if (season == s1 && week > w1) continue;
            return true;
        }
        return false;
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