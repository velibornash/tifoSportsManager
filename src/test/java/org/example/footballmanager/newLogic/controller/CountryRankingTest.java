package org.example.footballmanager.newLogic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Autowired
    CountrySeasonRankingPointsRepository ledger;

    @Autowired
    org.example.footballmanager.newLogic.service.SeasonService seasons;

    private int season() {
        return seasons.getActiveSeasonYear();
    }

    private Country countryByIso(String iso) {
        return countries.findAll().stream()
                .filter(c -> iso.equals(c.getIsoCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no country with ISO " + iso));
    }

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

    /**
     * Equal totals, distinct positions.
     *
     * <p><b>This test used to assert the opposite</b> — "countries on the same rating share a position" —
     * and it was <b>already red before the coin existed</b>, because it fed {@code reputation} into a list
     * that had been rebuilt on ranking points some commits earlier. It was failing for two reasons at once:
     * the numbers it set were not the numbers the list reads, and the rule it asserted is the one the
     * owner has since overruled.
     *
     * <p>The owner's rule is that level totals get <b>distinct</b> positions settled by a coin, never a
     * shared rank and never alphabetical. So the ledger is seeded directly, which is what the list reads.
     */
    @Test
    @Transactional
    @DisplayName("countries level on points get distinct positions, and the country below is behind them")
    void levelCountriesAreSeparatedByTheCoin() throws Exception {
        int season = season();
        // Three countries on exactly the same points, and one behind them.
        country("T11", "Tied one", 1500);
        country("T22", "Tied two", 1500);
        country("T33", "Tied three", 1500);
        country("B44", "Behind", 1400);
        for (String iso : List.of("T11", "T22", "T33")) {
            ledger.save(new CountrySeasonRankingPoints(countryByIso(iso), NationalTeamLevel.SENIOR, season, 200.0));
        }
        ledger.save(new CountrySeasonRankingPoints(countryByIso("B44"), NationalTeamLevel.SENIOR, season, 100.0));

        List<Map<String, Object>> rows = read("senior");
        Map<String, Integer> positionOf = new java.util.HashMap<>();
        rows.forEach(row -> positionOf.put(String.valueOf(row.get("isoCode")), (Integer) row.get("position")));

        assertNotEquals(positionOf.get("T11"), positionOf.get("T22"),
                "two countries on identical points must not share a position");
        assertNotEquals(positionOf.get("T11"), positionOf.get("T33"), "three of them");
        assertEquals(4, positionOf.get("B44"),
                "and the country a hundred points behind is fourth, because the three level ones take "
                        + "positions one, two and three rather than sharing one: " + positionOf);
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

    /**
     * Senior and U-21 are separate ladders, from separate rows.
     *
     * <p>Also written against the old reputation columns and therefore already red before the coin work:
     * the ranking reads {@code CountrySeasonRankingPoints}, not {@code reputation} and
     * {@code youthRating}. Seeded from the ledger instead, which is the thing the list actually shows.
     */
    @Test
    @Transactional
    @DisplayName("senior and U-21 are ranked separately, from separate ledger rows")
    void theTwoLevelsAreRankedSeparately() throws Exception {
        int season = season();
        Country strong = country("SSS", "Senior strong", 1500);
        ledger.save(new CountrySeasonRankingPoints(strong, NationalTeamLevel.SENIOR, season, 300.0));
        ledger.save(new CountrySeasonRankingPoints(strong, NationalTeamLevel.U21, season, -100.0));

        assertEquals(1800.0, pointsFor(read("senior"), "SSS"), 0.01,
                "1500 start + 300 earned on the senior side");
        assertEquals(1400.0, pointsFor(read("u21"), "SSS"), 0.01,
                "and the U-21 list reads its own row, not the senior side's points");
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
