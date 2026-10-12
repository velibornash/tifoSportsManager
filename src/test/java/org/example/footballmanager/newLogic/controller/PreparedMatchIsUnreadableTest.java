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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A prepared match is unreadable through every endpoint that takes a match id (owner, 2026-10-11).
 *
 * <p><b>This is the leak, stated as a test.</b> The matchday is computed an hour before kickoff and
 * stored, so from that moment the row exists in the {@code match} table with a real id. Eight endpoints
 * in {@code MatchController} and three in {@code ZoxReplayController} resolve a match id against that
 * table, and the fixture list — the only place the state was visible — stopped nobody who simply had
 * the id.
 *
 * <p>So each endpoint is asked directly, over HTTP, with the id of a genuinely prepared match, and
 * must answer 404. Not 403, and not an empty result: a prepared match has to be indistinguishable from
 * one that does not exist, because an id space this small is enumerable and "it exists but not yet" is
 * already an answer.
 *
 * <p>The replay endpoints are here because the replay is the richest thing to leak — every tick of the
 * match — and the store is populated at simulation time.
 */
@Import(ControllerAuthFixture.class)
class PreparedMatchIsUnreadableTest extends BaseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionRepository competitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private MatchRepository matches;
    @Autowired private ControllerAuthFixture auth;
    @Autowired private org.example.footballmanager.newLogic.store.MatchStore matchStore;

    private Long preparedMatchId;

    @BeforeEach
    void aMatchThatHasBeenComputedButNotPublished() {
        Competition competition = new Competition();
        competition.setName("ZZ Hidden " + UUID.randomUUID());
        competition.setType(CompetitionType.LEAGUE);
        competition.setTier(1);
        competition.setTeamType(CompetitionTeamType.CLUB);
        competition = competitions.save(competition);

        MatchFixture fixture = new MatchFixture();
        fixture.setCompetition(competition);
        fixture.setHomeTeam(aTeam("ZZ Hidden Home " + UUID.randomUUID()));
        fixture.setAwayTeam(aTeam("ZZ Hidden Away " + UUID.randomUUID()));
        fixture.setSeasonYear(1);
        fixture.setWeekNumber(1);
        fixture.setDayNumber(3);
        fixture.setPlayed(false);
        fixture = fixtures.save(fixture);

        Match match = new Match();
        match.setHomeTeam(fixture.getHomeTeam());
        match.setAwayTeam(fixture.getAwayTeam());
        match.setSeasonYear(1);
        match.setWeekNumber(1);
        match.setDayNumber(3);
        match.setHomeGoals(4);
        match.setAwayGoals(0);
        match.setPlayed(true);
        match = matches.save(match);

        // Exactly the state MatchdayScheduler.prepareUnplayedOn leaves behind.
        fixture.setPreparedMatchId(match.getId());
        fixture.setPlayed(false);
        fixtures.save(fixture);
        preparedMatchId = match.getId();
    }

    @Test
    @DisplayName("the match, its lineups, events, stats, preview and report are all 404")
    void noMatchEndpointServesAPreparedMatch() throws Exception {
        long id = preparedMatchId;

        for (String path : new String[]{
                "/matches/" + id,
                "/matches/" + id + "/lineups",
                "/matches/" + id + "/detail",
                "/matches/" + id + "/stats",
                "/matches/" + id + "/player-stats",
                "/matches/" + id + "/preview",
                "/matches/" + id + "/report"}) {
            mockMvc.perform(get(path).header("Authorization", auth.bearer(UserRole.OWNER)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("the replay is 404 too - the store already holds it an hour early")
    void noReplayEndpointServesAPreparedMatch() throws Exception {
        long id = preparedMatchId;

        // The replay store is in memory and is empty in a test, so the first version of this test
        // asserted 404 against a match nobody could serve and passed with the guard switched off.
        // The store is populated here so that 404 can only come from the guard.
        org.example.footballmanager.newLogic.model.Match dbMatch = matches.findById(id).orElseThrow();
        // Its own object, because MatchStore.createMatch overwrites the id it is given - handing it
        // the managed entity renumbers the row in memory and the replay link then points at nothing.
        org.example.footballmanager.newLogic.model.Match storeMatch =
                new org.example.footballmanager.newLogic.model.Match();
        storeMatch.setHomeTeam(dbMatch.getHomeTeam());
        storeMatch.setAwayTeam(dbMatch.getAwayTeam());
        long storeId = matchStore.createMatch(storeMatch);
        dbMatch.setReplayId(storeId);
        matches.save(dbMatch);
        matchStore.storeResult(storeId, aResult(storeId));

        for (String path : new String[]{
                "/api/zox/replay/" + id + "/metadata",
                "/api/zox/replay/" + id + "/chunks",
                "/api/zox/replay/" + id + "/chunks/0"}) {
            mockMvc.perform(get(path).header("Authorization", auth.bearer(UserRole.OWNER)))
                    .andExpect(status().isNotFound());
        }

        // And the control, which is the part that gives the three 404s above their meaning: the very
        // same store entry, reachable the moment the fixture is published. Without this line the loop
        // would also pass if the replay endpoint were simply broken — which it was, for a while.
        fixtures.findAll().stream()
                .filter(f -> preparedMatchId.equals(f.getPreparedMatchId()))
                .findFirst()
                .ifPresent(f -> {
                    f.setPlayed(true);
                    f.setPlayedMatch(matches.findById(id).orElseThrow());
                    fixtures.save(f);
                });

        // /chunks rather than /metadata: the chunk builder reads only the MatchResult this test owns,
        // while metadata reads a dozen fields off the store's Match that a fixture this size does not
        // have. The control has to isolate the guard, not become a second fixture-building exercise.
        mockMvc.perform(get("/api/zox/replay/" + id + "/chunks")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the fixture itself is still readable, and still says it has not happened")
    void theFixtureRemainsTheOnlyHonestView() throws Exception {
        MatchFixture fixture = fixtures.findAll().stream()
                .filter(f -> preparedMatchId.equals(f.getPreparedMatchId()))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(get("/matches/by-fixture/" + fixture.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk());
    }

    /** A minimal but real replay result, so the endpoint has something to serve if it is allowed to. */
    private org.example.footballmanager.newLogic.model.MatchResult aResult(long storeId) {
        return new org.example.footballmanager.newLogic.model.MatchResult(
                storeId, 4, 0,
                java.util.List.<org.example.footballmanager.newLogic.model.event.MatchEvent>of(),
                java.util.List.<org.example.footballmanager.newLogic.model.TickSnapshot>of(),
                90, 10,
                // shots, on target, fouls, corners, yellow, red - then the two averages
                55.0, 45.0, 12, 4, 5, 2, 9, 7, 4, 3, 1, 0, 2, 0, 7.1, 6.4,
                400, 350, 380, 320, "4-4-2", "4-4-2");
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }
}