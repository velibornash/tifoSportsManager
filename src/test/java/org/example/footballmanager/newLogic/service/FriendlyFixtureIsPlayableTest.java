package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.jobs.impl.FriendlyMatchdayJob;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.MatchType;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.FriendlyRequestRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.AsyncSimulationRunner;
import org.example.footballmanager.newLogic.jobs.JobContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * P2-10 — a friendly could be agreed, written, shown, and never played.
 *
 * <p><b>This is the defect, and it is older than the button that exposed it.</b> Every matchday in the
 * framework selects its fixtures by <b>competition type</b>. A friendly belongs to no competition —
 * correctly, since it decides nothing — so no matchday could find one. And the fixture the club service
 * wrote carried no {@code matchType} and no {@code dayNumber} either, so there was nothing to select on
 * even if a job had looked.
 *
 * <p>Two halves, and the second is the one that would have been missed: writing the fields is not enough.
 * Something has to ask for the fixtures by them.
 */
class FriendlyFixtureIsPlayableTest extends BaseTest {

    private static final int SLOT = 2;

    @Autowired private org.example.footballmanager.newLogic.repository.GameClockRepository clocks;

    /**
     * The week this test arranges its friendly in.
     *
     * <p><b>Derived from the clock, not assumed.</b> {@code requestFriendly} refuses a week that has
     * already been played, and only weeks 6, 11 and 12 have a friendly-capable slot at all — weeks 1-5
     * and 7-10 are league weeks. So a hardcoded 11 passes or fails depending on where the shared test
     * database's clock happens to be, which is a test about the clock wearing a test about friendlies.
     */
    private int selectedWeek() {
        Integer current = clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentWeek)
                .orElse(1);
        return current != null && current <= SeasonCalendar.PLAYOFF_WEEK
                ? SeasonCalendar.PLAYOFF_WEEK
                : SeasonCalendar.BREAK_WEEK;
    }

    /** The season the request will be written into, which is the clock's. */
    private int season() {
        return clocks.findById(1L)
                .map(org.example.footballmanager.newLogic.model.GameClock::getCurrentSeason)
                .orElse(1);
    }

    @Autowired private SeasonService seasons;
    @Autowired private FriendlyRequestService service;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private FriendlyRequestRepository requests;

    @MockBean private AsyncSimulationRunner runner;

    private Team home;
    private Team away;

    @BeforeEach
    void setUp() {
        Country country = new Country();
        country.setName("Friendly Play Republic " + System.nanoTime());
        String tail = Long.toString(Math.abs(System.nanoTime()) % 1296, 36);
        while (tail.length() < 2) {
            tail = "0" + tail;
        }
        country.setIsoCode("P" + tail);
        country.setReputation(1500);
        country = countries.save(country);
        home = club(country, "Home");
        away = club(country, "Away");
        // **The clock has to exist before anything is asked for.** requestFriendly reads it and returns
        // empty when it is missing, so on an in-memory test database with no `game_clock` row - which is
        // what `@ActiveProfiles("test")` gives - every request is refused. That is the service behaving
        // correctly: it will not invent a season for a request.
        //
        // Found the hard way: the failure was `Optional.orElseThrow` with no message, and the obvious
        // suspects were all the wrong ones.
        seasons.getOrCreateClock();
    }

    private Team club(Country country, String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.CLUB);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(false);
        Team saved = teams.save(t);
        for (int i = 0; i < 11; i++) {
            players.save(player(name + " P" + i, saved));
        }
        return saved;
    }

    private static Player player(String name, Team team) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 12);
        skills.setSkill(SkillName.STAMINA, 12);
        skills.setSkill(SkillName.GOALKEEPER, 12);
        skills.setSkill(SkillName.DEFENDER, 12);
        skills.setSkill(SkillName.TECHNIQUE, 12);
        skills.setSkill(SkillName.PLAYMAKER, 12);
        skills.setSkill(SkillName.PASSING, 12);
        skills.setSkill(SkillName.STRIKER, 12);
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName(name);
        p.setPosition(Position.MID);
        p.setSkills(skills);
        p.setAge(22);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return p;
    }

    @Test
    @Transactional
    @DisplayName("an agreed friendly carries the type and the day a matchday can find it by")
    void anAgreedFriendlyCarriesATypeAndADay() {
        FriendlyRequest created = service.requestFriendly(home.getId(), away.getId(), selectedWeek(), SLOT).orElseThrow();
        service.respond(created.getId(), away.getId(), true, null);

        MatchFixture fixture = fixtures.findById(created.getAcceptedFixtureId()).orElseThrow();
        assertEquals(MatchType.FRIENDLY, fixture.getMatchType(),
                "without a type there is nothing for the friendly matchday to select on");
        assertEquals(SeasonCalendar.SLOT_TWO_DAY, fixture.getDayNumber(),
                "without a day there is no date to select on either");
        assertEquals(selectedWeek(), fixture.getWeekNumber());
    }

    @Test
    @Transactional
    @DisplayName("the friendly matchday finds it and hands it to the engine")
    void theFriendlyMatchdayFindsItAndPlaysIt() {
        FriendlyRequest created = service.requestFriendly(home.getId(), away.getId(), selectedWeek(), SLOT).orElseThrow();
        service.respond(created.getId(), away.getId(), true, null);
        Long fixtureId = created.getAcceptedFixtureId();

        // The day-7 job, at the hour the template gives day 7.
        FriendlyMatchdayJob job = new FriendlyMatchdayJob("matchday-friendly-7",
                SeasonCalendar.SLOT_TWO_DAY, fixtures, runner);
        job.run(new JobContext(season(), selectedWeek(), SeasonCalendar.SLOT_TWO_DAY, 16));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(runner, atLeastOnce()).simulateInBackground(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(ids -> ids.contains(fixtureId)),
                "the friendly matchday did not hand the agreed friendly to the engine; it saw "
                        + captor.getAllValues());
    }

    @Test
    @Transactional
    @DisplayName("it has no competition, which is exactly why the competition-scoped matchdays drop it")
    void itHasNoCompetitionAndThatIsWhyItWasInvisible() {
        FriendlyRequest created = service.requestFriendly(home.getId(), away.getId(), selectedWeek(), SLOT).orElseThrow();
        service.respond(created.getId(), away.getId(), true, null);
        Long fixtureId = created.getAcceptedFixtureId();

        MatchFixture fixture = fixtures.findById(fixtureId).orElseThrow();
        // The day-scoped query returns it; MatchdayJob then keeps only fixtures whose competition id is
        // in its target set, and this one has no competition, so it is filtered out there. The premise
        // is the *filter*, not the query - an earlier version of this test claimed the query was
        // competition-scoped and it is not.
        assertNull(fixture.getCompetition(),
                "a friendly belongs to no competition; giving it one would put it in a ranking");
        assertTrue(fixtures.findBySeasonYearAndWeekNumberAndDayNumberAndPlayedFalse(
                        season(), selectedWeek(), SeasonCalendar.SLOT_TWO_DAY).stream()
                        .anyMatch(f -> f.getId().equals(fixtureId)),
                "the day query returns it, so it is the job's competition filter that drops it");

        // And the type-scoped query, which is what the friendly matchday uses, does find it.
        assertTrue(fixtures.findUnplayedFriendliesOnDay(season(), selectedWeek(), SeasonCalendar.SLOT_TWO_DAY)
                        .stream().anyMatch(f -> f.getId().equals(fixtureId)),
                "the type-scoped query must find it, or nothing does");
    }

    @Test
    @Transactional
    @DisplayName("a league fixture on the same day is not dragged along with the friendly")
    void onlyFriendliesAreSelected() {
        FriendlyRequest created = service.requestFriendly(home.getId(), away.getId(), selectedWeek(), SLOT).orElseThrow();
        service.respond(created.getId(), away.getId(), true, null);

        // Written after the request: a fixture in that week would make both sides busy and the request
        // would correctly be refused, which is a different test.
        MatchFixture leagueFixture = new MatchFixture();
        leagueFixture.setHomeTeam(home);
        leagueFixture.setAwayTeam(away);
        leagueFixture.setSeasonYear(season());
        leagueFixture.setWeekNumber(selectedWeek());
        leagueFixture.setDayNumber(SeasonCalendar.SLOT_TWO_DAY);
        leagueFixture.setRoundNumber(9);
        leagueFixture.setMatchType(MatchType.LEAGUE);
        leagueFixture.setPlayed(false);
        Long leagueId = leagueFixture.getId();

        List<MatchFixture> selected = fixtures.findUnplayedFriendliesOnDay(
                season(), selectedWeek(), SeasonCalendar.SLOT_TWO_DAY);
        assertNotNull(selected);
        assertTrue(selected.stream().noneMatch(f -> f.getId().equals(leagueId)),
                "a league fixture must not be played by the friendly matchday");
    }
}