package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goals are read out of the match's event log.
 *
 * <p><b>This exists because the repository used to return an empty list from every method</b>, and four
 * things the owner looks at every week were blank because of it: the league top scorers, the league top
 * assists, and the club milestones' top scorer and top assist. The match view showed every goal
 * correctly the whole time, because it read {@code event_json} directly — which is what made the failure
 * look like a data problem rather than a stub with a table-shaped name.
 *
 * <p>The event log built below is the real shape, taken from a played match: {@code playerId} and
 * {@code assistantId} arrive as <b>strings</b>, an unassisted goal has no assistant keys at all, and the
 * non-goal entries are the bulk of the array. A test built from tidied data would pass against an
 * implementation that cannot read what the engine actually writes.
 */
class GoalEventRepositoryTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private GoalEventRepository goals;
    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    /** A played match whose event log holds two goals, one of them assisted, plus the noise around them. */
    @Test
    @Transactional
    @DisplayName("goals and assists are read out of the event log")
    void goalsComeFromTheEventLog() {
        Competition league = aLeague("ZZ Goals");
        Match match = aPlayedMatch(league, """
                [
                  {"tick":1,"minute":0,"type":"DECISION","teamSide":"HOME","playerId":"9001","playerName":"N passer"},
                  {"tick":5,"minute":0,"type":"PASS","teamSide":"HOME","playerId":"9001","playerName":"N passer"},
                  {"tick":116,"minute":2,"type":"GOAL","teamSide":"HOME","playerId":"9002","playerName":"Ada Goals",
                   "scorerName":"Ada Goals","assistantId":"9003","assistantName":"Ben Assists",
                   "homeScoreAfter":1,"awayScoreAfter":0,"description":"*** GOAL HOME by Ada Goals ***"},
                  {"tick":492,"minute":12,"type":"GOAL","teamSide":"AWAY","playerId":"9004","playerName":"Cy Unassisted",
                   "scorerName":"Cy Unassisted","homeScoreAfter":1,"awayScoreAfter":1},
                  {"tick":700,"minute":14,"type":"SHOT_ON_TARGET","teamSide":"HOME","playerId":"9002"}
                ]""");

        List<GoalEvent> found = goals.findByMatchId(match.getId());

        assertEquals(2, found.size(), "expected the two GOAL entries and nothing else");
        assertEquals("Ada Goals", found.get(0).scorerName());
        assertEquals(9002L, found.get(0).scorerId());
        assertEquals(9003L, found.get(0).assistantId(), "the assisted goal lost its assist");
        assertEquals("Ben Assists", found.get(0).assistantName());
        assertEquals(2, found.get(0).minute());

        // The second goal has no assistant keys at all, which is the common case and the one a test built
        // from a full record would not cover.
        assertEquals("Cy Unassisted", found.get(1).scorerName());
        assertNull(found.get(1).assistantId(), "a goal with no assist was given one");
        assertNull(found.get(1).assistantName());
        assertEquals("AWAY", found.get(1).teamSide());
    }

    @Test
    @Transactional
    @DisplayName("a synthetic player id is not credited to anybody")
    void syntheticPlayerIdsAreNotScorers() {
        Competition league = aLeague("ZZ Synthetic");
        Match match = aPlayedMatch(league, """
                [{"tick":10,"minute":1,"type":"GOAL","teamSide":"HOME","playerId":"HOME-1",
                  "playerName":"Ghost Player","scorerName":"Ghost Player","homeScoreAfter":1,"awayScoreAfter":0}]
                """);

        List<GoalEvent> found = goals.findByMatchId(match.getId());

        assertTrue(found.isEmpty(),
                "an engine placeholder id became a scorer: " + found + ". A top-scorer row for somebody "
                        + "who cannot be clicked through to is worse than no row.");
    }

    @Test
    @Transactional
    @DisplayName("a competition's season returns every goal in it")
    void aCompetitionsSeasonReturnsItsGoals() {
        Competition league = aLeague("ZZ Season");
        aPlayedMatch(league, """
                [{"tick":10,"minute":1,"type":"GOAL","teamSide":"HOME","playerId":"9002","scorerName":"Ada Goals",
                  "homeScoreAfter":1,"awayScoreAfter":0}]
                """);

        List<GoalEvent> found = goals
                .findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(league.getId(), SEASON);

        assertFalse(found.isEmpty(),
                "the league top-scorers query returned nothing, which is the bug this class had");
        assertTrue(found.stream().anyMatch(g -> "Ada Goals".equals(g.scorerName())));
    }

    @Test
    @Transactional
    @DisplayName("an unplayed match and a match with no log contribute nothing, and neither throws")
    void emptyInputsAreEmptyResults() {
        Competition league = aLeague("ZZ Empty");
        Match played = aPlayedMatch(league, "[]");

        assertTrue(goals.findByMatchId(played.getId()).isEmpty(), "an empty event log produced a goal");
        assertTrue(goals.findByMatchId(-1L).isEmpty(), "an unknown match id produced a goal");
        assertTrue(goals.findByMatchId(null).isEmpty(), "a null match id produced a goal");
        assertTrue(goals.findByMatchSeasonYearAndScoredTrue(null).isEmpty(), "a null season produced a goal");
    }

    private Competition aLeague(String name) {
        Country country = new Country();
        country.setName(name + " " + System.nanoTime());
        country.setIsoCode("GG" + (char) ('A' + Math.abs(System.nanoTime()) % 20));
        country.setState(CountryState.SIMULATED);
        country = countries.save(country);

        Competition league = new Competition();
        league.setName(name + " " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(1);
        league.setCountry(country);
        return competitions.save(league);
    }

    private Match aPlayedMatch(Competition league, String eventJson) {
        Country country = league.getCountry();
        Team home = new Team();
        home.setName("ZZ Goals Home " + System.nanoTime());
        home.setCountry(country);
        home = teams.save(home);
        Team away = new Team();
        away.setName("ZZ Goals Away " + System.nanoTime());
        away.setCountry(country);
        away = teams.save(away);

        Match match = new Match();
        match.setCompetition(league);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setPlayed(true);
        match.setFinished(true);
        match.setHomeGoals(1);
        match.setAwayGoals(0);
        match.setSeasonYear(SEASON);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setMatchDate(LocalDateTime.of(2026, 2, 1, 19, 0));
        match.setEventJson(eventJson);
        return matches.save(match);
    }
}