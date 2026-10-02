package org.example.footballmanager.newLogic.jobs;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.jobs.impl.MatchdayJob;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A matchday asks the database for its fixtures once.
 *
 * <p>{@code findUnplayedOnDay} was called <b>inside</b> the flatMap over target competitions, so it ran once
 * per competition and every call returned the identical rows — the competition filter was applied in Java,
 * after the query. With sixteen CUP competitions that is sixteen identical full-table scans of the fixture
 * table for one matchday.
 *
 * <p>The count is asserted directly rather than inferred, because a performance claim measured by eye is not a
 * measurement. The mock is what makes it a measurement: it counts calls, so a regression is a number rather
 * than a slow page.
 */
class MatchdayJobQueryCountTest extends BaseTest {

    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    /**
     * Sixteen cup competitions, one fixture query.
     *
     * <p>Counted through a Mockito mock of the repository interface rather than a spy of the injected bean:
     * Spring Data hands back a JDK proxy and Mockito cannot wrap one. This is the second time this session
     * that has been the answer — it is the note to reach for.
     *
     * <p>The mock is given a fixed answer, so nothing depends on the database here at all. That is deliberate:
     * the question is how many times the code asks, not what it gets back.
     */
    @Test
    @DisplayName("sixteen cup competitions still cost one fixture query")
    void sixteenCompetitionsCostOneQuery() {
        // Sixteen CUP competitions, with no database involved - CompetitionRepository is also mocked and
        // returns sixteen targets, which is the shape the audit says made this sixteen queries.
        CompetitionRepository mockedCompetitions = Mockito.mock(CompetitionRepository.class);
        List<Competition> sixteenCups = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Competition cup = Mockito.mock(Competition.class);
            Mockito.when(cup.getId()).thenReturn(1000L + i);
            Mockito.when(cup.getType()).thenReturn(CompetitionType.CUP);
            sixteenCups.add(cup);
        }
        Mockito.when(mockedCompetitions.findAll()).thenReturn(sixteenCups);

        MatchFixtureRepository mockedFixtures = Mockito.mock(MatchFixtureRepository.class);
        Mockito.when(mockedFixtures.findUnplayedOnDay(any(), any(), anyInt())).thenReturn(List.of());

        MatchdayJob job = new MatchdayJob("matchday-cup", CompetitionType.CUP, 5, 18, 40,
                mockedCompetitions, mockedFixtures,
                Mockito.mock(org.example.footballmanager.newLogic.service.AsyncSimulationRunner.class));

        job.run(new JobContext(1, 1, 5, 18));

        verify(mockedFixtures, times(1)).findUnplayedOnDay(any(), any(), anyInt());
    }

    /**
     * The count is only worth asserting if the answer is still right. Sixteen competitions with one fixture
     * must still play that one fixture and none of the others'.
     */
    @Test
    @Transactional
    @DisplayName("only the target competition's fixtures are played")
    void onlyTargetCompetitionsArePlayed() {
        Country country = new Country();
        country.setName("Query cost scoped " + UUID.randomUUID());
        country.setIsoCode("S" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition target = cup(country, "Target cup");
        Competition other = cup(country, "Other cup");

        aFixture(target, country, 1, 5);
        aFixture(other, country, 1, 5);

        var runner = Mockito.mock(org.example.footballmanager.newLogic.service.AsyncSimulationRunner.class);
        MatchdayJob job = new MatchdayJob("matchday-cup", CompetitionType.CUP, 5, 18, 40,
                competitions, fixtures, runner);

        job.run(new JobContext(1, 1, 5, 18));

        var worked = Mockito.mockingDetails(runner).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("simulateInBackground"))
                .toList();
        assertEquals(1, worked.size(),
                "expected exactly one fixture to be played - the target cup's - but " + worked.size()
                        + " were started, which means the competition filter is not narrowing anything");
    }

    private Competition cup(Country country, String name) {
        Competition cup = new Competition();
        cup.setName(name + " " + UUID.randomUUID());
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setTier(1);
        cup.setCountry(country);
        return competitions.save(cup);
    }

    private MatchFixture aFixture(Competition cup, Country country, int week, int day) {
        Team home = new Team();
        home.setName("Query cost home " + UUID.randomUUID());
        home.setCountry(country);
        home = teams.save(home);
        Team away = new Team();
        away.setName("Query cost away " + UUID.randomUUID());
        away.setCountry(country);
        away = teams.save(away);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(cup);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setWeekNumber(week);
        fixture.setDayNumber(day);
        fixture.setSeasonYear(1);
        fixture.setRoundNumber(1);
        fixture.setPlayed(false);
        fixture.setMatchDate(LocalDateTime.of(2026, 2, 5, 18, 0));
        return fixtures.save(fixture);
    }
}