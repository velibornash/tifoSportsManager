package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.GameClock;
import org.example.footballmanager.newLogic.model.SeasonCalendar;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
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
 * The ad board over HTTP (owner, 2026-10-06).
 *
 * <p>Service-level rules already have their own test; this one checks what reaches a client: the posting
 * names its season, week and day on the face of the row, a bot is refused, and a claim creates the request
 * and takes the slot off the board.
 */
@Import(ControllerAuthFixture.class)
class FriendlyOfferControllerTest extends BaseTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private GameClockRepository clocks;
    @Autowired private org.example.footballmanager.newLogic.service.SeasonService seasons;
    @Autowired private ControllerAuthFixture auth;

    private Team human;
    private Team otherHuman;
    private Team bot;


    @BeforeEach
    void setUp() {
        Country country = new Country();
        country.setName("Offer HTTP Republic " + System.nanoTime());
        String tail = Long.toString(Math.abs(System.nanoTime()) % 1296, 36);
        while (tail.length() < 2) {
            tail = "0" + tail;
        }
        country.setIsoCode("H" + tail);
        country.setReputation(1500);
        country = countries.save(country);
        human = club(country, "Human", true);
        otherHuman = club(country, "Other Human", true);
        bot = club(country, "Bot", false);
    }

    private Team club(Country country, String name, boolean humanRun) {
        Team t = new Team();
        t.setName(name + " " + System.nanoTime());
        t.setType(CompetitionTeamType.CLUB);
        t.setCountry(country);
        t.setReputation(60.0);
        t.setHumanControlled(humanRun);
        return teams.save(t);
    }

    @Test
    @Transactional
    @DisplayName("a posting reaches the board with its season, week and day on the face of the row")
    void postingCarriesItsPeriod() throws Exception {
        int season = seasons.getActiveSeasonYear();
        int week = seasons.getCurrentWeek();
        mockMvc.perform(post("/api/season/friendly-offers")
                        .param("teamId", String.valueOf(human.getId()))
                        .param("week", String.valueOf(week))
                        .param("slot", slotFriendlyIn(week))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(season))
                .andExpect(jsonPath("$.week").value(week))
                .andExpect(jsonPath("$.day").exists())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(get("/api/season/friendly-offers")
                        .param("season", String.valueOf(season))
                        .param("week", String.valueOf(week))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[0].teamName").isString())
                .andExpect(jsonPath("$.offers[0].day").exists());
    }

    @Test
    @Transactional
    @DisplayName("a bot-run club is refused, not silently listed")
    void aBotIsRefused() throws Exception {
        int week = seasons.getCurrentWeek();
        mockMvc.perform(post("/api/season/friendly-offers")
                        .param("teamId", String.valueOf(bot.getId()))
                        .param("week", String.valueOf(week))
                        .param("slot", slotFriendlyIn(week))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isConflict());
    }

    @Test
    @Transactional
    @DisplayName("a claim takes the slot off the board and returns FULFILLED")
    void claimTakesTheSlotOffTheBoard() throws Exception {
        int season = seasons.getActiveSeasonYear();
        int week = seasons.getCurrentWeek();
        String body = mockMvc.perform(post("/api/season/friendly-offers")
                        .param("teamId", String.valueOf(human.getId()))
                        .param("week", String.valueOf(week))
                        .param("slot", slotFriendlyIn(week))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number id = (Number) mapper.readValue(body, java.util.Map.class).get("id");

        mockMvc.perform(post("/api/season/friendly-offers/{offerId}/claim", id.longValue())
                        .param("teamId", String.valueOf(otherHuman.getId()))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        mockMvc.perform(get("/api/season/friendly-offers")
                        .param("season", String.valueOf(season))
                        .param("week", String.valueOf(week))
                        .header("Authorization", auth.bearer(UserRole.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers.length()").value(0.0));
    }

    /** The first slot in this week that a club may advertise. */
    private String slotFriendlyIn(int week) {
        for (int slot = 1; slot <= SeasonCalendar.SLOTS_PER_WEEK; slot++) {
            SeasonCalendar.WeekSlot spec = SeasonCalendar.slot(week, slot);
            if (spec != null && spec.friendlyCapable()) {
                return String.valueOf(slot);
            }
        }
        return "1";
    }
}