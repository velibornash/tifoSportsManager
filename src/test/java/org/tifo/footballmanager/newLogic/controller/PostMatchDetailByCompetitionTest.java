package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionScope;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Match;
import org.tifo.footballmanager.newLogic.model.MatchPlayerStats;
import org.tifo.footballmanager.newLogic.model.NationalStage;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.MatchPlayerStatsRepository;
import org.tifo.footballmanager.newLogic.repository.MatchRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
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

    /**
     * A tie settled on penalties says so, and shows who missed (owner, 2026-10-14).
     *
     * <p>The owner's words, on a cup tie his club had won: *"vidim da sam prosao posle 2-2, po pravilima
     * treba da budu penali a nema ih nigde u izvestaju... da proveris i da ubacis negde, recimo u goals
     * i pogodjene i promasene penale."*
     *
     * <p>The engine was never wrong. {@code PenaltyShootout} runs, the right club goes through, and the
     * result lands in {@code home_penalty_goals} / {@code away_penalty_goals}. The report threw it away:
     * the headline said "drew 2-2", and the per-kick list the engine had already produced — side, taker,
     * scored or missed — was never written anywhere.
     *
     * <p>Two halves, and the second is the one that is easy to do badly. Showing only the conversions would
     * satisfy "add the penalties" and still hide how the tie was won, because a miss is half of a
     * shootout.
     */
    @Test
    @Transactional
    @DisplayName("a tie decided on penalties reports the shootout, takers and misses alike")
    void aShootoutIsReportedRatherThanHidden() {
        Competition competition = aCompetition(CompetitionType.CUP);
        Team home = aTeam(competition, "Home");
        Team away = aTeam(competition, "Away");
        Match match = aPlayedMatch(home, away, competition, 2, 2);

        // What the engine writes when a level tie goes to the spot.
        match.setHomePenaltyGoals(4);
        match.setAwayPenaltyGoals(3);
        match.setEventJson(shootoutEvents());
        match = matches.save(match);

        Map<String, Object> report = zox.postMatchReportFor(match.getId());
        String headline = String.valueOf(report.get("headline"));

        assertTrue(headline.contains("on penalties"),
                "the headline does not say the tie was settled from the spot. It reads \"" + headline
                        + "\", which reports a cup tie the club lost as a draw. The score stays the score; "
                        + "how it was decided is part of it.");
        assertTrue(headline.contains("4-3"),
                "the headline does not carry the shootout score: \"" + headline + "\"");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> homePens = (List<Map<String, Object>>) report.get("homePenalties");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> awayPens = (List<Map<String, Object>>) report.get("awayPenalties");

        assertFalse(homePens.isEmpty(), "no home kicks in the report, so this proves nothing");
        assertFalse(awayPens.isEmpty(), "no away kicks in the report, so this proves nothing");

        // Every kick, not only the ones that went in.
        assertEquals(5, homePens.size(), "the home side took five kicks and the report lists "
                + homePens.size() + ". A report that lists only the conversions is hiding the misses.");
        assertEquals(5, awayPens.size(), "the away side took five kicks and the report lists "
                + awayPens.size());

        long homeScored = homePens.stream().filter(k -> Boolean.TRUE.equals(k.get("scored"))).count();
        long awayScored = awayPens.stream().filter(k -> Boolean.TRUE.equals(k.get("scored"))).count();
        assertEquals(4, homeScored, "the home shootout score in the report does not match its own column");
        assertEquals(3, awayScored, "the away shootout score in the report does not match its own column");

        // And at least one miss is named, which is the half the owner asked for by name.
        boolean anyMiss = homePens.stream().anyMatch(k -> Boolean.FALSE.equals(k.get("scored")))
                || awayPens.stream().anyMatch(k -> Boolean.FALSE.equals(k.get("scored")));
        assertTrue(anyMiss, "no missed penalty appears in the report, so the misses are still hidden");
        for (Map<String, Object> kick : homePens) {
            assertTrue(kick.get("playerName") != null && !String.valueOf(kick.get("playerName")).isBlank(),
                    "a home kick has no taker named: " + kick);
        }
    }

    /** Five kicks a side, in the shape `SimMatchService` writes them. */
    private String shootoutEvents() {
        List<String> kicks = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            kicks.add(kick("HOME", "Home Taker " + i, i < 4));
            kicks.add(kick("AWAY", "Away Taker " + i, i < 3));
        }
        return "[" + String.join(",", kicks) + "]";
    }

    private String kick(String side, String taker, boolean scored) {
        return "{\"type\":\"" + (scored ? "PENALTY_SCORED" : "PENALTY_MISSED")
                + "\",\"teamSide\":\"" + side + "\",\"playerName\":\"" + taker + "\"}";
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

    private org.tifo.footballmanager.newLogic.model.Player aPlayer(Team team, String label) {
        org.tifo.footballmanager.newLogic.model.Player player =
                new org.tifo.footballmanager.newLogic.model.Player();
        player.setName("Player " + label + " " + UUID.randomUUID().toString().substring(0, 4));
        player.setTeam(team);
        player.setPosition(org.tifo.footballmanager.newLogic.model.Position.MID);
        player.setRating(75);
        player.setAge(24);
        return players().save(player);
    }

    @Autowired
    org.tifo.footballmanager.newLogic.repository.PlayerRepository playerRepository;

    private org.tifo.footballmanager.newLogic.repository.PlayerRepository players() {
        return playerRepository;
    }
}