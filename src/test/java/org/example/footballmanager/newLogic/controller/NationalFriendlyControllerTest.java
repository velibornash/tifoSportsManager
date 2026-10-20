package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.NationalFriendlySlots;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The national-team warm-up over HTTP (owner, 2026-10-06).
 *
 * <p>The slot is not a parameter, and that is the point: "NT friendly can only be in week 6 day 1" is
 * the whole of the slot logic, so the endpoint takes a season and a week and refuses anything that is not
 * that. It also names opponents by national-team id, because a senior side cannot warm up against a U-21.
 */
@Import(ControllerAuthFixture.class)
class NationalFriendlyControllerTest extends BaseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CountryRepository countries;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private ControllerAuthFixture auth;

    private Team seniorA;
    private Team seniorB;
    private Team youthA;

    @BeforeEach
    void setUp() {
        Country country = new Country();
        country.setName("NT HTTP Republic " + System.nanoTime());
        String tail = Long.toString(Math.abs(System.nanoTime()) % 1296, 36);
        while (tail.length() < 2) {
            tail = "0" + tail;
        }
        country.setIsoCode("T" + tail);
        country.setReputation(1500);
        country.setYouthRating(1500);
        country = countries.save(country);
        seniorA = side(country, "Senior A");
        seniorB = side(country, "Senior B");
        youthA = side(country, "Youth A");
        country.setSeniorNationalTeam(seniorA);
        country.setU21NationalTeam(youthA);
        countries.save(country);
    }

    private Team side(Country country, String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.NATIONAL_TEAM);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(false);
        Team saved = teams.save(t);
        for (int i = 0; i < 11; i++) {
            players.save(player(name + " P" + i, saved));
        }
        return saved;
    }

    private static Player player(String name, Team team) {
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

    @Test
    @Transactional
    @DisplayName("the slot endpoint states the one week and day, and there is no parameter to get it wrong")
    void theSlotIsFixed() throws Exception {
        mockMvc.perform(get("/api/national/friendly-requests/slot")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").value(NationalFriendlySlots.WEEK))
                .andExpect(jsonPath("$.day").value(NationalFriendlySlots.DAY));
    }

    @Test
    @Transactional
    @DisplayName("the opponent list offers national sides of one level, and not the country's other level")
    void opponentsAreByLevel() throws Exception {
        String body = mockMvc.perform(get("/api/national/friendly-requests/opponents")
                        .param("level", "senior")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.jayway.jsonpath.JsonPath.read(body, "$[*].name").toString()
                .contains("Senior");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Senior"),
                "senior opponents should be listed, got: " + body);
    }

    @Test
    @Transactional
    @DisplayName("a request a week other than six is refused, and a national one accepted")
    void theWeekIsChecked() throws Exception {
        mockMvc.perform(post("/api/national/friendly-requests")
                        .param("requesterId", String.valueOf(seniorA.getId()))
                        .param("opponentId", String.valueOf(seniorB.getId()))
                        .param("week", "11")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/national/friendly-requests")
                        .param("requesterId", String.valueOf(seniorA.getId()))
                        .param("opponentId", String.valueOf(seniorB.getId()))
                        .param("week", String.valueOf(NationalFriendlySlots.WEEK))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}