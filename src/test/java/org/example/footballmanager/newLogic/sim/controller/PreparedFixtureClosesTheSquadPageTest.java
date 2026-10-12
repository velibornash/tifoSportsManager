package org.example.footballmanager.newLogic.sim.controller;

import org.example.footballmanager.BaseTest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The squad page closes with the match (owner, 2026-10-11).
 *
 * <p>The tactic editor was locked and the squad page was not, because its {@code editable} flag was
 * {@code !fixture.isPlayed()} — and a **prepared fixture is not played**. The hour before kickoff is
 * precisely the hour in which that flag is wrong, so the one page where a manager picks his eleven was
 * the one page still accepting changes.
 *
 * <p>The screen already honoured {@code editable}, so the flag was the whole bug. The reason is in the
 * payload too, because a squad page that says "Closed" with no reason reads as a bug rather than as the
 * game's own rule.
 */
@Import(org.example.footballmanager.newLogic.controller.ControllerAuthFixture.class)
class PreparedFixtureClosesTheSquadPageTest extends BaseTest {

    @Autowired private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired private org.example.footballmanager.newLogic.controller.ControllerAuthFixture auth;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchRepository matches;
    @Autowired private org.example.footballmanager.newLogic.repository.PlayerRepository players;

    private Team home;
    private MatchFixture fixture;

    @BeforeEach
    void aMatchOnDayThree() {
        Competition competition = new Competition();
        competition.setName("ZZ Squad " + java.util.UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        home = aTeam("ZZ Squad Home " + java.util.UUID.randomUUID());

        fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(home);
        fixture.setAwayTeam(aTeam("ZZ Squad Away " + java.util.UUID.randomUUID()));
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        fixture = fixtures.save(fixture);
    }

    @Test
    @DisplayName("an undecided match is editable")
    void openBeforeTheMatchIsDecided() throws Exception {
        mockMvc.perform(get("/api/sim/fixtures/{id}/lineup", fixture.getId())
                        .header("Authorization", auth.bearerManaging(
                                org.example.commonmanager.model.UserRole.OWNER, home)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @DisplayName("a computed match is not editable, and says why")
    void closedOnceTheMatchIsComputed() throws Exception {
        prepare();

        mockMvc.perform(get("/api/sim/fixtures/{id}/lineup", fixture.getId())
                        .header("Authorization", auth.bearerManaging(
                                org.example.commonmanager.model.UserRole.OWNER, home)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.lockReason").isNotEmpty())
                .andExpect(jsonPath("$.lockedAt").isNotEmpty());
    }

    @Test
    @DisplayName("saving the eleven after the match is computed is refused")
    void savingIsRefusedOnceTheMatchIsComputed() throws Exception {
        prepare();

        mockMvc.perform(put("/api/sim/fixtures/{id}/lineup", fixture.getId())
                        .header("Authorization", auth.bearerManaging(
                                org.example.commonmanager.model.UserRole.OWNER, home))
                        .contentType("application/json")
                        .content("{\"formation\":\"4-4-2\",\"starters\":[],\"bench\":[]}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("MATCH_CLOSED"));
    }

    @Test
    @DisplayName("writing the orders after the match is computed is refused, and says the real reason")
    void ordersAreRefusedOnceTheMatchIsComputed() throws Exception {
        // The orders page had a time cutoff and knew nothing about the prepare lock, so through the
        // hour the owner closed it said "open for another 45 minutes" about a match decided an hour
        // ago - the right arithmetic for the wrong question.
        fixture.setMatchDate(java.time.LocalDateTime.now().plusHours(3));
        fixtures.save(fixture);
        prepare();

        mockMvc.perform(put("/api/sim/fixtures/{id}/substitution-plan", fixture.getId())
                        .header("Authorization", auth.bearer(
                                org.example.commonmanager.model.UserRole.OWNER))
                        .contentType("application/json")
                        .content("{\"rules\":[]}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("PLAN_CLOSED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already been decided")));
    }

    @Test
    @DisplayName("orders stay writable while the match is genuinely still open")
    void ordersAreWritableBeforeTheMatchIsDecided() throws Exception {
        // The other half: a lock that refuses everything is not a lock. Three hours out with nothing
        // computed, a manager may still write his orders.
        fixture.setMatchDate(java.time.LocalDateTime.now().plusHours(3));
        fixtures.save(fixture);

        mockMvc.perform(put("/api/sim/fixtures/{id}/substitution-plan", fixture.getId())
                        .header("Authorization", auth.bearer(
                                org.example.commonmanager.model.UserRole.OWNER))
                        .contentType("application/json")
                        .content("{\"rules\":[]}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("the suggestion fills eleven, names the short bench, and warns about nobody")
    void theSuggestionIsAnAnswerNotAShrug() throws Exception {
        // OFK Omladinac has thirteen players and that is deliberate, so thirteen must produce a complete
        // answer: eleven out, two on the bench, and no complaint - nothing has gone wrong.
        for (int i = 0; i < 13; i++) {
            addPlayer(i);
        }

        mockMvc.perform(get("/api/sim/fixtures/{id}/lineup/suggest", fixture.getId())
                        .header("Authorization", auth.bearerManaging(org.example.commonmanager.model.UserRole.OWNER, home)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggested").value(true))
                .andExpect(jsonPath("$.starters.length()").value(11))
                .andExpect(jsonPath("$.bench.length()").value(2))
                .andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.starters[0].name").isNotEmpty())
                .andExpect(jsonPath("$.starters[0].position").isNotEmpty());
    }

    @Test
    @DisplayName("a computed match refuses even a suggestion")
    void aPreparedFixtureRefusesTheSuggestion() throws Exception {
        // Suggesting a lineup for a match that has already been decided is worse than saying nothing: it
        // looks like an invitation.
        prepare();

        mockMvc.perform(get("/api/sim/fixtures/{id}/lineup/suggest", fixture.getId())
                        .header("Authorization", auth.bearerManaging(org.example.commonmanager.model.UserRole.OWNER, home)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("MATCH_CLOSED"));
    }

    private void addPlayer(int i) {
        org.example.footballmanager.newLogic.model.Player player =
                new org.example.footballmanager.newLogic.model.Player();
        player.setName("ZZ Squad " + i + " " + java.util.UUID.randomUUID());
        player.setTeam(home);
        player.setPosition(i == 0
                ? org.example.footballmanager.newLogic.model.Position.GK
                : (i < 5 ? org.example.footballmanager.newLogic.model.Position.DEF
                        : (i < 9 ? org.example.footballmanager.newLogic.model.Position.MID
                                : org.example.footballmanager.newLogic.model.Position.ATT)));
        player.setRating(50 + i);
        player.setAge(24);
        player.setForm(6.5);
        player.setMorale(60.0);
        players.save(player);
    }

    private void prepare() {
        Match match = new Match();
        match.setHomeTeam(fixture.getHomeTeam());
        match.setAwayTeam(fixture.getAwayTeam());
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setPlayed(true);
        match = matches.save(match);

        fixture.setPreparedMatchId(match.getId());
        // The real prepare path stamps this, and the squad page shows it so the manager can see when
        // his match was closed rather than only that it is.
        fixture.setPreparedAt(java.time.LocalDateTime.now());
        fixture.setPlayed(false);
        fixtures.save(fixture);
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}