package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.LeagueTableReconciliationService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The league table is rebuilt from the matches, not added to.
 *
 * <p><b>This exists because the repair pass had never been run.</b> It is scheduled for the small hours
 * of days 4 and 8 and had no test, so "the nightly job fixes the table" was a claim in a comment. The
 * whole argument for the class is that a rebuild is idempotent and converges from any starting state, and
 * those are exactly the two properties a one-shot happy-path test cannot check — it would pass against an
 * implementation that only ever adds.
 *
 * <p>So every test here <b>corrupts the table first</b> and then demands the rebuild produce the right
 * answer, and the idempotency test demands a <b>second</b> run correct nothing.
 */
class LeagueTableReconciliationServiceTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private LeagueTableReconciliationService reconcile;
    @Autowired private CompetitionRepository competitions;
    @Autowired private SeasonCompetitionRepository seasonCompetitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private MatchRepository matches;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    private Competition league;
    private SeasonCompetition seasonCompetition;
    private Team home;
    private Team away;

    @BeforeEach
    @Transactional
    void aLeagueWithTwoClubs() {
        Country country = new Country();
        country.setName("ZZ Table " + System.nanoTime());
        country.setIsoCode("TT");
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        league = new Competition();
        league.setName("ZZ Table " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(1);
        league.setCountry(country);
        league = competitions.save(league);

        seasonCompetition = new SeasonCompetition();
        seasonCompetition.setCompetition(league);
        seasonCompetition.setSeasonYear(SEASON);
        seasonCompetition = seasonCompetitions.save(seasonCompetition);

        home = aTeam(country, "Home");
        away = aTeam(country, "Away");
        entries.save(entryFor(home));
        entries.save(entryFor(away));
    }

    @Test
    @Transactional
    @DisplayName("a wrong table is rebuilt from the matches")
    void aCorruptedTableIsRebuilt() {
        // The table claims a 3-0 home win and a 4-0 away win. The matches say otherwise.
        corrupt(home, 9, 3, 0, 0, 3, 0);
        corrupt(away, 12, 4, 0, 0, 4, 0);
        played(home, away, 2, 1);   // home wins
        played(home, away, 1, 1);   // drawn
        played(away, home, 2, 0);   // away wins

        Result result = reconcile.reconcile(league, SEASON);

        assertEquals(3, result.matchesRead(), "the rebuild did not read the three matches");
        assertEquals(2, result.entriesCorrected(), "the rebuild did not correct both clubs");

        // home 2-1 (win), home 1-1 (draw), away 2-0 (loss). So each club has one of each.
        CompetitionEntry homeRow = rowFor(home);
        assertEquals(4, homeRow.getPoints(), "one win and one draw is four points");
        assertEquals(1, homeRow.getWins());
        assertEquals(1, homeRow.getDraws());
        assertEquals(1, homeRow.getLosses());
        assertEquals(3, homeRow.getGoalsScored(), "2 + 1 + 0");
        assertEquals(4, homeRow.getGoalsConceded(), "1 + 1 + 2");

        CompetitionEntry awayRow = rowFor(away);
        assertEquals(4, awayRow.getPoints(), "one win and one draw is four points");
        assertEquals(1, awayRow.getWins());
        assertEquals(1, awayRow.getDraws());
        assertEquals(1, awayRow.getLosses());
        assertEquals(4, awayRow.getGoalsScored(), "1 + 1 + 2");
        assertEquals(3, awayRow.getGoalsConceded(), "2 + 1 + 0");
    }

    @Test
    @Transactional
    @DisplayName("rebuilding twice changes nothing the second time")
    void rebuildingIsIdempotent() {
        played(home, away, 2, 1);
        // played(a, b, x, y) means a is the home side and scored x. So this is away 3-0, not home.
        played(away, home, 3, 0);

        Result first = reconcile.reconcile(league, SEASON);
        Result second = reconcile.reconcile(league, SEASON);

        assertEquals(2, first.entriesCorrected(), "the first rebuild should have had work to do");
        assertEquals(0, second.entriesCorrected(),
                "a second rebuild corrected something, which means it is adding rather than rebuilding. "
                        + "This is the property the whole class exists for.");
        // home 2-1 then away 3-0: one win and one loss each, three points.
        assertEquals(3, rowFor(home).getPoints());
        assertEquals(3, rowFor(away).getPoints());
    }

    @Test
    @Transactional
    @DisplayName("a club with no matches is zeroed rather than left stale")
    void anUnplayedClubIsZeroedNotLeftAlone() {
        played(home, away, 1, 0);
        corrupt(away, 5, 1, 1, 0, 7, 3);

        reconcile.reconcile(league, SEASON);

        CompetitionEntry awayRow = rowFor(away);
        assertEquals(0, awayRow.getPoints(), "a club that never played still shows points");
        assertEquals(0, awayRow.getWins());
        assertEquals(0, awayRow.getGoalsScored());
    }

    @Test
    @Transactional
    @DisplayName("a league with no season is left alone rather than throwing")
    void aLeagueWithNoSeasonIsHarmless() {
        Competition orphan = new Competition();
        orphan.setName("ZZ Orphan " + System.nanoTime());
        orphan.setType(CompetitionType.LEAGUE);
        orphan.setScope(CompetitionScope.NATIONAL);
        orphan.setTeamType(CompetitionTeamType.CLUB);
        orphan.setTier(9);
        orphan.setCountry(league.getCountry());
        orphan = competitions.save(orphan);

        Result result = reconcile.reconcile(orphan, SEASON);

        assertEquals(0, result.tablesRebuilt(), "a league with no season rebuilt something");
        assertEquals(0, result.entriesCorrected());
    }

    private void played(Team homeTeam, Team awayTeam, int homeGoals, int awayGoals) {
        Match match = new Match();
        match.setCompetition(league);
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setPlayed(true);
        match.setFinished(true);
        match.setSeasonYear(SEASON);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        matches.save(match);
    }

    private CompetitionEntry entryFor(Team team) {
        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(seasonCompetition);
        entry.setTeam(team);
        entry.setPoints(0);
        entry.setWins(0);
        entry.setDraws(0);
        entry.setLosses(0);
        entry.setGoalsScored(0);
        entry.setGoalsConceded(0);
        return entry;
    }

    /** Write a table that the matches cannot possibly justify. */
    private void corrupt(Team team, int points, int wins, int draws, int losses, int scored, int conceded) {
        CompetitionEntry entry = rowFor(team);
        entry.setPoints(points);
        entry.setWins(wins);
        entry.setDraws(draws);
        entry.setLosses(losses);
        entry.setGoalsScored(scored);
        entry.setGoalsConceded(conceded);
        entries.save(entry);
    }

    private CompetitionEntry rowFor(Team team) {
        return entries.findBySeasonCompetition(seasonCompetition).stream()
                .filter(e -> e.getTeam().getId().equals(team.getId()))
                .findFirst()
                .orElseThrow();
    }

    private Team aTeam(Country country, String label) {
        Team team = new Team();
        team.setName("ZZ Table " + label + " " + System.nanoTime());
        team.setCountry(country);
        return teams.save(team);
    }
}