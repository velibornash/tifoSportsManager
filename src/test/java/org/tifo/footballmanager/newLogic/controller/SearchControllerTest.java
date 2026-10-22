package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.UniqueIsoCodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.tifo.commonmanager.model.UserRole;
import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.Player;
import org.tifo.footballmanager.newLogic.model.Position;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.PlayerRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Global search (owner, 2026-10-14).
 *
 * <p>*"Search mora biti globalan tool ... niti mora da se koristi samo za prijateljske ... u kojoj mogu
 * da se pretrazuju useri, klubovi ili igraci."*
 *
 * <p>The thing worth protecting here is that it is <b>not</b> the friendly picker. That endpoint answers
 * a narrower question — which club may I ask for a friendly — and it answers it by excluding national
 * sides and by being reachable only from inside a panel. A search excludes neither and is reached from
 * the magnifying glass in the top bar.
 */
@AutoConfigureMockMvc
@Import(ControllerAuthFixture.class)
class SearchControllerTest extends BaseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private ControllerAuthFixture auth;
    @Autowired private PlayerRepository players;

    private Country country;
    private Team findable;

    @BeforeEach
    void setUp() {
        country = new Country();
        country.setName("Search Republic " + System.nanoTime());
        country.setIsoCode(UniqueIsoCodes.next());
        country.setReputation(1500);
        country = countries.save(country);
        findable = club("Zebrafold Athletic");
    }

    private Team club(String name) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.CLUB);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(true);
        return teams.save(t);
    }

    @Test
    @Transactional
    @DisplayName("a search finds a club anywhere in the world and says what it is")
    void findsAClub() throws Exception {
        mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER)).param("q", "Zebrafold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1.0))
                .andExpect(jsonPath("$[0].type").value("TEAM"))
                .andExpect(jsonPath("$[0].name").value(findable.getName()));
    }

    @Test
    @Transactional
    @DisplayName("an empty box returns nothing rather than the whole world")
    void anEmptyQueryIsNotABrowseList() throws Exception {
        mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0.0));
    }

    @Test
    @Transactional
    @DisplayName("the kind can be narrowed, and an absent kind means all of them")
    void canBeNarrowedByKind() throws Exception {
        mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER)).param("q", "Zebrafold").param("type", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0.0));

        mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER)).param("q", "Zebrafold").param("type", "TEAM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1.0));

        mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER)).param("q", "Zebrafold").param("type", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1.0));
    }

    @Test
    @Transactional
    @DisplayName("a national side is searchable here, unlike in the friendly picker")
    void aNationalSideIsSearchable() throws Exception {
        // The friendly picker deliberately hides these: they are not opponents a club asks. A search is
        // a different question and must not quietly inherit that exclusion.
        Team national = club("Zebrafold National");
        national.setType(CompetitionTeamType.NATIONAL_TEAM);
        teams.save(national);

        String body = mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER)).param("q", "Zebrafold"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<?> rows = mapper.readValue(body, List.class);
        boolean found = rows.stream().anyMatch(row -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) row;
            return String.valueOf(map.get("name")).startsWith("Zebrafold National");
        });
        // (the assertion follows)
        assertTrue(found, "a national side must be findable by name in a global search");
    }

    @Test
    @Transactional
    @DisplayName("a player called up to a national side is one result, not one per squad")
    void nationalSquadCopiesAreNotASecondPerson() throws Exception {
        // Owner, 2026-10-14: searching "Bori" returned Borislav Negovanović three times - his club,
        // Serbia and Serbia U-21. National squads are copies (NationalTeamService.addToSquad) so a
        // call-up does not remove him from his club, and each copy carries sourcePlayerId. He is one
        // person and must come back once, with the squads named on the row that survives.
        Team national = club("Zebrafold National");
        national.setType(CompetitionTeamType.NATIONAL_TEAM);
        national = teams.save(national);
        Team u21 = club("Zebrafold U-21");
        u21.setType(CompetitionTeamType.NATIONAL_TEAM);
        u21 = teams.save(u21);

        // The real player, at the club. This is the row that must survive.
        Player real = new Player();
        real.setName("Zebrafoldson Zebrafoldovic");
        real.setTeam(findable);
        real.setPosition(Position.MID);
        real.setAge(24);
        real = players.save(real);

        for (Team nt : List.of(national, u21)) {
            Player copy = new Player();
            copy.setName(real.getName());
            copy.setTeam(nt);
            copy.setPosition(Position.MID);
            copy.setAge(24);
            copy.setSourcePlayerId(real.getId());
            players.save(copy);
        }

        String body = mockMvc.perform(get("/api/search").header("Authorization", auth.bearer(UserRole.OWNER))
                        .param("q", "Zebrafold").param("type", "PLAYER"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<?> rows = mapper.readValue(body, List.class);
        assertEquals(1, rows.size(), "one person must come back once, not once per squad: " + body);

        @SuppressWarnings("unchecked")
        Map<String, Object> only = (Map<String, Object>) rows.get(0);
        assertEquals(real.getId().longValue(), ((Number) only.get("id")).longValue(),
                "the row that survives must be the real club player, not a national copy");
        // De-duplicating must not hide that he is an international.
        assertTrue(String.valueOf(only.get("detail")).contains(national.getName())
                        && String.valueOf(only.get("detail")).contains(u21.getName()),
                "the squads must be named on the surviving row: " + only.get("detail"));
    }
}
