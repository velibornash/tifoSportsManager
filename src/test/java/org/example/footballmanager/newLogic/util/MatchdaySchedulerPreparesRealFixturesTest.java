package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.MatchdayScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A matchday prepares and publishes through the real scheduler, against a real database.
 *
 * <p><b>Written because three unit suites were green while every fixture failed.</b> The lazy-loading
 * fault — *"could not initialize proxy Team#406 - no Session"* — happened three times on the live app
 * and no unit test could see it, because a mocked {@code SimMatchService} never touches a proxy and a
 * mocked repository hands back a fixture nobody else will ever load. 775 fixtures, then 155, then 155
 * again, and each run was recorded as a matchday that had happened.
 *
 * <p>So this one runs the scheduler as the job runs it: fixtures fetched, ids taken, each fixture
 * re-read inside its own transaction, and the transaction manager the container actually supplies.
 *
 * <p>The assertion is deliberately about <b>failures</b> rather than about a score. A matchday where
 * the simulation legitimately declines to produce a result is still a healthy matchday; a matchday that
 * threw on every fixture is not, and that is the distinction the green suites were missing.
 */
class MatchdaySchedulerPreparesRealFixturesTest extends BaseTest {

    @Autowired private MatchdayScheduler scheduler;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;

    @Test
    @DisplayName("preparing a real matchday does not fail on lazy loading")
    // Deliberately NOT @Transactional. The first version was, and it passed - because the test's own
    // transaction held a session open, which is precisely what the job does not have. A test that
    // reproduces the world must also reproduce its absence of an ambient transaction.
    void preparingDoesNotFailOnLazyLoading() {
        Competition competition = new Competition();
        competition.setName("ZZ Scheduler " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(aTeam("ZZ Scheduler Home " + UUID.randomUUID()));
        fixture.setAwayTeam(aTeam("ZZ Scheduler Away " + UUID.randomUUID()));
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        Long fixtureId = fixtures.save(fixture).getId();

        // Exactly what the job hands over: entities out of a query, then ids.
        List<Long> ids = fixtures.findUnplayedOnDay(1, 1, 3).stream()
                .map(MatchFixture::getId)
                .filter(id -> id != null && id.equals(fixtureId))
                .toList();

        MatchdayScheduler.MatchdayOutcome outcome = scheduler.prepareUnplayedOn(1, 1, 3, ids);

        assertThat(outcome.failed())
                .as("every fixture failed with 'could not initialize proxy Team - no Session' while the "
                        + "unit suites stayed green: %s", outcome)
                .isZero();
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}