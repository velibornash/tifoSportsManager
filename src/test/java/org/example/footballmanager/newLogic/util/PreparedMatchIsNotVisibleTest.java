package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.service.MatchdayScheduler;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hour before kickoff, measured on the real service (owner, 2026-10-11).
 *
 * <p><b>Why this test exists separately.</b> {@code MatchdayPreparePublishTest} drives the scheduler
 * with a mocked {@link SimMatchService}, so it proves the scheduler calls the right method and cannot
 * prove that the method behaves. A mutation that made {@code prepareMatch} set {@code played = true} —
 * publishing every result an hour early — passed that test green. It failed only here, and it was
 * deleted on sight.
 *
 * <p>So this one runs the real bean: persist a real match through {@code prepareMatch}, and look at
 * what the database says afterwards. The claim under test is about data a manager can reach, not about a
 * method call.
 *
 * <p>The contract:
 *
 * <ul>
 *   <li>{@code prepareMatch} stores the match and leaves {@code fixture.played == false};</li>
 *   <li>the fixture is therefore invisible to every read path that filters on played;</li>
 *   <li>{@code persist} — the ordinary path — does set it.</li>
 * </ul>
 */
class PreparedMatchIsNotVisibleTest extends BaseTest {

    private static final int SEASON = 1;
    private static final int WEEK = 1;
    private static final int DAY = 3;

    @Autowired private SimMatchService sim;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchRepository matches;
    @Autowired private org.example.footballmanager.newLogic.repository.TeamRepository teams;
    @Autowired private org.example.footballmanager.newLogic.service.ClubRatingService clubRatings;
    @Autowired private org.example.footballmanager.newLogic.repository.CompetitionRepository competitions;

    @Test
    @Transactional
    @DisplayName("a prepared match is stored but not played, so it cannot be read an hour early")
    void preparingDoesNotPublish() {
        MatchFixture fixture = newFixture();

        sim.prepareMatch(fixture, score(fixture, 2, 1), -1L, null);

        // The row is really there — this is not a "the save silently did nothing" green.
        assertNotNull(fixture.getId(), "the prepared match must be persisted");
        assertTrue(matches.findById(fixture.getId()).isPresent(),
                "the match row must exist before kickoff, otherwise publishing has nothing to release");

        // And yet the fixture still reads as not played. This is the whole point of the hour.
        assertFalse(fixture.isPlayed(), "preparing must not mark the fixture played");
        assertNull(fixture.getPlayedMatch(), "a prepared fixture must not expose a played match");
        assertNotNull(fixture.getPreparedMatchId(), "the prepared match id must be recorded for kickoff");
        assertTrue(fixture.isPrepared(), "the fixture must report itself as prepared");
        assertNotNull(fixture.getPreparedAt(), "preparedAt must be stamped");

        // What the game reads. findUnpublishedOnDay is what the publish job selects on.
        assertFalse(fixtures.findBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(SEASON, WEEK, DAY)
                        .stream().noneMatch(f -> f.getId().equals(fixture.getId())),
                "the prepared fixture must still be among the day's unplayed fixtures");

        // And the match itself carries no result anyone is shown yet.
        Match stored = matches.findById(fixture.getId()).orElseThrow();
        assertEquals(2, stored.getHomeGoals());
        assertTrue(stored.isPlayed(), "the match row itself is a completed match, just not a published one");
    }

    @Test
    @Transactional
    @DisplayName("the ordinary path still publishes, so the change did not disable matchdays")
    void theOrdinaryPathStillPublishes() {
        MatchFixture fixture = newFixture();

        sim.persist(fixture, score(fixture, 1, 0), -1L, null);

        assertTrue(fixture.isPlayed(), "persist must still mark the fixture played");
        assertNotNull(fixture.getPlayedMatch());
        assertFalse(fixture.isPrepared(), "an ordinary persist is not a prepared match");
        assertNull(fixture.getPreparedMatchId());
    }

    @Test
    @Transactional
    @DisplayName("a matchday commits as it goes, so one failure does not cost the evening")
    void eachMatchCommitsForItself() {
        // The regression this guards is not visible in a unit test: one @Transactional around a
        // whole matchday held 155 matches for twenty minutes and committed none of them, so a match
        // id would be logged that never existed in the table. PreparedMatchIsNotVisibleTest cannot
        // see it because it is @Transactional itself and rolls back either way.
        //
        // So this asserts the shape rather than the timing: the scheduler method must not be
        // transactional, and each match must commit through SimMatchService.
        boolean schedulerIsTransactional = java.util.Arrays.stream(
                        MatchdayScheduler.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("prepareUnplayedOn"))
                .anyMatch(m -> m.isAnnotationPresent(Transactional.class));

        assertFalse(schedulerIsTransactional,
                "prepareUnplayedOn must not wrap a whole matchday in one transaction");

        // ...and JobRunner runs every job inside one REQUIRES_NEW transaction, so the fixtures have to
        // escape it explicitly. Dropping this template silently puts a matchday back into one
        // transaction, which is invisible to every unit test and very visible at 155 matches.
        assertTrue(java.util.Arrays.stream(MatchdayScheduler.class.getDeclaredFields())
                        .anyMatch(f -> f.getName().equals("perFixture")),
                "each fixture must commit in its own transaction, not the job's");

        boolean simPersistsTransactionally = java.util.Arrays.stream(
                        SimMatchService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("prepareMatch"))
                .anyMatch(m -> m.isAnnotationPresent(Transactional.class));

        assertTrue(simPersistsTransactionally,
                "prepareMatch must be transactional, or each fixture would not commit on its own");
    }

    @Test
    @Transactional
    @DisplayName("a prepared match is outside the rating replay until it is published")
    void ratingsWaitForPublication() {
        // The owner, asked directly: a rating should move when the result is published, not when the
        // football is played an hour earlier. Every rating service here is a full replay over the
        // played match table rather than an increment, so excluding a prepared match defers its effect
        // to the next replay instead of losing it - which is why this is safe as a query predicate with
        // no bookkeeping behind it.
        //
        // The assertion is on the replay's own count rather than on a club's rating, because
        // recompute() only rates clubs that carry a division and a bare test team does not: asserting
        // on eloRating here would have passed forever without the query being right.
        MatchFixture fixture = newFixture();

        int before = clubRatings.recompute().matchesReplayed();

        sim.prepareMatch(fixture, score(fixture, 4, 0), -1L, null);
        assertEquals(before, clubRatings.recompute().matchesReplayed(),
                "a prepared match must stay out of the replay an hour before it is published");

        fixture.setPlayed(true);
        fixture.setPlayedMatch(matches.findById(fixture.getPreparedMatchId()).orElseThrow());
        fixtures.save(fixture);

        assertEquals(before + 1, clubRatings.recompute().matchesReplayed(),
                "once published, the same match must be in the replay");
    }

    private MatchFixture newFixture() {
        var competition = new org.example.footballmanager.newLogic.model.Competition();
        competition.setName("ZZ Prepared " + java.util.UUID.randomUUID());
        competition.setType(org.example.footballmanager.newLogic.model.CompetitionType.LEAGUE);
        competition.setTier(1);
        // Without this the club rating replay skips the fixture entirely: its query separates the
        // club pool from the national one on teamType, and a null never matches CLUB.
        competition.setTeamType(org.example.footballmanager.newLogic.model.CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(aTeam("ZZ Prepared Home " + java.util.UUID.randomUUID()));
        fixture.setAwayTeam(aTeam("ZZ Prepared Away " + java.util.UUID.randomUUID()));
        fixture.setSeasonYear(SEASON);
        fixture.setWeekNumber(WEEK);
        fixture.setDayNumber(DAY);
        fixture.setMatchDate(java.time.LocalDateTime.now());
        return fixtures.save(fixture);
    }

    private org.example.footballmanager.newLogic.model.Team aTeam(String name) {
        org.example.footballmanager.newLogic.model.Team team =
                new org.example.footballmanager.newLogic.model.Team();
        team.setName(name);
        return teams.save(team);
    }

    private org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome score(
            MatchFixture fixture, int home, int away) {
        return new org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome(
                fixture.getHomeTeam().getName(), fixture.getAwayTeam().getName(), home, away,
                3600L, 90, "4-4-2", "4-4-2", 50.0, 50.0, 1.4, 1.1,
                teamOutcome(fixture.getHomeTeam().getName(), home),
                teamOutcome(fixture.getAwayTeam().getName(), away),
                new java.util.ArrayList<>(), java.util.List.of(), null, null, null);
    }

    private static org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.TeamOutcome
            teamOutcome(String name, int goals) {
        return new org.example.footballmanager.newLogic.sim.result.ProposalMatchOutcome.TeamOutcome(
                name, goals, 12, 5, 330, 290, 8, 6, 4, 2, 2,
                goals > 0 ? 2 : 3, 5, 7, 9, 2, 1, 0, 0, 0,
                goals >= 2 ? 52.0 : 40.0, 6.4);
    }
}