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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A goal VAR ruled out must not be credited to a scorer.
 *
 * <p><b>The defect this guards.</b> {@code GoalEventRepository.isGoal} tested
 * {@code type.contains("GOAL")}, so {@code GOAL_DISALLOWED} and {@code VAR_GOAL_OVERTURNED} counted as
 * goals. That fed three things the owner looks at: the league's top scorers, the league's top assists,
 * and {@code LeagueMilestoneService}'s club top scorer and top assist. Across the shipped matches that was
 * 30 credits for goals that do not exist.
 *
 * <p><b>Why it is a defect and not a preference.</b> {@code BallResultHandler} asks VAR <i>before</i> it
 * calls {@code goalScored}, so the scoreline never counted these goals either — a striker shown on 5
 * beside a team that scored 3 is a table disagreeing with itself.
 *
 * <p><b>Why this goes through the repository and not the predicate.</b> A test asserting only
 * {@code countsAsGoal} would pass against a repository that no longer calls it, which is the exact gap
 * that let the substring rule look harmless for as long as it did. This writes a blob and asserts the
 * number that reaches the scorer table.
 *
 * <p>The blob is the real shape: {@code playerId} arrives as a <b>string</b>, and the ruled-out entries
 * are the ones the engine actually emits at {@code BallResultHandler:262}.
 */
class RuledOutGoalIsNotCreditedTest extends BaseTest {

    private static final int SEASON = 1;

    @Autowired private GoalEventRepository goals;
    @Autowired private MatchRepository matches;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("a disallowed goal and an overturned goal are not credited, and the real goal still is")
    void ruledOutGoalsAreNotCredited() {
        Competition league = aLeague("ZZ Ruled Out");
        Match match = aPlayedMatch(league, """
                [
                  {"tick":116,"minute":2,"type":"GOAL","teamSide":"HOME","playerId":"9002","playerName":"Ada Goals",
                   "scorerName":"Ada Goals","homeScoreAfter":1,"awayScoreAfter":0},
                  {"tick":800,"minute":20,"type":"GOAL_DISALLOWED","teamSide":"HOME","playerId":"9002",
                   "playerName":"Ada Goals","description":"GOAL DISALLOWED by VAR for HOME"},
                  {"tick":1200,"minute":30,"type":"VAR_GOAL_OVERTURNED","teamSide":"AWAY","playerId":"9004",
                   "playerName":"Cy Unassisted","varType":"GOAL","varDecision":"OVERTURNED"},
                  {"tick":1600,"minute":40,"type":"GOAL_KICK","teamSide":"HOME","playerId":"9002"}
                ]
                """);

        List<GoalEvent> found = goals.findByMatchId(match.getId());

        assertEquals(1, found.size(),
                "exactly one of those four is a goal that counted, but the scorer table was given "
                        + found.stream().map(GoalEvent::scorerName).toList());
        assertEquals("Ada Goals", found.get(0).scorerName());
        assertEquals(2, found.get(0).minute(), "the credited goal must be the real one, at minute 2");
    }

    @Test
    @Transactional
    @DisplayName("a match where every goal was overturned credits nobody, rather than naming a leader")
    void aMatchWithOnlyRuledOutGoalsCreditsNobody() {
        Competition league = aLeague("ZZ All Overturned");
        Match match = aPlayedMatch(league, """
                [
                  {"tick":800,"minute":20,"type":"GOAL_DISALLOWED","teamSide":"HOME","playerId":"9002",
                   "playerName":"Ada Goals"},
                  {"tick":1200,"minute":30,"type":"VAR_GOAL_OVERTURNED","teamSide":"AWAY","playerId":"9004",
                   "playerName":"Cy Unassisted"}
                ]
                """);

        assertTrue(goals.findByMatchId(match.getId()).isEmpty(),
                "a match in which every goal was overturned has no scorer, and LeagueMilestoneService "
                        + "would otherwise name a player who did not score");
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
        home.setName("ZZ Ruled Home " + System.nanoTime());
        home.setCountry(country);
        home = teams.save(home);
        Team away = new Team();
        away.setName("ZZ Ruled Away " + System.nanoTime());
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
