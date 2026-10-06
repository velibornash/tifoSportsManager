package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A country's place in the national ranking (owner, 2026-10-07).
 *
 * <p>The owner asked for "ranking points and a position on the ranking list" on a country's page. That
 * cannot be answered from one country's row — a position is a statement about every other country too —
 * so it is computed where the ratings live, in {@code NationalRatingService}'s two columns:
 * {@code Country.reputation} for the senior side and {@code Country.youthRating} for U-21.
 *
 * <p><b>Equal ratings share a position.</b> That is the assertion most likely to be got wrong by a
 * "sort and number them" implementation, and it is the one that would make a manager think two
 * identically-ranked countries are different.
 */
@Import(ControllerAuthFixture.class)
class CountryRankingTest extends BaseTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ControllerAuthFixture auth;

    @Autowired
    ObjectMapper json;

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    @Test
    @Transactional
    @DisplayName("the ranking is ordered by position and every country appears exactly once")
    void theRankingIsOrderedAndComplete() throws Exception {
        seedWorld();

        List<Map<String, Object>> rows = read("senior");

        assertEquals(5, rows.size(), "every country with a national side is ranked");
        int previous = 0;
        for (Map<String, Object> row : rows) {
            int position = (Integer) row.get("position");
            assertTrue(position >= previous, "positions do not go backwards: " + rows);
            previous = position;
            assertTrue(row.containsKey("points"), "a position without points is not a ranking: " + row);
            assertTrue(row.containsKey("rated"), "and says whether the points mean anything yet: " + row);
        }
    }

    @Test
    @Transactional
    @DisplayName("countries on the same rating share a position")
    void equalRatingsShareAPosition() throws Exception {
        // Three countries on exactly the same senior rating, and one behind them.
        country("T11", "Tied one", 1500);
        country("T22", "Tied two", 1500);
        country("T33", "Tied three", 1500);
        country("B44", "Behind", 1400);

        List<Map<String, Object>> rows = read("senior");

        Map<String, Integer> positionOf = new java.util.HashMap<>();
        rows.forEach(row -> positionOf.put(String.valueOf(row.get("isoCode")), (Integer) row.get("position")));

        assertEquals(positionOf.get("T11"), positionOf.get("T22"),
                "two countries with the same rating are the same distance from the top");
        assertEquals(positionOf.get("T11"), positionOf.get("T33"), "three of them");
        assertEquals(1, positionOf.get("T11"), "and nobody is above anyone here");
        assertEquals(4, positionOf.get("B44"), "while the country below them is behind all three");
    }

    @Test
    @Transactional
    @DisplayName("a country that has not played says so, rather than showing a seed rating as a result")
    void anUnplayedCountrySaysItIsUnrated() throws Exception {
        country("XXX", "Unplayed", 1500);

        List<Map<String, Object>> rows = read("senior");
        Map<String, Object> mine = rows.stream()
                .filter(row -> "XXX".equals(row.get("isoCode"))).findFirst().orElseThrow();

        assertEquals(false, mine.get("rated"),
                "1500 is the starting rating, not a result. Shown without saying so it reads as 'exactly "
                        + "average', which nobody has earned.");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 are ranked separately, from two different columns")
    void theTwoLevelsAreRankedSeparately() throws Exception {
        Country seniorStrong = country("SSS", "Senior strong", 1800);
        seniorStrong.setYouthRating(1200);
        countries.save(seniorStrong);

        assertEquals(1800.0, pointsFor(read("senior"), "SSS"), 0.01);
        assertEquals(1200.0, pointsFor(read("u21"), "SSS"), 0.01,
                "the U-21 rating is read from youthRating, not from the senior reputation");
    }

    @Test
    @Transactional
    @DisplayName("a country with no national side is not ranked at all")
    void aCountryWithNoSideIsNotRanked() throws Exception {
        seedWorld();
        Country bare = new Country();
        bare.setName("No sides");
        bare.setIsoCode("NOB");
        bare.setReputation(1500);
        bare.setState(CountryState.SIMULATED);
        countries.save(bare);

        assertFalse(read("senior").stream().anyMatch(row -> "NOB".equals(row.get("isoCode"))),
                "a country with no senior side has no senior ranking. Listing it would show a position "
                        + "for something that cannot play.");
    }

    // ---------- reading ----------

    private double pointsFor(List<Map<String, Object>> rows, String iso) {
        return rows.stream().filter(row -> iso.equals(row.get("isoCode")))
                .map(row -> ((Number) row.get("points")).doubleValue()).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> read(String level) throws Exception {
        String body = mockMvc.perform(get("/countries/ranking?level=" + level)
                        .header("Authorization", auth.bearer(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, List.class);
    }

    private void seedWorld() {
        country("AAA", "Alpha", 1700);
        country("BBB", "Bravo", 1600);
        country("CCC", "Charlie", 1500);
        country("DDD", "Delta", 1500);
        country("EEE", "Echo", 1400);
    }

    private Country country(String iso, String name, int rating) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(iso);
        country.setReputation(rating);
        country.setYouthRating(rating);
        country.setState(CountryState.SIMULATED);
        Country saved = countries.save(country);

        Team senior = new Team();
        senior.setName(name + " senior");
        senior.setType(CompetitionTeamType.NATIONAL_TEAM);
        senior.setCountry(saved);
        saved.setSeniorNationalTeam(teams.save(senior));
        return countries.save(saved);
    }
}
