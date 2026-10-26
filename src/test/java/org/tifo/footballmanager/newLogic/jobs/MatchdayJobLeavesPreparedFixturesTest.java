package org.tifo.footballmanager.newLogic.jobs;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.UniqueIsoCodes;
import org.tifo.footballmanager.newLogic.jobs.impl.MatchdayJob;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionScope;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.service.AsyncSimulationRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fixture the matchday has already computed is <b>not</b> played again at kickoff.
 *
 * <p><b>The defect this exists for.</b> The owner's rule is a pair of jobs: one computes the whole
 * matchday an hour before kickoff and stores the match on the fixture as {@code preparedMatchId}, the
 * other releases it at kickoff. A prepared fixture is, by design, still {@code played = false} — the
 * result must not be readable before the match. The older play jobs asked only "is it played?", got
 * "no", and simulated the tie a second time. Two matches for one fixture, the second written over the
 * first, and the score the manager watched was not the score the engine had planned.
 *
 * <p>The prepare/publish pair owns a matchday now. A play job is the fallback for whatever the pair
 * could not do, and a prepared fixture is not that.
 *
 * <p>This is a behavioural test rather than a source check: the job is driven with a mocked
 * {@link AsyncSimulationRunner}, so what is asserted is the list of fixtures it tried to play.
 */
class MatchdayJobLeavesPreparedFixturesTest extends BaseTest {

    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("a prepared fixture is left for the publish job, and only the unprepared one is played")
    void aPreparedFixtureIsLeftForThePublishJob() {
        Country country = new Country();
        country.setName("Prepared skip " + UUID.randomUUID());
        country.setIsoCode(UniqueIsoCodes.next());
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition cup = cup(country);

        MatchFixture drafted = aFixture(cup, country, 1, 5);
        MatchFixture prepared = aFixture(cup, country, 1, 5);
        prepared.setPreparedMatchId(999L);
        prepared.setPreparedAt(LocalDateTime.of(2026, 2, 5, 17, 0));
        prepared = fixtures.save(prepared);

        assertTrue(prepared.isPrepared(),
                "precondition: the fixture is computed but not published - the state the whole hour "
                        + "before kickoff exists for");

        AsyncSimulationRunner runner = Mockito.mock(AsyncSimulationRunner.class);
        MatchdayJob job = new MatchdayJob("matchday-cup", CompetitionType.CUP, 5, 18, 40,
                competitions, fixtures, runner);

        job.run(new JobContext(1, 1, 5, 18));

        List<?> played = Mockito.mockingDetails(runner).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("simulateInBackground"))
                .map(i -> i.getArgument(0))
                .toList();

        assertEquals(1, played.size(),
                "the matchday started " + played.size() + " simulation(s) for a day with one prepared "
                        + "fixture and one that is not prepared. The prepared one belongs to the publish "
                        + "job: playing it here is a second match for the same tie.");

        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) played.get(0);
        assertEquals(List.of(drafted.getId()), ids,
                "the prepared fixture " + prepared.getId() + " was handed to the simulation runner "
                        + "again. A fixture with a prepared match id is already computed; the publish job "
                        + "releases it at kickoff and nothing may overwrite it.");
    }

    private Competition cup(Country country) {
        Competition cup = new Competition();
        cup.setName("Prepared skip cup " + UUID.randomUUID());
        cup.setType(CompetitionType.CUP);
        cup.setScope(CompetitionScope.NATIONAL);
        cup.setTeamType(CompetitionTeamType.CLUB);
        cup.setTier(1);
        cup.setCountry(country);
        return competitions.save(cup);
    }

    private MatchFixture aFixture(Competition cup, Country country, int week, int day) {
        Team home = new Team();
        home.setName("Prepared skip home " + UUID.randomUUID());
        home.setCountry(country);
        home = teams.save(home);
        Team away = new Team();
        away.setName("Prepared skip away " + UUID.randomUUID());
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
