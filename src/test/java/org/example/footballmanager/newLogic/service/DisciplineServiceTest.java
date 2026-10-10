package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerDiscipline;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cards, and what they cost (owner ruling, 2026-10-10).
 *
 * <p><b>A red card bans the first next official match</b> — everything except a friendly. <b>Yellows
 * accumulate in the league:</b> 3 earns one match, 6 earns two, 9 earns three. Counters reset per season.
 *
 * <p><b>The part that is easy to get wrong.</b> If the yellow counter reset the moment three were reached,
 * six and nine could never be reached at all and two thirds of the rule would be dead letters. So the
 * counter keeps its place while the earned bans are outstanding, and resets only once they have been
 * served. Several tests below exist to hold that specific behaviour.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class DisciplineServiceTest {

    @Autowired DisciplineService discipline;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired CompetitionRepository competitions;
    @Autowired MatchFixtureRepository fixtures;

    private static final int SEASON = 1;

    private Player player;
    private Competition league;
    private Team home;
    private Team away;

    @BeforeEach
    void setUp() {
        home = teams.save(team("Discipline Home"));
        away = teams.save(team("Discipline Away"));

        player = new Player();
        player.setName("Disciplined Player");
        player.setTeam(home);
        player = players.save(player);

        league = new Competition();
        league.setName("Discipline League " + System.nanoTime());
        league.setType(CompetitionType.LEAGUE);
        league = competitions.save(league);
    }

    private Team team(String prefix) {
        Team team = new Team();
        team.setName(prefix + " " + System.nanoTime());
        return team;
    }

    /** A friendly is a fixture that belongs to no competition. */
    private MatchFixture aFriendly() {
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setCompetition(null);
        fixture.setSeasonYear(SEASON);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setMatchDate(LocalDateTime.now().plusDays(1));
        return fixtures.save(fixture);
    }

    private MatchFixture aLeagueMatch(int week) {
        MatchFixture fixture = new MatchFixture();
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setCompetition(league);
        fixture.setSeasonYear(SEASON);
        fixture.setWeekNumber(week);
        fixture.setDayNumber(3);
        fixture.setMatchDate(LocalDateTime.now().plusDays(7L * week));
        return fixtures.save(fixture);
    }

    // ── the red card ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a red card bans the first next official match")
    void aRedCardBarsTheNextOfficialMatch() {
        discipline.record(player.getId(), SEASON, league, 0, 1);

        MatchFixture next = aLeagueMatch(2);
        assertTrue(discipline.suspensionFor(player.getId(), SEASON, next).isPresent(),
                "a red card must bar the very next official match");
        assertTrue(discipline.suspensionFor(player.getId(), SEASON, next).get().contains("sent off"));
    }

    @Test
    @DisplayName("a red card does not bar a friendly")
    void aRedCardDoesNotBarAFriendly() {
        discipline.record(player.getId(), SEASON, league, 0, 1);

        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aFriendly()).isPresent(),
                "the owner said everything except a friendly is official, so the ban waits for one");
    }

    @Test
    @DisplayName("the ban is served by playing, and only then")
    void theBanIsServedByPlaying() {
        discipline.record(player.getId(), SEASON, league, 0, 1);
        MatchFixture banned = aLeagueMatch(2);

        assertTrue(discipline.suspensionFor(player.getId(), SEASON, banned).isPresent());
        discipline.serve(player.getId(), SEASON, banned);

        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(3)).isPresent(),
                "one red card is one match, and the next match he is available again");
    }

    @Test
    @DisplayName("a red card bars any competition, not only the one it happened in")
    void aRedCardIsClubWide() {
        discipline.record(player.getId(), SEASON, league, 0, 1);

        Competition cup = new Competition();
        cup.setName("Discipline Cup " + System.nanoTime());
        cup.setType(CompetitionType.CUP);
        cup = competitions.save(cup);

        MatchFixture cupMatch = new MatchFixture();
        cupMatch.setHomeTeam(home);
        cupMatch.setAwayTeam(away);
        cupMatch.setCompetition(cup);
        cupMatch.setSeasonYear(SEASON);
        cupMatch.setMatchDate(LocalDateTime.now().plusDays(3));
        cupMatch = fixtures.save(cupMatch);

        assertTrue(discipline.suspensionFor(player.getId(), SEASON, cupMatch).isPresent(),
                "the owner said the first next official match OF THE CLUB, and a cup tie is one");
    }

    // ── the yellow accumulation ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("three yellows earn one league match")
    void threeYellowsEarnOneMatch() {
        discipline.record(player.getId(), SEASON, league, 2, 0);
        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(2)).isPresent(),
                "two yellows earn nothing");

        discipline.record(player.getId(), SEASON, league, 1, 0);
        assertTrue(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(3)).isPresent(),
                "the third yellow earns one match");
    }

    @Test
    @DisplayName("yellows earned in a friendly do not count")
    void friendlyYellowsDoNotCount() {
        discipline.record(player.getId(), SEASON, null, 3, 0);

        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(2)).isPresent(),
                "the accumulation is the league's own; a friendly yellow is not a league yellow");
    }

    @Test
    @DisplayName("six yellows earn two matches")
    void sixYellowsEarnTwoMatches() {
        discipline.record(player.getId(), SEASON, league, 6, 0);

        MatchFixture first = aLeagueMatch(2);
        MatchFixture second = aLeagueMatch(3);
        MatchFixture third = aLeagueMatch(4);

        assertTrue(discipline.suspensionFor(player.getId(), SEASON, first).isPresent());
        discipline.serve(player.getId(), SEASON, first);
        assertTrue(discipline.suspensionFor(player.getId(), SEASON, second).isPresent(),
                "six yellows is two matches, not one");
        discipline.serve(player.getId(), SEASON, second);
        assertFalse(discipline.suspensionFor(player.getId(), SEASON, third).isPresent());
    }

    @Test
    @DisplayName("nine yellows earn three matches")
    void nineYellowsEarnThreeMatches() {
        discipline.record(player.getId(), SEASON, league, 9, 0);

        for (int week = 2; week <= 4; week++) {
            MatchFixture fixture = aLeagueMatch(week);
            assertTrue(discipline.suspensionFor(player.getId(), SEASON, fixture).isPresent(),
                    "nine yellows is three matches, so week " + week + " must still be a ban");
            discipline.serve(player.getId(), SEASON, fixture);
        }
        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(5)).isPresent());
    }

    @Test
    @DisplayName("the counter resets only once the earned bans are served, so six and nine stay reachable")
    void theCounterWaitsForTheBansToBeServed() {
        discipline.record(player.getId(), SEASON, league, 3, 0);
        PlayerDiscipline afterThree = discipline.recordFor(player.getId(), SEASON, league).orElseThrow();
        assertEquals(3, afterThree.getYellowCards(),
                "resetting the counter at three would mean six and nine could never be reached, and two "
                        + "thirds of the owner's rule would be dead letters");

        discipline.serve(player.getId(), SEASON, aLeagueMatch(2));
        PlayerDiscipline afterServing = discipline.recordFor(player.getId(), SEASON, league).orElseThrow();
        assertEquals(0, afterServing.getYellowCards(), "now the cycle is complete and the counter resets");

        // And the next cycle starts from zero, earning again at three.
        discipline.record(player.getId(), SEASON, league, 3, 0);
        assertTrue(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(4)).isPresent(),
                "after the reset the accumulation starts again");
    }

    @Test
    @DisplayName("a yellow ban is served in the league, not in every competition")
    void yellowBansAreLeagueScoped() {
        discipline.record(player.getId(), SEASON, league, 3, 0);

        assertTrue(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(2)).isPresent());

        Competition cup = new Competition();
        cup.setName("Scope Cup " + System.nanoTime());
        cup.setType(CompetitionType.CUP);
        cup = competitions.save(cup);
        MatchFixture cupMatch = new MatchFixture();
        cupMatch.setHomeTeam(home);
        cupMatch.setAwayTeam(away);
        cupMatch.setCompetition(cup);
        cupMatch.setSeasonYear(SEASON);
        cupMatch.setMatchDate(LocalDateTime.now().plusDays(3));
        cupMatch = fixtures.save(cupMatch);

        assertFalse(discipline.suspensionFor(player.getId(), SEASON, cupMatch).isPresent(),
                "yellows accumulate in the league and the ban is a league ban — that is the difference "
                        + "the owner drew between the two rules");
    }

    // ── the season reset ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("counters are per season, so a new campaign starts clean")
    void theNewSeasonStartsClean() {
        discipline.record(player.getId(), SEASON, league, 6, 0);
        assertTrue(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(2)).isPresent());

        MatchFixture nextSeason = aLeagueMatch(3);
        nextSeason.setSeasonYear(2);
        nextSeason = fixtures.save(nextSeason);

        assertFalse(discipline.suspensionFor(player.getId(), 2, nextSeason).isPresent(),
                "the reset is structural — the record is keyed by season, so there is no sweep to forget "
                        + "to run and no counter that can survive into a new campaign");
    }

    @Test
    @DisplayName("the thresholds are the owner's, exactly")
    void theThresholdsAreTheOwners() {
        assertEquals(0, PlayerDiscipline.leagueBansEarnedBy(0));
        assertEquals(0, PlayerDiscipline.leagueBansEarnedBy(2));
        assertEquals(1, PlayerDiscipline.leagueBansEarnedBy(3), "3 yellows -> 1 match");
        assertEquals(1, PlayerDiscipline.leagueBansEarnedBy(5), "still in the first band");
        assertEquals(2, PlayerDiscipline.leagueBansEarnedBy(6), "6 yellows -> 2 matches");
        assertEquals(2, PlayerDiscipline.leagueBansEarnedBy(8));
        assertEquals(3, PlayerDiscipline.leagueBansEarnedBy(9), "9 yellows -> 3 matches");
    }

    @Test
    @DisplayName("a player with no record is available")
    void aCleanPlayerIsAvailable() {
        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aLeagueMatch(2)).isPresent());
        assertFalse(discipline.suspensionFor(player.getId(), SEASON, aFriendly()).isPresent());
    }
}