package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.FriendlyRequest;
import org.example.footballmanager.newLogic.model.FriendlyRequest.FriendlyStatus;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.FriendlyRequestRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The friendly panel needs two things that did not exist: a way to choose an opponent, and a request
 * row that says who it is with.
 *
 * <p>The second is the trap. {@link FriendlyRequest} carries two team <b>ids</b> and no names, so
 * rendering a request straight from the entity gives a manager a number and a choice of accept or
 * decline with no way to know who is asking. The controller resolves the names; this pins that, because
 * the symptom of losing it is a list of ids that renders perfectly and is useless.
 *
 * <p>The opponent list exists because {@code GET /teams} caps at 200 rows and the world holds roughly
 * 14,880 clubs: a picker fed from it would offer the first 200 alphabetically and nothing else, which
 * looks like a working search over an empty world.
 */
@Import(ControllerAuthFixture.class)
class FriendlyOpponentAndRequestRowTest extends BaseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private FriendlyRequestRepository requests;
    @Autowired private ControllerAuthFixture auth;

    private Country country;
    private Team club;
    private Team opponent;

    @BeforeEach
    void setUp() {
        country = new Country();
        country.setName("Friendly Republic " + System.nanoTime());
        country.setIsoCode("F" + (System.nanoTime() % 90 + 10));
        country.setReputation(1500);
        country = countries.save(country);
        club = club("Inviting Club");
        opponent = club("Rival Athletic");
    }

    private Team club(String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.CLUB);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(false);
        return teams.save(t);
    }

    private String opponentIds() throws Exception {
        String body = mockMvc.perform(get("/api/season/friendlies/{teamId}/opponents", club.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<?> rows = mapper.readValue(body, List.class);
        StringBuilder ids = new StringBuilder();
        for (Object row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) row;
            ids.append(map.get("id")).append(',');
        }
        return ids.toString();
    }

    @Test
    @Transactional
    @DisplayName("the opponent list offers the other club and never the club itself")
    void opponentsAreScopedAndExcludeSelf() throws Exception {
        String ids = opponentIds();
        assertTrue(ids.contains(String.valueOf(opponent.getId())),
                "the rival club must be offered: " + ids);
        assertFalse(ids.contains(String.valueOf(club.getId())),
                "a club must never be offered as its own opponent: " + ids);
    }

    @Test
    @Transactional
    @DisplayName("a national side is not an opponent a club may ask")
    void nationalTeamsAreNotOffered() throws Exception {
        Team national = new Team();
        national.setName("Some National Team " + System.nanoTime());
        national.setType(CompetitionTeamType.NATIONAL_TEAM);
        national.setCountry(country);
        national.setReputation(1500.0);
        national.setHumanControlled(false);
        teams.save(national);

        String body = mockMvc.perform(get("/api/season/friendlies/{teamId}/opponents", club.getId())
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (Object row : mapper.readValue(body, List.class)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) row;
            String name = String.valueOf(map.get("name"));
            assertFalse(name.startsWith("Some National Team"),
                    "a national side was offered as a friendly opponent; they are picked up by the "
                            + "national-team invitation path, not this one");
        }
    }

    @Test
    @Transactional
    @DisplayName("the list is narrowed by the search term rather than returning the whole country")
    void theSearchNarrowsTheList() throws Exception {
        Team findable = club("Zebrafold United");
        mockMvc.perform(get("/api/season/friendlies/{teamId}/opponents", club.getId())
                        .param("q", "Zebrafold")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1.0))
                .andExpect(jsonPath("$[0].id").value(findable.getId()));
    }

    @Test
    @Transactional
    @DisplayName("a request row carries the other club's name, not an id")
    void requestRowsCarryAName() throws Exception {
        FriendlyRequest request = new FriendlyRequest();
        request.setRequesterTeamId(opponent.getId());
        request.setOpponentTeamId(club.getId());
        request.setSeason(1);
        request.setWeek(11);
        request.setSlot(1);
        request.setStatus(FriendlyStatus.PENDING);
        requests.save(request);

        mockMvc.perform(get("/api/season/friendlies/{teamId}/week", club.getId())
                        .param("season", "1").param("week", "11")
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incoming[0].otherName").isString())
                .andExpect(jsonPath("$.incoming[0].opponentTeamId").value(opponent.getId()))
                .andExpect(jsonPath("$.incoming[0].week").value(11))
                .andExpect(jsonPath("$.incoming[0].status").value("PENDING"));
    }
}