package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.GamePosition;
import org.tifo.footballmanager.newLogic.model.MatchFixture;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sync has to be able to say that a match was left behind (owner, 2026-10-19).
 *
 * <p><b>The owner's ruling, verbatim:</b> <em>"every match whose start is in the past must be played —
 * the game looks only at its own season/week/day/hour against the current s/w/d/h — <b>and the sync has
 * to check it</b>."</em>
 *
 * <p>The first half was already true — the clock's walk offers every game hour it crosses to the job
 * runner, so a matchday missed while the app was down is replayed. **The check was missing**, and
 * without it a matchday whose publish job failed left a fixture in the past that no job would ever
 * play, and the world looked perfectly healthy.
 *
 * <p><b>Why the test is written in slots and not in dates.</b> The game has no calendar year: a
 * fixture lives in a <b>(season, week, day)</b> slot. And the fixture's own {@code matchDate} cannot
 * be used to order the slots — the league stamps every fixture of a week with that week's date and
 * changes only the hour, and the cup takes a date six days later, so a week-1 day-7 fixture carries
 * an <em>earlier</em> timestamp than a week-1 day-3 one. The test therefore puts a deliberately
 * misleading date on every fixture: if the implementation reached for the date, it would answer
 * differently, and the count would not be the one asserted.
 */
@SpringBootTest
@Transactional
class StrandedFixtureServiceTest {

    @Autowired StrandedFixtureService stranded;
    @Autowired MatchFixtureRepository fixtures;
    @Autowired CompetitionRepository competitions;
    @Autowired TeamRepository teams;
    @Autowired CountryRepository countries;

    @Test
    @DisplayName("a fixture in a slot that has passed and is unplayed is left behind")
    void whatHasPassedAndIsUnplayedIsStranded() {
        Competition league = myLeague();
        Team home = myTeam(league, "Home");
        Team away = myTeam(league, "Away");

        GamePosition clock = new GamePosition(1, 2, 4, 12);

        // **The count is measured as a difference.** The check is a question about the whole world, and
        // the world this test runs in is not empty — it is the owner's own, with a season of fixtures in
        // it. So the number before is taken, the fixtures are added, and the difference is the claim.
        long before = stranded.count(clock);

        // Season 1, week 1, day 1 - before the clock's week.
        MatchFixture first = fixture(league, home, away, 1, 1, 1, false);
        // Season 1, week 1, day 3 - the same week, an earlier day.
        MatchFixture second = fixture(league, home, away, 1, 1, 3, false);
        // Season 1, week 2, day 3 - an earlier week.
        MatchFixture third = fixture(league, home, away, 1, 2, 3, false);
        // Season 1, week 3, day 1 - a later week. Not behind.
        MatchFixture later = fixture(league, home, away, 1, 3, 1, false);
        // Season 1, week 2, day 4 - the day the clock is standing in. Not behind yet.
        MatchFixture today = fixture(league, home, away, 1, 2, 4, false);
        // Season 2, week 1, day 1 - a later season. Not behind.
        MatchFixture nextSeason = fixture(league, home, away, 2, 1, 1, false);
        // Season 1, week 1, day 1 - behind, but played. Not stranded.
        MatchFixture played = fixture(league, home, away, 1, 1, 1, true);

        assertEquals(before + 3, stranded.count(clock),
                "three fixtures are in a slot that has passed and were never played: week 1 day 1, "
                        + "week 1 day 3 and week 2 day 3. The week-2 day-4 fixture is the day the "
                        + "clock is standing in — its publish job may not have fired yet — the "
                        + "week-3 one is in the future, the season-2 one is in the future, and the "
                        + "played one is played.");

        List<StrandedFixtureService.Stranded> named = stranded.stranded(clock, 10_000);
        List<Long> mine = named.stream().map(StrandedFixtureService.Stranded::fixtureId).toList();

        assertTrue(mine.contains(first.getId()), "the week-1 day-1 fixture is named");
        assertTrue(mine.contains(second.getId()), "the week-1 day-3 fixture is named");
        assertTrue(mine.contains(third.getId()), "the week-2 day-3 fixture is named");
        assertTrue(!mine.contains(later.getId()), "the week-3 fixture is not — it is in the future");
        assertTrue(!mine.contains(today.getId()),
                "the fixture in the day the clock is standing in is not — its publish job may not have "
                        + "fired yet, and a day is only counted once it is over");
        assertTrue(!mine.contains(nextSeason.getId()), "nor the next season's");
        assertTrue(!mine.contains(played.getId()), "nor the one that was played");

        List<StrandedFixtureService.Stranded> ordered = named.stream()
                .filter(row -> row.competition() != null && row.competition().equals(league.getName()))
                .toList();
        assertEquals(3, ordered.size(), "and this test's own three are in the list");
        assertTrue(ordered.get(0).week() < ordered.get(1).week()
                        || (ordered.get(0).week() == ordered.get(1).week()
                            && ordered.get(0).day() <= ordered.get(1).day()),
                "oldest slot first: week 1 comes before week 2");
        assertTrue(ordered.get(0).day() == 1, "and day 1 before day 3");
    }

    @Test
    @DisplayName("a world with nothing left behind says so")
    void aWholeWorldSaysNothingIsStranded() {
        // The count is the world's, and the world this runs in is not empty. What is asserted here is
        // that the number never goes down and that every row the service names is genuinely behind the
        // clock - the property that makes the count meaningful.
        GamePosition clock = new GamePosition(1, 2, 4, 12);
        int count = stranded.count(clock);
        assertTrue(count >= 0, "the count is a count");

        for (StrandedFixtureService.Stranded row : stranded.stranded(clock, 200)) {
            boolean behind = row.season() < clock.season()
                    || (row.season() == clock.season() && row.week() < clock.week())
                    || (row.season() == clock.season() && row.week() == clock.week()
                        && row.day() < clock.day());
            assertTrue(behind,
                    "every named fixture is in a slot that has passed: " + row.competition() + " "
                            + row.home() + " - " + row.away() + " is season " + row.season() + ", week "
                            + row.week() + ", day " + row.day() + ", and the clock is at week "
                            + clock.week() + ", day " + clock.day() + ".");
        }
    }

    // ── a world of this test's own ────────────────────────────────────────────────────────────────

    private Competition myLeague() {
        long stamp = System.nanoTime();
        Country country = new Country();
        country.setIsoCode("S" + (char) ('A' + (int) (stamp % 26)) + "T");
        country.setName("Stranded Probe " + stamp);
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition league = new Competition();
        league.setName("Stranded Probe League " + stamp);
        league.setType(CompetitionType.LEAGUE);
        league.setTier(1);
        league.setCountry(country);
        return competitions.save(league);
    }

    private Team myTeam(Competition league, String name) {
        Team team = new Team();
        team.setName(name + " " + System.nanoTime());
        team.setCompetition(league);
        return teams.save(team);
    }

    /**
     * A fixture in a slot.
     *
     * <p><b>The date is deliberately wrong.</b> It is a moment in the future for every fixture, and the
     * ones in later slots carry the <em>earlier</em> dates — so an implementation that compared
     * timestamps instead of slots would answer differently, and the count asserted above would not be
     * the one it produced.
     */
    private MatchFixture fixture(Competition league, Team home, Team away, int season, int week, int day,
                                 boolean played) {
        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(league);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setSeasonYear(season);
        fixture.setWeekNumber(week);
        fixture.setDayNumber(day);
        fixture.setPlayed(played);
        // Later slots get earlier dates, on purpose.
        fixture.setMatchDate(LocalDateTime.of(2026, 1, 1 + Math.max(0, 4 - week), 12, 0));
        return fixtures.save(fixture);
    }
}
