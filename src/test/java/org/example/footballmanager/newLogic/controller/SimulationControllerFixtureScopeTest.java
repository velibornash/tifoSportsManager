package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
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
        when(fixtures.findBySeasonYearAndRoundNumber(any(), any())).thenReturn(List.of());

        SimulationController controller = controllerWith(fixtures);
        controller.simulateCurrentRound(null);

        verify(fixtures, never()).findAll();
        verify(fixtures, atLeastOnce()).findBySeasonYearAndRoundNumber(any(), any());
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
        int round = clock.getCurrentWeek() != null ? clock.getCurrentWeek() : 1;

        MatchFixtureRepository fixtures = Mockito.mock(MatchFixtureRepository.class);
        when(fixtures.findBySeasonYearAndRoundNumber(any(), any()))
                .thenReturn(List.of(unplayedFixture(season, round)));

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
        verify(fixtures, atLeastOnce()).findBySeasonYearAndRoundNumber(any(), any());
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
                Mockito.mock(ClubRatingService.class));
    }

    // --- fixture ---

    private MatchFixture unplayedFixture(Integer season, int round) {
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
        fixture.setRoundNumber(round);
        fixture.setWeekNumber(round);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        return fixture;
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}
