package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.GameDay;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.CurrentRoundSimulationStateService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.service.TrainingProgressionService;
import org.example.footballmanager.newLogic.sim.SimMatchService;
import org.example.footballmanager.newLogic.service.AsyncSimulationRunner;
import org.example.footballmanager.newLogic.service.ClubRatingService;
import org.example.footballmanager.newLogic.service.GameClockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1: a request must not read the world's fixture table to answer a question about one round.
 *
 * <p>Four endpoints in {@code SimulationController} asked {@code matchFixtureRepository.findAll()} and
 * then filtered {@code (seasonYear, roundNumber)} in Java. Every fixture of every season in the world
 * was materialised to select one round's — and two of the four are {@code POST} handlers a manager
 * presses, so the whole table crossed the wire into the JVM on every click.
 *
 * <h2>Why a mock, and not Hibernate statistics</h2>
 *
 * <p><b>Two of the three obvious instruments were measured and did not work here</b>, which is worth
 * recording because both are the ones this repository reached for first on C1 and on the national Elo
 * replay:
 *
 * <ul>
 *   <li><b>Hibernate's entity-load counter reads zero.</b> A {@code @Transactional} test has the rows
 *       in its own persistence context, so a load is elided and the counter never moves. The same trap
 *       the board records twice: a guard comparing two zeroes.</li>
 *   <li><b>A SQL statement count cannot see it.</b> A whole-table load and a narrow lookup each issue
 *       exactly one query, so the number is identical and the test is green against the code it was
 *       written to catch.</li>
 * </ul>
 *
 * <p>So this is a mock on the repository interface, asked the question directly:
 * {@code verify(never()).findAll()}. The constructor is built by hand with mocks, which needs the real
 * parameter order — Lombok orders {@code @RequiredArgsConstructor} parameters by fully-qualified type
 * name rather than by field declaration order, so the order was read off {@code javap} and not off the
 * source.
 *
 * <p>The counterweight is the second test: a narrow query that returns nothing satisfies
 * "never reads the world" perfectly, so there is also a test that the round's fixtures are the ones the
 * handler acts on.
 */
class SimulationControllerFixtureScopeTest extends BaseTest {

    @Autowired private SeasonService seasons;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;

    @Test
    @Transactional
    @DisplayName("simulate-all asks for one round and never reads the whole fixture table")
    void simulateAllAsksForOneRound() {
        MatchFixtureRepository fixtures = Mockito.mock(MatchFixtureRepository.class);
        when(fixtures.findBySeasonYearAndWeekNumberAndDayNumber(any(), any(), any())).thenReturn(List.of());

        SimulationController controller = controllerWith(fixtures);
        controller.simulateCurrentRound(null);

        verify(fixtures, never()).findAll();
        verify(fixtures, atLeastOnce()).findBySeasonYearAndWeekNumberAndDayNumber(any(), any(), any());
    }

    @Test
    @Transactional
    @DisplayName("advance-week asks for one round, and it is the round it then acts on")
    void advanceWeekAsksForOneRound() {
        // The counterweight to the test above. "Never calls findAll" is trivially satisfied by a query
        // that returns nothing at all, which would leave every one of these four endpoints reporting an
        // empty round while passing the guard perfectly.
        //
        // One unplayed fixture in the current round is what makes the handler answer
        // "ROUND_NOT_COMPLETE" and return — with an empty round it proceeds and moves the clock, which
        // is why the fixture is here rather than an empty list. Blocking is also the cheap path: no
        // simulation is run and the clock is not touched, so the measurement costs nothing but the read.
        var clock = seasons.getOrCreateClock();
        Integer season = clock.getCurrentSeason();
        int week = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;
        int day = clock.getCurrentDay() != null ? clock.getCurrentDay() : 1;

        MatchFixtureRepository fixtures = Mockito.mock(MatchFixtureRepository.class);
        when(fixtures.findBySeasonYearAndWeekNumberAndDayNumber(any(), any(), any()))
                .thenReturn(List.of(unplayedFixture(season, week, day)));

        SimulationController controller = controllerWith(fixtures);
        var response = controller.advanceWeek(null);

        assertEquals("blocked", response.getBody().get("status"),
                "the handler should have stopped at the round-not-complete check; if it proceeded it "
                        + "would have moved the clock and this test would be measuring something else");
        assertEquals(1L, response.getBody().get("remainingFixtures"),
                "the handler was handed one unplayed fixture and reported "
                        + response.getBody().get("remainingFixtures")
                        + ". A query scoped too narrowly would report a round nobody has to play.");

        verify(fixtures, never()).findAll();
        verify(fixtures, atLeastOnce()).findBySeasonYearAndWeekNumberAndDayNumber(any(), any(), any());
    }

    /**
     * The ruling: one press plays <b>one matchday</b>, not one round and not a whole week.
     *
     * <p>This is the test that could not have been written before the owner ruled, and it pins the
     * thing that was actually wrong rather than the thing that was merely slow. The four endpoints
     * read the clock's <b>week</b> and used it as a <b>round</b> number, and in a league that plays two
     * rounds a week those are not the same thing:
     *
     * <pre>
     *   game week 1 -> rounds 1, 2      game week 6  -> nothing (mid-season window)
     *   game week 2 -> rounds 3, 4      game week 7  -> rounds 11, 12
     *   game week 3 -> rounds 5, 6      game week 10 -> rounds 17, 18
     * </pre>
     *
     * <p>So in week 3 they fetched round 3 — week 2's football — skipped rounds 5 and 6, and rounds
     * 13 to 18 were never reached at all because the clock stops at week 12. Measured on the seeded
     * Serbian world: week 3 returned round 3 where the week really holds 5 and 6, and week 6 returned a
     * fixture on a day deliberately left empty for the national-team pause.
     *
     * <p><b>Day 3 and day 7 are league, day 1 is international and day 5 is cup</b>, so (season, week,
     * day) is the axis that says which football is due — and it is the axis the existing
     * {@code ix_match_fixture_season_week_day} index already serves, which is why no index on
     * {@code round_number} was wanted.
     */
    @Test
    @Transactional
    @DisplayName("the endpoints ask for a matchday by week AND day, never by round")
    void theEndpointsAskForAMatchdayNotARound() {
        MatchFixtureRepository fixtures = Mockito.mock(MatchFixtureRepository.class);
        when(fixtures.findBySeasonYearAndWeekNumberAndDayNumber(any(), any(), any())).thenReturn(List.of());

        controllerWith(fixtures).simulateCurrentRound(null);

        var asked = mockingDetails(fixtures).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("findBySeasonYearAndWeekNumberAndDayNumber"))
                .toList();
        assertTrue(!asked.isEmpty(),
                "the controller asked for fixtures without naming a day. One press plays one matchday, and a "
                        + "week holds two of them.");
        verify(fixtures, never()).findAll();

        // The day it asked for has to be the day the clock is on, not the day it defaults to.
        var clock = seasons.getOrCreateClock();
        int expectedDay = clock.getCurrentDay() != null ? clock.getCurrentDay() : GameDay.FIRST;
        int askedDay = (Integer) asked.get(0).getArgument(2);
        assertEquals(expectedDay, askedDay,
                "the controller asked for day " + askedDay + " and the clock is on day " + expectedDay
                        + ". Asking for the wrong day is the same defect as asking by round: it plays the "
                        + "wrong football.");
    }

    // --- wiring ---

    /**
     * The real {@link SeasonService} — the handler asks it for the clock — with a mocked fixture
     * repository and mocks for everything else it does not reach on these two paths.
     */
    private SimulationController controllerWith(MatchFixtureRepository fixtures) {
        return new SimulationController(
                Mockito.mock(org.example.commonmanager.repository.UserRepository.class),
                Mockito.mock(TeamRepository.class),
                fixtures,
                Mockito.mock(CurrentRoundSimulationStateService.class),
                Mockito.mock(GameClockService.class),
                Mockito.mock(CompetitionRepository.class),
                seasons,
                Mockito.mock(TrainingProgressionService.class),
                Mockito.mock(AsyncSimulationRunner.class),
                Mockito.mock(SimMatchService.class),
                Mockito.mock(ClubRatingService.class),
                // The exhibition endpoint (P2-8). Mocked because this class is about fixture scope.
                Mockito.mock(org.example.footballmanager.newLogic.service.ExhibitionMatchService.class));
    }

    // --- fixture ---

    /**
     * A fixture sitting on a named game week and day.
     *
     * <p>Week and day are separate arguments on purpose. The controller asks for
     * {@code (season, week, day)} and nothing else, so a fixture built with the week in its round
     * column — which is what this helper used to do — is invisible to it. That is the defect the test
     * exists to catch, and a fixture that could not be seen would have made it vacuous.
     */
    private MatchFixture unplayedFixture(Integer season, int week, int day) {
        Competition competition = new Competition();
        competition.setName("ZZ Fixture Scope " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition = competitions.save(competition);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(aTeam("ZZ Home " + UUID.randomUUID()));
        fixture.setAwayTeam(aTeam("ZZ Away " + UUID.randomUUID()));
        fixture.setSeasonYear(season);
        fixture.setRoundNumber(1);
        fixture.setWeekNumber(week);
        fixture.setDayNumber(day);
        fixture.setPlayed(false);
        return fixture;
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}
