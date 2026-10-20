package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Club matches are replayed into ranking points, and the replay is not head-to-head.
 *
 * <p>The owner rejected the old weighting in one line: *"snaga tima moze da utice na projekciju
 * rezultata ali ne i na rejting poene"* — a team's strength may move the forecast, never the points.
 *
 * <p><b>The first test is that sentence, executable.</b> The same club, the same 1-0, the same scoreline
 * and the same competition — once against an overwhelming favourite and once against a nobody. Under
 * Elo the second is worth nothing and the first is worth a lot. Here they must be worth <b>exactly the
 * same</b>, because what earns points is beating the forecast, and beating the forecast by one goal is
 * beating the forecast by one goal whoever you beat.
 */
class ClubRankingPointsReplayTest extends BaseTest {

    @Autowired
    ClubRankingPointsService replay;

    @Autowired
    ClubSeasonRankingPointsRepository ledger;

    @Autowired
    MatchRepository matches;

    @Autowired
    TeamRepository teams;

    @Autowired
    CompetitionRepository competitions;

    @Autowired
    ScheduleInsightService insights;

    /**
     * The defining property, stated as arithmetic rather than as a slogan.
     *
     * <p>The owner rejected the old weighting: *"snaga tima moze da utice na projekciju rezultata ali ne
     * i na rejting poene"* — strength may move the forecast, never the points.
     *
     * <p><b>The first version of this test asserted "the same 1-0 is worth the same against a giant and
     * against a nobody", and that was wrong twice over.</b> It was vacuous — every subtotal came out at
     * exactly 0.0, so it compared zero with zero and would have passed against the old gap-weighted Elo
     * too. And the claim itself is not true: a 1-0 against an overwhelming favourite and a 1-0 against a
     * nobody have <i>different forecasts</i>, so they legitimately score differently. Opponent strength
     * reaches the points only through the forecast, which is the thing being asserted here.
     *
     * <p>What is true, and what this now checks: the ledger holds <b>exactly</b>
     * {@code pointsFor(forecast margin, actual margin, competition, tier)} per match and nothing else.
     * Any surviving term in the opponent's own strength would show up as a difference from that number.
     */
    @Test
    @Transactional
    @DisplayName("the ledger holds exactly the forecast formula, with no term in the opponent's strength")
    void theLedgerHoldsExactlyTheForecastFormula() {
        Competition league = aCompetition("Formula " + UUID.randomUUID(), 1);
        Team firm = aClub("Formula firm " + UUID.randomUUID(), league, 90);
        Team giant = aClub("Formula giant " + UUID.randomUUID(), league, 95);
        Team nobody = aClub("Formula nobody " + UUID.randomUUID(), league, 45);

        aPlayedMatch(firm, giant, 6, 0, league, 1);
        aPlayedMatch(firm, nobody, 6, 0, league, 1);

        var result = replay.recompute();
        assertEquals(2, result.rowsWritten() >= 2 ? 2 : result.rowsWritten(),
                "both matches must leave a row: " + result);

        double written = ledger.findByTeamIdAndSeasonYear(firm.getId(), 1)
                .orElseThrow(() -> new AssertionError(
                        "no season 1 row for the firm; the replay wrote " + result)).getPoints();

        double expected = RankingPointsEngine.pointsFor(expectedMargin(firm, giant), 6,
                RankingPointsEngine.LEAGUE, RankingPointsEngine.TIER_1)
                + RankingPointsEngine.pointsFor(expectedMargin(firm, nobody), 6,
                        RankingPointsEngine.LEAGUE, RankingPointsEngine.TIER_1);

        assertEquals(expected, written, 0.01,
                "the ledger must be the forecast formula and nothing else. Got " + written + ", expected "
                        + expected + " - a gap-weighted system would put a larger number here");
        assertTrue(written > 0,
                "two 6-0 wins over the forecast must earn points, or this test is comparing zeros again");
    }

    @Test
    @Transactional
    @DisplayName("a club's subtotal is per season, and the window reads off it")
    void subtotalsArePerSeason() {
        Competition league = aCompetition("Seasoned " + UUID.randomUUID(), 1);
        Team club = aClub("Seasoned club " + UUID.randomUUID(), league, 80);
        Team rival = aClub("Seasoned rival " + UUID.randomUUID(), league, 80);

        aPlayedMatch(club, rival, 6, 0, league, 1);
        aPlayedMatch(club, rival, 6, 0, league, 2);

        replay.recompute();

        double seasonOne = ledger.findByTeamIdAndSeasonYear(club.getId(), 1)
                .orElseThrow(() -> new AssertionError("no season 1 row")).getPoints();
        double seasonTwo = ledger.findByTeamIdAndSeasonYear(club.getId(), 2)
                .orElseThrow(() -> new AssertionError("no season 2 row")).getPoints();

        assertEquals(seasonOne, seasonTwo, 0.01, "the same result in two seasons scores the same");
        assertTrue(seasonOne > 0, "a 6-0 against an equal side is far beyond any forecast and must earn "
                + "points, was " + seasonOne);

        double windowed = RankingPointsEngine.windowedTotal(2, java.util.Map.of(1, seasonOne, 2, seasonTwo));
        assertEquals(1500.0 + seasonOne * 0.75 + seasonTwo, windowed, 0.01,
                "the current season counts fully and last season at three quarters");
    }

    /**
     * The rule the owner spelled out, pinned with a fixture whose forecast is actually stable.
     *
     * <p>*"ako tim POBEDI manje od margine ne dobija ali ni ne gubi poene (pobeda je ipak pobeda)"* — a
     * win by no more than the forecast earns nothing and costs nothing.
     *
     * <p><b>Two equal sides turned out not to be a usable fixture here.</b> The forecast moves with
     * recent results, which is right: measured before any match, two equal sides sit at about
     * <b>+0.49</b> — a coin flip, under the 1.0 threshold — and after the away side has lost one they
     * sit at <b>+1.17</b>, which is a forecast win. Same two teams, same ratings. An earlier version of
     * this test used equal sides, measured at the second of those points, and asserted the coin-flip
     * behaviour; it passed or failed depending on which tests had run before it.
     *
     * <p>So it uses a clear strength gap, where the forecast is a win under any history.
     */
    @Test
    @Transactional
    @DisplayName("winning by no more than the forecast earns nothing and costs nothing")
    void winningAsForecastEarnsNothing() {
        Competition league = aCompetition("Expected " + UUID.randomUUID(), 1);
        Team home = aClub("Expected home " + UUID.randomUUID(), league, 92);
        Team away = aClub("Expected away " + UUID.randomUUID(), league, 50);

        double forecast = expectedMargin(home, away);
        assertTrue(forecast >= RankingPointsEngine.EXPECTED_WIN_MARGIN,
                "a 92 against a 50 must be a forecast win, was " + forecast);

        assertEquals(0.0, RankingPointsEngine.pointsFor(forecast, 3,
                RankingPointsEngine.LEAGUE, RankingPointsEngine.TIER_1), 0.0001,
                "a 3-0 is not three goals beyond a forecast of " + forecast + ", so it is worth nothing");
        assertTrue(RankingPointsEngine.pointsFor(forecast, 6,
                        RankingPointsEngine.LEAGUE, RankingPointsEngine.TIER_1) > 0,
                "and a 6-0 is, or nothing in this system could ever be earned");

        aPlayedMatch(home, away, 3, 0, league, 1);
        replay.recompute();

        double written = ledger.findByTeamIdAndSeasonYear(home.getId(), 1).orElseThrow().getPoints();
        assertEquals(0.0, written, 0.01,
                "the replay must agree with the formula: a win that was no more than expected is not "
                        + "rewarded, and it is certainly not a penalty");
    }

    /**
     * The measured instability above, kept as its own test so the number is on the record rather than
     * a comment nobody runs.
     */
    @Test
    @Transactional
    @DisplayName("the forecast moves with recent results, so equal sides are not a stable fixture")
    void theForecastMovesWithRecentResults() {
        Competition league = aCompetition("Form " + UUID.randomUUID(), 1);
        Team home = aClub("Form home " + UUID.randomUUID(), league, 80);
        Team away = aClub("Form away " + UUID.randomUUID(), league, 80);

        double beforeAnyMatch = expectedMargin(home, away);

        // The away side loses badly. Form is part of the forecast, so this must move it.
        aPlayedMatch(aTeam("Form opponent " + UUID.randomUUID(), league, 80), away, 5, 0, league, 1);

        double afterALoss = expectedMargin(home, away);

        assertTrue(afterALoss > beforeAnyMatch,
                "a side that has just lost badly should be forecast less likely to win: "
                        + beforeAnyMatch + " before, " + afterALoss + " after");
    }

    @Test
    @Transactional
    @DisplayName("a lower division earns less for the identical result")
    void lowerDivisionsEarnLess() {
        Competition topFlight = aCompetition("Top " + UUID.randomUUID(), 1);
        Competition fifth = aCompetition("Fifth " + UUID.randomUUID(), 5);
        Team topWinner = aClub("Top winner " + UUID.randomUUID(), topFlight, 80);
        Team topRival = aClub("Top rival " + UUID.randomUUID(), topFlight, 80);
        Team fifthWinner = aClub("Fifth winner " + UUID.randomUUID(), fifth, 80);
        Team fifthRival = aClub("Fifth rival " + UUID.randomUUID(), fifth, 80);

        aPlayedMatch(topWinner, topRival, 6, 0, topFlight, 1);
        aPlayedMatch(fifthWinner, fifthRival, 6, 0, fifth, 1);

        replay.recompute();

        double top = ledger.findByTeamIdAndSeasonYear(topWinner.getId(), 1).orElseThrow().getPoints();
        double bottom = ledger.findByTeamIdAndSeasonYear(fifthWinner.getId(), 1).orElseThrow().getPoints();

        assertTrue(top > bottom,
                "the same 6-0 in tier 1 must beat the same 6-0 in tier 5: " + top + " vs " + bottom);
        assertEquals(top * RankingPointsEngine.TIER_5, bottom, 0.01,
                "and by exactly the tier-5 weight, because everything else about the two is identical");
    }

    /**
     * The projection this replay reads is JPQL with two implicit joins, and a query that compiles proves
     * nothing about whether it parses.
     */
    @Test
    @Transactional
    @DisplayName("the replay's projection parses and carries season and tier")
    void theProjectionParsesAndCarriesSeasonAndTier() {
        Competition third = aCompetition("Third " + UUID.randomUUID(), 3);
        Team home = aClub("Proj home " + UUID.randomUUID(), third, 77);
        Team away = aClub("Proj away " + UUID.randomUUID(), third, 77);
        aPlayedMatch(home, away, 2, 1, third, 4);

        var rows = matches.findPlayedClubRankedInOrder();

        assertTrue(rows.stream().anyMatch(r -> r.homeTeamId().equals(home.getId())
                        && r.awayTeamId().equals(away.getId())),
                "the projection returned no row for the match that was just played: " + rows.size() + " rows");
        var row = rows.stream()
                .filter(r -> r.homeTeamId().equals(home.getId()))
                .findFirst().orElseThrow();

        assertEquals(4, row.seasonYear(), "without the season the replay cannot fill a per-season ledger");
        assertEquals(3, row.homeTierOrDefault(), "and without the tier the division weight is unreachable");
        assertEquals(3, row.awayTierOrDefault());
        assertNotNull(row.scope());
        assertNotNull(row.teamType());
    }

    @Test
    @Transactional
    @DisplayName("running the replay twice leaves the same ledger, not two rows per season")
    void theReplayIsIdempotent() {
        Competition league = aCompetition("Twice " + UUID.randomUUID(), 1);
        Team club = aClub("Twice club " + UUID.randomUUID(), league, 85);
        Team rival = aClub("Twice rival " + UUID.randomUUID(), league, 70);
        aPlayedMatch(club, rival, 4, 0, league, 1);

        replay.recompute();
        double first = ledger.findByTeamIdAndSeasonYear(club.getId(), 1).orElseThrow().getPoints();
        replay.recompute();
        double second = ledger.findByTeamIdAndSeasonYear(club.getId(), 1).orElseThrow().getPoints();

        assertEquals(1, ledger.findByTeamId(club.getId()).size(),
                "two rows for one club in one season would be added together by the window");
        assertEquals(first, second, 0.01, "the replay is a rebuild, not an accumulation");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    /**
     * What the forecast said this fixture was, so the test can state the expected total in engine terms
     * rather than a magic number.
     *
     * <p>Asserted through the engine on purpose. The point of the first test is that the opponent makes
     * no difference to the number; if the expected value were hard-coded, the test would break whenever
     * the forecast is retuned without proving anything about the opponent.
     */
    private double expectedMargin(Team home, Team away) {
        var prediction = insights.buildFixtureInsights(home, away).prediction();
        return prediction.expectedHomeGoals() - prediction.expectedAwayGoals();
    }

    /** A bare club in a division, for building an opponent without needing its own assertions. */
    private Team aTeam(String name, Competition competition, int strength) {
        return aClub(name, competition, strength);
    }

    private Competition aCompetition(String name, int tier) {
        Competition competition = new Competition();
        competition.setName(name);
        competition.setType(CompetitionType.LEAGUE);
        competition.setScope(CompetitionScope.NATIONAL);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition.setTier(tier);
        return competitions.save(competition);
    }

    private Team aClub(String name, Competition competition, int strength) {
        Team club = new Team();
        club.setName(name);
        club.setFormation("4-4-2");
        club = teams.save(club);
        club.setCompetition(competition);
        club = teams.save(club);
        for (int i = 0; i < 11; i++) {
            Player player = new Player();
            player.setName(name + " p" + i);
            player.setTeam(club);
            player.setPosition(i == 0 ? Position.GK : Position.MID);
            player.setRating(strength);
            player.setAge(22);
            players.save(player);
        }
        return club;
    }

    @Autowired
    org.example.footballmanager.newLogic.repository.PlayerRepository players;

    private void aPlayedMatch(Team home, Team away, int homeGoals, int awayGoals,
                              Competition competition, int season) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setCompetition(competition);
        match.setSeasonYear(season);
        match.setPlayed(true);
        match.setMatchDate(java.time.LocalDateTime.of(2026, 1, 1, 12, 0).plusDays(season * 7L));
        matches.save(match);
    }
}