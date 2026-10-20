package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.commonmanager.model.User;
import org.tifo.commonmanager.repository.UserRepository;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.FantasyPick;
import org.tifo.footballmanager.newLogic.model.FantasyPlayerScore;
import org.tifo.footballmanager.newLogic.model.FantasyScoreLine;
import org.tifo.footballmanager.newLogic.model.FantasyTeam;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchPlayerStats;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.SkillName;
import org.tifo.footballmanager.newLogic.model.Skills;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyPickRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyPlayerScoreRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyScoreLineRepository;
import org.tifo.footballmanager.newLogic.repository.FantasyTeamRepository;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A gameweek is counted from the match statistics, once (owner, 2026-10-17).
 *
 * <p>The football is written by hand here — one match, four players, a known scoreline — because the claim
 * being tested is arithmetic and a simulated match would make the expected numbers unknowable. What is
 * real is everything around it: the rows are the ones the engine writes, the points come through the same
 * code the job calls, and the assertions are on what ended up in the database.
 *
 * <p><b>Four claims.</b> The points are right, including the bonus for the top ratings and the clean sheet
 * a midfielder earns; the armband doubles; a second run changes nothing; and an entry that joined into a
 * locked week scores nothing for it.
 */
class FantasyScoringTest extends BaseTest {

    private static final int SEASON = 1;
    private static final int WEEK = 1;

    @Autowired private FantasyScoringService scoring;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private MatchRepository matches;
    @Autowired private MatchPlayerStatsRepository matchStats;
    @Autowired private UserRepository users;
    @Autowired private FantasyTeamRepository fantasyTeams;
    @Autowired private FantasyPickRepository fantasyPicks;
    @Autowired private FantasyPlayerScoreRepository playerScores;
    @Autowired private FantasyScoreLineRepository scoreLines;

    @Test
    @Transactional
    @DisplayName("a gameweek is counted from the stats, the armband doubles, and a second run changes nothing")
    void aGameweekIsCountedOnce() {
        Country country = country();
        Competition league = league(country);
        Team home = club("ZZ Score Home " + UUID.randomUUID(), league, country);
        Team away = club("ZZ Score Away " + UUID.randomUUID(), league, country);

        Player keeper = player(home, Position.GK);
        Player defender = player(home, Position.DEF);
        Player midfielder = player(home, Position.MID);
        Player striker = player(away, Position.ATT);

        // Home win 2-0. Ratings chosen so the bonus order is defender, keeper, midfielder, striker.
        Match match = match(league, home, away, 2, 0);
        stats(match, keeper, 90, 0, 0, 0, 0, 4, 80);
        stats(match, defender, 90, 1, 0, 0, 0, 0, 90);
        stats(match, midfielder, 90, 0, 1, 0, 0, 0, 70);
        stats(match, striker, 90, 0, 0, 0, 0, 0, 60);

        User manager = manager();
        FantasyTeam team = fantasyTeam(country, manager, 1);
        pick(team, keeper, true, false);
        pick(team, defender, true, true);
        pick(team, midfielder, true, false);
        pick(team, striker, false, false);

        FantasyScoringService.ScoreResult result = scoring.scoreWeek(country.getId(), SEASON, WEEK);
        assertFalse(result.alreadyScored(), "the first pass scores the week");
        assertEquals(4, result.playersScored(), "four players appeared");
        assertEquals(1, result.teamsScored(), "one entry was in the game");

        // 2 appearance + 4 clean sheet + 1 for four saves + 2 bonus for the second-best rating
        assertEquals(9, pointsOf(keeper));
        // 2 appearance + 6 goal + 4 clean sheet + 3 bonus for the best rating
        assertEquals(15, pointsOf(defender));
        // 2 appearance + 3 assist + 1 clean sheet in midfield + 1 bonus
        assertEquals(7, pointsOf(midfielder));
        // 2 appearance and nothing else: his side conceded twice and he did not score
        assertEquals(2, pointsOf(striker));

        // The armband doubles the defender: 9 + 30 + 7, and the substitute scores nothing.
        assertEquals(46, teamPoints(team.getId()),
                "the captain counts twice and the bench counts for nothing");

        // A second run is a no-op, not a second set of rows.
        FantasyScoringService.ScoreResult again = scoring.scoreWeek(country.getId(), SEASON, WEEK);
        assertTrue(again.alreadyScored(), "a scored week is never scored again");
        assertEquals(4, playerScores.findBySeasonYearAndWeekNumberOrderByPointsDesc(SEASON, WEEK).size(),
                "and nothing was written a second time");
        assertEquals(4, scoreLines.findByFantasyTeamIdAndSeasonYear(team.getId(), SEASON).size());
        assertEquals(46, teamPoints(team.getId()));
    }

    @Test
    @Transactional
    @DisplayName("an entry that joined into a locked week scores nothing for that week")
    void anEntryThatJoinedLateScoresNothing() {
        Country country = country();
        Competition league = league(country);
        Team home = club("ZZ Late Home " + UUID.randomUUID(), league, country);
        Team away = club("ZZ Late Away " + UUID.randomUUID(), league, country);

        Player keeper = player(home, Position.GK);
        Match match = match(league, home, away, 1, 0);
        stats(match, keeper, 90, 0, 0, 0, 0, 2, 75);

        User manager = manager();
        // Joined while week 1 was locked, so he starts scoring in week 2.
        FantasyTeam late = fantasyTeam(country, manager, 2);
        pick(late, keeper, true, true);

        scoring.scoreWeek(country.getId(), SEASON, WEEK);

        assertTrue(scoreLines.findByFantasyTeamIdAndSeasonYear(late.getId(), SEASON).isEmpty(),
                "a manager who arrived after the football does not inherit its points");
        // The player's own gameweek is still counted: the player-points view is about the players.
        assertEquals(1, playerScores.findBySeasonYearAndWeekNumberOrderByPointsDesc(SEASON, WEEK).size());
    }

    // ------------------------------------------------------------------ helpers

    private int pointsOf(Player player) {
        List<FantasyPlayerScore> rows = playerScores.findByPlayerIdAndSeasonYear(player.getId(), SEASON);
        assertEquals(1, rows.size(), "one row per player per gameweek");
        return rows.get(0).getPoints();
    }

    private int teamPoints(Long teamId) {
        int total = 0;
        for (FantasyScoreLine line : scoreLines.findByFantasyTeamIdAndSeasonYear(teamId, SEASON)) {
            if (line.isStarter()) {
                total += line.isCaptain() ? 2 * line.getPoints() : line.getPoints();
            }
        }
        return total;
    }

    private Country country() {
        Country country = new Country();
        country.setName("ZZ Score " + UUID.randomUUID());
        country.setIsoCode("ZS");
        country.setReputation(1500);
        country.setYouthRating(1500);
        return countries.save(country);
    }

    private Competition league(Country country) {
        Competition competition = new Competition();
        competition.setName("ZZ Score League " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition.setCountry(country);
        return competitions.save(competition);
    }

    private Team club(String name, Competition league, Country country) {
        Team team = new Team();
        team.setName(name);
        team.setCompetition(league);
        team.setCountry(country);
        team.setReputation(50.0);
        team.setHumanControlled(false);
        return teams.save(team);
    }

    private Player player(Team team, Position position) {
        Skills skills = new Skills();
        for (SkillName name : new SkillName[]{SkillName.PACE, SkillName.STAMINA, SkillName.GOALKEEPER,
                SkillName.DEFENDER, SkillName.TECHNIQUE, SkillName.PLAYMAKER, SkillName.PASSING,
                SkillName.STRIKER}) {
            skills.setSkill(name, 12);
        }
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName("ZZ " + position + " " + UUID.randomUUID());
        p.setPosition(position);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return players.save(p);
    }

    private Match match(Competition league, Team home, Team away, int homeGoals, int awayGoals) {
        Match match = new Match();
        match.setCompetition(league);
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setSeasonYear(SEASON);
        match.setWeekNumber(WEEK);
        match.setDayNumber(3);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setPossessionHome(55.0);
        match.setPossessionAway(45.0);
        match.setPlayed(true);
        match.setFinished(true);
        match.setHomeResultRevealed(true);
        match.setAwayResultRevealed(true);
        return matches.save(match);
    }

    private void stats(Match match, Player player, int minutes, int goals, int assists,
                       int yellow, int red, int saves, int rating) {
        MatchPlayerStats row = new MatchPlayerStats();
        row.setMatch(match);
        row.setPlayer(player);
        row.setMinutesPlayed(minutes);
        row.setGoals(goals);
        row.setAssists(assists);
        row.setYellowCards(yellow);
        row.setRedCards(red);
        row.setSaves(saves);
        row.setRating(rating);
        row.setPositionPlayed(player.getPosition());
        matchStats.save(row);
    }

    private User manager() {
        User user = new User();
        user.setUsername("zz_score_" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPassword("not-a-real-password");
        return users.save(user);
    }

    private FantasyTeam fantasyTeam(Country country, User manager, int scoresFromWeek) {
        FantasyTeam team = new FantasyTeam();
        team.setUser(manager);
        team.setCountry(country);
        team.setSeasonYear(SEASON);
        team.setScoresFromWeek(scoresFromWeek);
        return fantasyTeams.save(team);
    }

    private void pick(FantasyTeam team, Player player, boolean starter, boolean captain) {
        FantasyPick pick = new FantasyPick();
        pick.setFantasyTeam(team);
        pick.setPlayer(player);
        pick.setStarter(starter);
        pick.setCaptain(captain);
        pick.setPurchasePrice(5.0);
        fantasyPicks.save(pick);
    }
}
