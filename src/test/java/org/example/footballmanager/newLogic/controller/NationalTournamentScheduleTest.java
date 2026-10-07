package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.NationalTournamentSchedule;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.util.NationalTeamCompetitions;
import org.example.footballmanager.newLogic.util.NationalTournamentSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A drawn qualifying competition returns the schedule, and every name on it can be followed (owner,
 * 2026-10-07).
 *
 * <p><b>The schedule was never sent.</b> The owner's question was "where are the matches when the draw
 * comes out?" and the answer was: nowhere. {@code roundsOf} skips every fixture carrying a group code,
 * which is right for a knockout and meant a qualifying competition — whose every fixture carries one —
 * returned <b>no fixtures at all</b>. Eight groups rendered on screen with nothing behind them.
 *
 * <p>And nothing was followable: a group had no schedule, and a team name was text. A qualifying table
 * is six countries; a manager asking who is in his group should be able to click one.
 */
@Import(ControllerAuthFixture.class)
class NationalTournamentScheduleTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Autowired
    NationalTeamCompetitions catalogue;

    @Autowired
    NationalTournamentSeeder seeder;

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    @Autowired
    PlayerRepository players;

    @Autowired
    MatchFixtureRepository fixtures;

    private static final int SEASON = 1;

    @BeforeEach
    @Transactional
    void aDrawnQualifyingCompetition() {
        for (int i = 0; i < 48; i++) {
            Country country = new Country();
            country.setName(String.format("Nation %02d", i));
            country.setIsoCode(String.format("N%02d", i));
            country.setReputation(1000 + i);
            country.setYouthRating(1000 + i);
            country.setState(CountryState.SIMULATED);
            Country saved = countries.save(country);
            saved.setSeniorNationalTeam(side(saved, "Senior"));
            // A U-21 side per nation as well. Without one the U-21 seeder has nothing to draw from and
            // the U-21 schedule comes back empty - which is what the first version of the U-21 test
            // reported as "the field is drawn differently", when the real answer was that the fixture
            // had built no U-21 teams to draw.
            saved.setU21NationalTeam(side(saved, "U21"));
            countries.save(saved);
        }
        catalogue.ensureAll();
        seeder.ensureGroupStage(NationalTeamLevel.SENIOR, SEASON);
    }

    /**
     * The U-21 is opened by the same card as the senior side, so this proves it rather than assuming it.
     *
     * <p>{@code national-tournament-view.js} serves all four competitions — senior and U-21, qualifying
     * and finals — from one renderer and one payload builder, so {@code tie(MatchFixture)} is level
     * agnostic and the senior fix ({@code P0-PREV-3}) should already cover the U-21. "Should already" is
     * exactly the kind of claim that is true until a level filter is added somewhere, so the U-21 schedule
     * is fetched here and required to carry the same fields a senior tie does.
     *
     * <p>{@code matchId} is the one that matters: every post-match endpoint is keyed by it, so a U-21 tie
     * without it would open with lineups, stats, goals and the report all empty.
     */
    @Test
    @Transactional
    @DisplayName("a U-21 tie carries the same opening fields a senior tie does")
    void aU21TieCarriesTheOpeningFields() throws Exception {
        seeder.ensureGroupStage(NationalTeamLevel.U21, SEASON);

        Map<String, Object> body = readFor("u21", "QUALIFYING");
        List<Map<String, Object>> groups = groupsOf(body);
        assertEquals(NationalTournamentSchedule.GROUPS, groups.size(),
                "the U-21 field is drawn the same way the senior one is");

        List<Map<String, Object>> ties = new java.util.ArrayList<>();
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> day : castList(group.get("fixtures"))) {
                ties.addAll(castList(day.get("fixtures")));
            }
        }
        assertFalse(ties.isEmpty(), "the U-21 schedule has ties in it");

        Map<String, Object> tie = ties.get(0);
        assertNotNull(tie.get("id"), "the fixture id, so an unplayed tie can be opened");
        assertTrue(tie.containsKey("matchId"),
                "a U-21 tie must carry matchId like a senior one. It does not, so a played U-21 tie would "
                        + "open with lineups, stats, goals and the report all empty.");
        assertTrue(tie.containsKey("played"));
        assertTrue(tie.containsKey("homeIso") && tie.containsKey("awayIso"),
                "and the country codes, so the names are still links to those countries");
    }

    @Test
    @Transactional
    @DisplayName("every group carries its whole qualifying schedule, five matchdays a day")
    void everyGroupCarriesItsSchedule() throws Exception {
        Map<String, Object> body = readQualifying();

        List<Map<String, Object>> groups = groupsOf(body);
        assertEquals(NationalTournamentSchedule.GROUPS, groups.size(), "eight groups");

        groups.forEach(group -> {
            String code = String.valueOf(group.get("code"));
            List<Map<String, Object>> matchdays = castList(group.get("fixtures"));
            assertEquals(5, matchdays.size(),
                    "group " + code + " has five matchdays. A group whose schedule is empty is the bug "
                            + "this test exists for.");

            int round = 1;
            for (Map<String, Object> day : matchdays) {
                assertEquals(round, day.get("round"), "matchdays are in order");
                assertEquals(NationalTournamentSchedule.qualifyingDay(round), day.get("day"),
                        "and each is on the day the owner's calendar gives it");
                assertEquals(3, castList(day.get("fixtures")).size(),
                        "group " + code + " matchday " + round + " is three ties");
                round++;
            }
        });
    }

    @Test
    @Transactional
    @DisplayName("every tie in the schedule names both sides and carries both country codes")
    void everyTieNamesBothSidesAndBothCountries() throws Exception {
        Map<String, Object> body = readQualifying();

        int ties = 0;
        for (Map<String, Object> group : groupsOf(body)) {
            for (Map<String, Object> day : castList(group.get("fixtures"))) {
                for (Map<String, Object> tie : castList(day.get("fixtures"))) {
                    ties++;
                    assertNotNull(tie.get("homeName"), "a tie with no home side");
                    assertNotNull(tie.get("awayName"), "a tie with no away side");
                    assertTrue(String.valueOf(tie.get("homeIso")).length() == 3,
                            "a tie whose home side has no country cannot be linked to: " + tie);
                    assertTrue(String.valueOf(tie.get("awayIso")).length() == 3,
                            "a tie whose away side has no country cannot be linked to: " + tie);
                }
            }
        }
        assertEquals(120, ties, "8 groups of 6, 15 ties each, counted through the API");
    }

    @Test
    @Transactional
    @DisplayName("the standings table carries a country code per row, so a name can be followed")
    void theTableCarriesCountryCodes() throws Exception {
        Map<String, Object> body = readQualifying();

        List<Map<String, Object>> table = castList(groupsOf(body).get(0).get("table"));
        assertEquals(6, table.size(), "a group of six");
        for (Map<String, Object> row : table) {
            assertTrue(String.valueOf(row.get("countryIso")).length() == 3,
                    "a standings row with no country cannot be a link: " + row);
        }
    }

    @Test
    @Transactional
    @DisplayName("a competition that was never drawn says so, and does not claim an empty schedule")
    void anUndrawnCompetitionSaysSo() throws Exception {
        String body = mockMvc.perform(get("/api/national-tournaments/u21/WORLD_CUP")
                        .header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> payload = json.readValue(body, Map.class);
        assertEquals(false, payload.get("exists"),
                "the U-21 World Cup row exists - ensureAll creates all four the moment any one is drawn - "
                        + "but nothing has been drawn into it. 'exists' must mean drawn, or the World page "
                        + "offers three empty competitions it calls real.");
        assertTrue(castList(payload.get("groups")).isEmpty(), "and no groups are invented");
        assertTrue(String.valueOf(payload.get("note")).contains("created"),
                "and it says the row exists without pretending there is a tournament in it: " + payload.get("note"));
    }

    // ---------- reading ----------

    @SuppressWarnings("unchecked")
    /** The same read, for any level and stage, so the U-21 can be checked on its own terms. */
    private Map<String, Object> readFor(String level, String stage) throws Exception {
        String body = mockMvc.perform(get("/api/national-tournaments/" + level + "/" + stage
                        + "?season=" + SEASON)
                        .header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    private Map<String, Object> readQualifying() throws Exception {
        String body = mockMvc.perform(get("/api/national-tournaments/senior/QUALIFYING?season=" + SEASON)
                        .header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> groupsOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("groups");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Team side(Country country, String label) {
        Team team = new Team();
        team.setName(country.getName() + " " + label);
        team.setType(CompetitionTeamType.NATIONAL_TEAM);
        team.setCountry(country);
        team.setHumanControlled(false);
        Team saved = teams.save(team);
        for (int p = 1; p <= 25; p++) {
            players.save(player(label + " " + p, saved));
        }
        return saved;
    }

    private Player player(String name, Team team) {
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 12);
        skills.setSkill(SkillName.STAMINA, 12);
        skills.setSkill(SkillName.GOALKEEPER, 12);
        skills.setSkill(SkillName.DEFENDER, 12);
        skills.setSkill(SkillName.TECHNIQUE, 12);
        skills.setSkill(SkillName.PLAYMAKER, 12);
        skills.setSkill(SkillName.PASSING, 12);
        skills.setSkill(SkillName.STRIKER, 12);
        skills.initializeExactFromVisibleIfNeeded();
        Player p = new Player();
        p.setName(name);
        p.setPosition(Position.MID);
        p.setSkills(skills);
        p.setAge(21);
        p.setTeam(team);
        p.setRating(p.careerRating());
        return p;
    }

    /** The competition the test drew, so a reader can see the fixtures really exist. */
    @Test
    @Transactional
    @DisplayName("the schedule read here is the schedule that was actually drawn")
    void theScheduleMatchesTheFixtures() {
        Competition qualifying = catalogue.qualifiers(NationalTeamLevel.SENIOR).orElseThrow();
        long drawn = fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(qualifying.getId(), SEASON)
                .stream()
                .filter(f -> f.getGroupCode() != null)
                .count();
        assertEquals(120, drawn, "8 groups of 6 play 15 ties each: 8 x 15 = 120");
    }
}
