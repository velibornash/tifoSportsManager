package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The post-match report works for every competition, and does not depend on a team's name
 * ({@code P0-PREV-5}).
 *
 * <p>The card's exit criterion was deliberately about <b>proof</b> rather than change: the match view and
 * the ZOX endpoints branch on nothing, so cup ties, international club cups, senior internationals and
 * U-21 internationals already use the same screen. "It is type-agnostic" was an assumption from reading
 * the code, and this is the same assumption that has been wrong three times this week.
 *
 * <p>So the same report is built over the same players in four different competitions and required to
 * produce the same thing. If any of them branched on competition type, or if a competition's teams were
 * shaped differently, this fails.
 */
class PostMatchDetailByCompetitionTest extends BaseTest {

    @Autowired
    ZoxApiController zox;

    @Autowired
    MatchRepository matches;

    @Autowired
    MatchPlayerStatsRepository stats;

    @Autowired
    TeamRepository teams;

    @Autowired
    CompetitionRepository competitions;

    @Test
    @Transactional
    @DisplayName("the report is identical across league, cup, club cup and international")
    void theReportDoesNotCareWhatKindOfMatchItWas() {
        List<CompetitionType> types = List.of(
                CompetitionType.LEAGUE, CompetitionType.CUP,
                CompetitionType.INTERNATIONAL, CompetitionType.TOURNAMENT);

        for (CompetitionType type : types) {
            Competition competition = aCompetition(type);
            Team home = aTeam(competition, "Home");
            Team away = aTeam(competition, "Away");
            Match match = aPlayedMatch(home, away, competition, 2, 1);

            Map<String, Object> report = zox.postMatchReportFor(match.getId());

            assertNotNull(report.get("headline"), type + ": no headline");
            assertNotNull(report.get("playerOfTheMatch"), type + ": no player of the match");
            assertFalse(performerNames(report, "homeTopPerformers").isEmpty(),
                    type + ": the home top performers are empty. The report does not branch on "
                            + "competition type, so this must work for every one of them.");
            assertFalse(performerNames(report, "awayTopPerformers").isEmpty(),
                    type + ": the away top performers are empty");
            assertNotNull(report.get("timeline"), type + ": no timeline");
        }
    }

    /**
     * Two teams with the same name must not be confused for each other.
     *
     * <p>The report decided whose performance it was by comparing <b>team names</b>. Renaming a team does
     * not break that - both the match and the stats rows point at the same {@code Team} row, so a rename
     * moves all of them together, which is what the first version of this test did and what made it
     * pass against code it was supposed to fail.
     *
     * <p>What <b>does</b> break it is two different teams sharing a name, which is ordinary in this game:
     * a senior national side and its U-21 side sit in the same family of names, two clubs can be called
     * the same thing, and the earlier fixture renames produced exactly this. With name matching both
     * sides match both filters, so every player appears in <i>both</i> top-performer lists and the
     * player of the match is picked from a doubled-up field.
     */
    @Test
    @Transactional
    @DisplayName("two teams with the same name are still told apart")
    void twoTeamsWithTheSameNameAreStillApart() {
        Competition competition = aCompetition(CompetitionType.INTERNATIONAL);
        Team home = aTeam(competition, "Germany");
        Team away = aTeam(competition, "Germany");
        // aTeam appends a UUID to keep names distinct, which is right everywhere else and defeats the
        // point here. Both sides are named exactly "Germany", as two sides in this game genuinely can be.
        home.setName("Germany");
        away.setName("Germany");
        teams.save(home);
        teams.save(away);
        assertEquals(home.getName(), away.getName(),
                "precondition: two distinct teams deliberately sharing a name");
        assertTrue(home.getId() != null && away.getId() != null && !home.getId().equals(away.getId()),
                "and they are different rows, which is the whole point");

        Match match = aPlayedMatch(home, away, competition, 2, 1);
        Map<String, Object> report = zox.postMatchReportFor(match.getId());

        List<String> homeNames = performerNames(report, "homeTopPerformers");
        List<String> awayNames = performerNames(report, "awayTopPerformers");

        assertFalse(homeNames.isEmpty(), "the home side has performers");
        assertFalse(awayNames.isEmpty(), "the away side has performers");
        for (String name : homeNames) {
            assertFalse(awayNames.contains(name),
                    "\"" + name + "\" is listed as a performer for BOTH sides. The report is matching "
                            + "on the team's name and both are called \"Germany\", so every player "
                            + "belongs to both sides at once. Home: " + homeNames + " away: " + awayNames);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> performerNames(Map<String, Object> report, String key) {
        Object rows = report.get(key);
        if (!(rows instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Map<String, Object>>) list).stream()
                .map(row -> String.valueOf(row.get("playerName")))
                .toList();
    }

    private Competition aCompetition(CompetitionType type) {
        Competition competition = new Competition();
        competition.setName(type + " " + UUID.randomUUID());
        competition.setType(type);
        competition.setScope(type == CompetitionType.LEAGUE || type == CompetitionType.CUP
                ? CompetitionScope.NATIONAL : CompetitionScope.INTERNATIONAL);
        competition.setTeamType(type == CompetitionType.LEAGUE || type == CompetitionType.CUP
                ? CompetitionTeamType.CLUB : CompetitionTeamType.NATIONAL_TEAM);
        competition.setTier(1);
        competition.setNationalStage(NationalStage.OTHER);
        return competitions.save(competition);
    }

    private Team aTeam(Competition competition, String label) {
        Team team = new Team();
        team.setName(label + " " + UUID.randomUUID().toString().substring(0, 4));
        team.setFormation("4-3-3");
        team = teams.save(team);
        team.setCompetition(competition);
        return teams.save(team);
    }

    private Match aPlayedMatch(Team home, Team away, Competition competition, int homeGoals, int awayGoals) {
        Match match = new Match();
        match.setHomeTeam(home);
        match.setAwayTeam(away);
        match.setHomeGoals(homeGoals);
        match.setAwayGoals(awayGoals);
        match.setCompetition(competition);
        match.setSeasonYear(1);
        match.setPlayed(true);
        match.setMatchDate(java.time.LocalDateTime.of(2026, 1, 5, 15, 0));
        match = matches.save(match);

        for (int i = 0; i < 4; i++) {
            MatchPlayerStats row = new MatchPlayerStats();
            row.setMatch(match);
            row.setPlayer(i % 2 == 0 ? aPlayer(home, "H" + i) : aPlayer(away, "A" + i));
            row.setRating(70 + i);
            row.setMinutesPlayed(90);
            stats.save(row);
        }
        return match;
    }

    private org.example.footballmanager.newLogic.model.Player aPlayer(Team team, String label) {
        org.example.footballmanager.newLogic.model.Player player =
                new org.example.footballmanager.newLogic.model.Player();
        player.setName("Player " + label + " " + UUID.randomUUID().toString().substring(0, 4));
        player.setTeam(team);
        player.setPosition(org.example.footballmanager.newLogic.model.Position.MID);
        player.setRating(75);
        player.setAge(24);
        return players().save(player);
    }

    @Autowired
    org.example.footballmanager.newLogic.repository.PlayerRepository playerRepository;

    private org.example.footballmanager.newLogic.repository.PlayerRepository players() {
        return playerRepository;
    }
}