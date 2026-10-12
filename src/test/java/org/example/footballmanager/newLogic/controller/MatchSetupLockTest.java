package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Match;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An hour before kickoff the manager cannot change his match setup (owner, 2026-10-11).
 *
 * <p>The owner's words: *"sat vremena pre početka utakmice igrač ne sme više podešavati taktiku za taj
 * meč."* This test is that sentence, written down and then broken on purpose before the guard exists.
 *
 * <p>Two halves, and they are not the same check:
 *
 * <ul>
 *   <li><b>before</b> the prepare job has run there is nothing to protect, and a manager may change
 *       everything — a club with no match in an hour is not a club in trouble;</li>
 *   <li><b>after</b> it has run, the match has been decided without his change and every write is
 *       refused.</li>
 * </ul>
 *
 * <p>The second half is the one that matters, because a lock that is only about the clock is a lock
 * that an app that was down for the hour does not have.
 */
@Import(ControllerAuthFixture.class)
class MatchSetupLockTest extends BaseTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired private ControllerAuthFixture auth;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchRepository matches;

    private Team home;
    private Team away;

    @BeforeEach
    void aClubWithAMatchOnDayThree() {
        Competition competition = new Competition();
        competition.setName("ZZ Lock " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        home = aTeam("ZZ Lock Home " + UUID.randomUUID());
        away = aTeam("ZZ Lock Away " + UUID.randomUUID());

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(away);
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        fixtures.save(fixture);
    }

    @Test
    @DisplayName("before the match is computed, tactics and lineup template are editable")
    void openBeforeTheMatchIsDecided() throws Exception {
        mockMvc.perform(put("/teams/{id}/tactics-editor", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home))
                        .contentType("application/json")
                        .content(tacticsPayload()))
                .andExpect(status().isOk());

        mockMvc.perform(put("/teams/{id}/lineup-template", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home))
                        .contentType("application/json")
                        .content(lineupPayload()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("after the match is computed, tactics and lineup template are refused with a reason")
    void closedOnceTheMatchIsComputed() throws Exception {
        prepareTodaysFixture();

        mockMvc.perform(put("/teams/{id}/tactics-editor", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home))
                        .contentType("application/json")
                        .content(tacticsPayload()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.reason").exists());

        mockMvc.perform(put("/teams/{id}/lineup-template", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home))
                        .contentType("application/json")
                        .content(lineupPayload()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.reason").exists());
    }

    @Test
    @DisplayName("the away side is locked too - which side it is drawn must not matter")
    void theAwayClubIsLockedAsWell() throws Exception {
        prepareTodaysFixture();

        mockMvc.perform(put("/teams/{id}/tactics-editor", away.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, away))
                        .contentType("application/json")
                        .content(tacticsPayload()))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("at kickoff the match is published and the club is open again")
    void openAgainAfterKickoff() throws Exception {
        MatchFixture fixture = prepareTodaysFixture();
        fixture.setPlayed(true);
        fixture.setPlayedMatch(matches.findById(fixture.getPreparedMatchId()).orElseThrow());
        fixtures.save(fixture);

        mockMvc.perform(put("/teams/{id}/tactics-editor", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home))
                        .contentType("application/json")
                        .content(tacticsPayload()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the page can be told, before the manager tries, that his match is closed")
    void theLockIsReadableWithoutTrying() throws Exception {
        // A 423 only reaches a manager who has already tried to save something, and a disabled button
        // with no explanation is worse than either: it reads as a broken game. The page needs to ask.
        mockMvc.perform(get("/teams/{id}/match-lock", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));

        prepareTodaysFixture();

        mockMvc.perform(get("/teams/{id}/match-lock", home.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, home)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.homeOrAway").value("HOME"))
                .andExpect(jsonPath("$.opponent").value(away.getName()))
                // Day 3 is the 19:00 league slot. The hour comes from the calendar, not from a literal.
                .andExpect(jsonPath("$.kickoffHour").value(19))
                .andExpect(jsonPath("$.reason").isNotEmpty());

        // The same question from the other side must answer about the same match, not the other one.
        mockMvc.perform(get("/teams/{id}/match-lock", away.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, away)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.homeOrAway").value("AWAY"))
                .andExpect(jsonPath("$.opponent").value(home.getName()));
    }

    @Test
    @DisplayName("a club with no match in the hour is never locked")
    void aClubWithoutAMatchIsNeverLocked() throws Exception {
        Team lonely = aTeam("ZZ Lock Lonely " + UUID.randomUUID());

        mockMvc.perform(put("/teams/{id}/tactics-editor", lonely.getId())
                        .header("Authorization", auth.bearerManaging(UserRole.OWNER, lonely))
                        .contentType("application/json")
                        .content(tacticsPayload()))
                .andExpect(status().isOk());
    }

    /** The state the prepare job leaves behind: computed, stored, and still not shown. */
    private MatchFixture prepareTodaysFixture() {
        MatchFixture fixture = fixtures.findUnplayedOnDay(1, 1, 3).stream()
                .filter(f -> home.getId().equals(f.getHomeTeam().getId()))
                .findFirst()
                .orElseThrow();

        Match match = new Match();
        match.setHomeTeam(fixture.getHomeTeam());
        match.setAwayTeam(fixture.getAwayTeam());
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setHomeGoals(2);
        match.setAwayGoals(1);
        match.setPlayed(true);
        match = matches.save(match);

        fixture.setPreparedMatchId(match.getId());
        fixture.setPlayed(false);
        return fixtures.save(fixture);
    }

    private String tacticsPayload() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formation", "3-5-2");
        payload.put("style", "ATTACKING");
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
    }

    private String lineupPayload() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formation", "3-5-2");
        payload.put("style", "ATTACKING");
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}
