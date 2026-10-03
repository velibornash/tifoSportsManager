package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The schedule can be asked for one kind of football instead of only the league.
 *
 * <p><b>Why the parameter exists.</b> {@code getSchedule} resolved exactly one competition — the club's
 * league — so a cup or an international fixture was invisible on it. That is why the frontend's cup and
 * international screens pointed at fabricated data: <b>there was nothing real to ask for.</b> Every row
 * already carried {@code competitionType}, so this is a query choice and a filter rather than a new
 * endpoint.
 *
 * <p><b>The rule that matters most here is the one about failing quietly.</b> An unrecognised
 * {@code competitionType} falls back to the league rather than to an empty list, because a filter that
 * silently returned the wrong rows would fill the screen with plausible football from the wrong
 * competition — which is the exact failure this whole task exists to remove. The tests below pin both
 * halves: the recognised values filter, and an unrecognised one does <b>not</b> pretend.
 */
@Import(ControllerAuthFixture.class)
class TeamScheduleCompetitionFilterTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    CompetitionRepository competitions;

    @Autowired
    MatchFixtureRepository fixtures;

    private Team club;
    private Competition league;
    private Competition cup;
    private Competition internationals;

    /**
     * Whitespace stripped before any substring match.
     *
     * <p>Jackson pretty-prints in this application, so a row reads {@code "round" : 5} and an exact
     * substring never matches. Three of these tests failed on that alone before this helper existed — the
     * trap is recorded in {@code CountryTeamPlayersDisclosureTest} and I walked into it again.
     */
    private static String tight(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString()
                .replace(" ", "").replace("\n", "").replace("\r", "");
    }

    @BeforeEach
    @Transactional
    void aClubInThreeCompetitions() {
        club = auth.club("Schedule");

        league = competition("League", CompetitionType.LEAGUE);
        cup = competition("Cup", CompetitionType.CUP);
        internationals = competition("Internationals", CompetitionType.INTERNATIONAL);

        fixture(league, 3);
        fixture(cup, 5);
        fixture(internationals, 7);
    }

    private Competition competition(String label, CompetitionType type) {
        Competition c = new Competition();
        c.setName(label + " " + club.getName());
        c.setType(type);
        c.setScope(type == CompetitionType.INTERNATIONAL
                ? CompetitionScope.INTERNATIONAL : CompetitionScope.NATIONAL);
        c.setTeamType(CompetitionTeamType.CLUB);
        c.setTier(type == CompetitionType.LEAGUE ? 1 : null);
        c.setCountry(club.getCountry());
        c.setTeamsPerCompetition(16);
        return competitions.save(c);
    }

    /** The round number is the fixture's identifier here: 3 = league, 5 = cup, 7 = internationals. */
    private void fixture(Competition competition, int round) {
        for (int i = 0; i < 2; i++) {
            MatchFixture f = new MatchFixture();
            f.setCompetition(competition);
            f.setSeasonYear(1);
            f.setWeekNumber(round);
            f.setRoundNumber(round);
            f.setHomeTeam(club);
            f.setAwayTeam(club);
            f.setMatchDate(LocalDateTime.of(2026, 3, round, 15, 0));
            fixtures.save(f);
        }
    }

    // ── The filter ───────────────────────────────────────────────────────────────────────────────────

    private String scheduleFor(String type, String bearer) throws Exception {
        return tight(mockMvc.perform(get("/teams/{teamId}/schedule", club.getId())
                        .param("seasonYear", "1")
                        .param("competitionType", type)
                        .header("Authorization", bearer))
                .andExpect(status().isOk()));
    }

    private String unfilteredScheduleFor(String bearer) throws Exception {
        return tight(mockMvc.perform(get("/teams/{teamId}/schedule", club.getId())
                        .param("seasonYear", "1")
                        .header("Authorization", bearer))
                .andExpect(status().isOk()));
    }

    @Test
    @DisplayName("asking for the cup answers with the cup")
    void askingForTheCupAnswersWithTheCup() throws Exception {
        String body = scheduleFor("CUP", auth.bearerManaging(UserRole.REGULAR, club));

        assertTrue(body.contains("\"round\":5"), "the cup rows should be present: " + body);
        assertTrue(!body.contains("\"round\":3"), "league rows must not answer a cup request: " + body);
        assertTrue(!body.contains("\"round\":7"), "international rows must not either: " + body);
    }

    @Test
    @DisplayName("asking for internationals answers with internationals")
    void askingForInternationalsAnswersWithThem() throws Exception {
        String body = scheduleFor("INTERNATIONAL", auth.bearerManaging(UserRole.REGULAR, club));

        assertTrue(body.contains("\"round\":7"), "international rows should be present: " + body);
        assertTrue(!body.contains("\"round\":3"), "league rows must not answer that: " + body);
    }

    @Test
    @DisplayName("no filter keeps the old behaviour, the club's league")
    void noFilterKeepsTheOldBehaviour() throws Exception {
        String body = unfilteredScheduleFor(auth.bearerManaging(UserRole.REGULAR, club));

        assertTrue(body.contains("\"round\":3"), "the league should still answer with no filter: " + body);
    }

    /**
     * The quiet-failure guard, and the reason this test exists.
     *
     * <p>{@code FRIENDLY} is <b>not</b> a {@code CompetitionType} — the enum is {@code LEAGUE,
     * INTERNATIONAL, TOURNAMENT, CUP} — and a friendlies screen was about to ask for it. Had an
     * unrecognised value quietly meant "no filter", that screen would have filled with <b>league</b>
     * fixtures labelled as friendlies: plausible rows from the wrong competition, which is the exact
     * failure this task exists to remove. Answering with the league and saying so beats inventing rows.
     */
    @Test
    @DisplayName("an unrecognised competitionType falls back rather than inventing rows")
    void anUnrecognisedTypeDoesNotGuess() throws Exception {
        String body = scheduleFor("FRIENDLY", auth.bearerManaging(UserRole.REGULAR, club));

        assertTrue(!body.contains("FRIENDLY"),
                "the response echoed a type the world does not have: " + body);
        assertTrue(body.contains("\"competitionType\":\"LEAGUE\"") || body.contains("\"round\":3"),
                "an unknown type must not fabricate rows of that type: " + body);
    }

    /** The route still needs a token, with the new parameter present. */
    @Test
    @DisplayName("an anonymous caller cannot read a filtered schedule")
    void anAnonymousCallerCannotReadIt() throws Exception {
        int code = mockMvc.perform(get("/teams/{teamId}/schedule", club.getId())
                        .param("seasonYear", "1")
                        .param("competitionType", "CUP"))
                .andReturn().getResponse().getStatus();

        assertTrue(code == 401 || code == 403 || code == 302,
                "expected a refusal, got " + code);
    }

    @Test
    @DisplayName("a manager can still read a rival's schedule — every manager reads the league table")
    void aManagerCanStillReadAnySchedule() throws Exception {
        assertEquals(200, mockMvc.perform(get("/teams/{teamId}/schedule", club.getId())
                        .param("seasonYear", "1")
                        .param("competitionType", "CUP")
                        .header("Authorization", auth.bearer(UserRole.REGULAR)))
                .andReturn().getResponse().getStatus());
    }
}