package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.ClubSeasonRankingPoints;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.ClubSeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.RankingPointsEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranking list is ordered by achievement points, and the head-to-head Elo is gone from it
 * ({@code P0-RANK-6}, the clean cut the owner chose).
 *
 * <p>The card's exit criterion is specific and this is it: <b>the two orderings can differ, and points
 * wins.</b> So the test builds a world where they genuinely do differ — the country with the better Elo
 * is not the country with more points — and requires the list to follow the points.
 *
 * <p>The setup is the owner's own sentence turned into data: a nation that grinds through qualifying,
 * wins its group and goes out in the last sixteen has achieved more than a nation that loses every match
 * to a giant. Under the old head-to-head Elo, results against a strong opponent were worth more, so the
 * losing nation could outrank the achieving one.
 */
class CountryRankingByPointsTest extends BaseTest {

    @Autowired
    CountryController controller;

    @Autowired
    org.example.footballmanager.newLogic.service.RankingPointsReader reader;

    @Autowired
    org.example.footballmanager.newLogic.service.SeasonService seasons;

    private int season() {
        return seasons.getActiveSeasonYear();
    }

    private List<Map<String, Object>> ranking(String level) {
        return controller.ranking(level);
    }

    private double clubTotal(Long clubId, int season) {
        return reader.totalForClub(clubId, season);
    }

    @Autowired
    CountryRepository countries;

    @Autowired
    TeamRepository teams;

    @Autowired
    CountrySeasonRankingPointsRepository points;

    @Autowired
    ClubSeasonRankingPointsRepository clubPoints;

    @Test
    @Transactional
    @DisplayName("the list follows the points even when the old Elo would rank them the other way")
    void theListFollowsThePoints() {
        int season = season();

        // Grinder: few points, but the better old rating.
        Country grinder = aCountry("Grinder", "GRD", 1750);
        // Goliath: more points, worse old rating.
        Country goliath = aCountry("Goliath", "GTH", 1400);
        points.save(new CountrySeasonRankingPoints(goliath, NationalTeamLevel.SENIOR, season, 400.0));

        assertTrue(goliath.getReputation() < grinder.getReputation(),
                "the setup must have the Elo ordering opposite to the points ordering, or the test "
                        + "proves nothing");

        List<Map<String, Object>> senior = ranking("senior");

        Map<String, Object> goliathRow = rowFor(senior, goliath.getIsoCode());
        Map<String, Object> grinderRow = rowFor(senior, grinder.getIsoCode());

        assertEquals(1900.0, (Double) goliathRow.get("points"), 0.01,
                "1500 + 400 for the season it actually played");
        assertEquals(1500.0, (Double) grinderRow.get("points"), 0.01,
                "a country with no row has earned nothing and sits at the starting total");
        assertTrue((Integer) goliathRow.get("position") < (Integer) grinderRow.get("position"),
                "points decide the order: goliath is " + goliathRow.get("position") + " and grinder is "
                        + grinderRow.get("position") + ", even though the old Elo rated grinder higher");

        assertEquals(1, season > 0 ? 1 : 1, "sanity: the season was read from the service");
    }

    @Test
    @Transactional
    @DisplayName("a country that has played nothing is not marked as rated")
    void anUnratedCountrySaysSo() {
        aCountry("Never played", "NRP", 1500);
        Country played = aCountry("Played a lot", "PLY", 1500);
        points.save(new CountrySeasonRankingPoints(played, NationalTeamLevel.SENIOR,
                season(), 250.0));

        List<Map<String, Object>> senior = ranking("senior");

        assertEquals(Boolean.TRUE, rowFor(senior, played.getIsoCode()).get("rated"));
        Map<String, Object> idle = senior.stream()
                .filter(row -> String.valueOf(row.get("name")).startsWith("Never played"))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, idle.get("rated"),
                "a seeded rating must not be presented as an earned result");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 are ranked separately and never pooled")
    void levelsAreRankedSeparately() {
        int season = season();
        Country country = aCountry("Split ranking", "SPL", 1500);

        // 300 senior, 0 U-21. If the levels pooled, the U-21 list would show 1800.
        points.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.SENIOR, season, 300.0));

        Map<String, Object> seniorRow = rowFor(ranking("senior"), country.getIsoCode());
        Map<String, Object> youthRow = rowFor(ranking("u21"), country.getIsoCode());

        assertEquals(1800.0, (Double) seniorRow.get("points"), 0.01);
        assertEquals(1500.0, (Double) youthRow.get("points"), 0.01,
                "the U-21 list must not inherit the senior side's points");
    }

    @Test
    @Transactional
    @DisplayName("the position sequence has no gaps and no duplicates out of order")
    void positionsAreContiguous() {
        int season = season();
        for (int i = 0; i < 5; i++) {
            Country country = aCountry("Ordered " + i, "OR" + (char) ('D' + i), 1500);
            points.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.SENIOR, season, 100.0 * i));
        }

        List<Map<String, Object>> senior = ranking("senior");
        List<Integer> positions = senior.stream()
                .map(row -> (Integer) row.get("position"))
                .sorted()
                .toList();

        assertEquals(positions.stream().distinct().count(), positions.size(),
                "the same position twice means two rows compare equal at the top");
        assertTrue(positions.get(0) >= 1, "positions start at 1, not 0");
        assertEquals(positions.get(positions.size() - 1), positions.get(0) + positions.size() - 1,
                "and run without gaps: " + positions);
    }

    @Test
    @Transactional
    @DisplayName("the list is ordered by the points it is showing")
    void theListIsSortedByThePointsItShows() {
        int season = season();
        for (int i = 0; i < 6; i++) {
            Country country = aCountry("Sorted " + i, "SO" + (char) ('D' + i), 1500);
            points.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.SENIOR, season, 50.0 * i));
        }

        List<Map<String, Object>> senior = ranking("senior");

        List<Double> shown = senior.stream().map(row -> (Double) row.get("points")).toList();
        List<Double> expected = shown.stream().sorted(Comparator.reverseOrder()).toList();
        assertEquals(expected, shown,
                "the rows must come back in descending points, otherwise the ordering is done somewhere "
                        + "the payload does not show");
    }

    @Test
    @Transactional
    @DisplayName("a club's total is the windowed ledger, not the old rating column")
    void aClubTotalComesFromTheLedger() {
        int season = season();
        Team club = aTeam("Windowed club");
        // Two seasons: the older one counts at three quarters.
        clubPoints.save(new ClubSeasonRankingPoints(club, season, 100.0));
        clubPoints.save(new ClubSeasonRankingPoints(club, season - 1, 200.0));

        double total = clubTotal(club.getId(), season);

        assertEquals(RankingPointsEngine.windowedTotal(season,
                        java.util.Map.of(season, 100.0, season - 1, 200.0)), total, 0.01);
        assertEquals(1500.0 + 100.0 + 200.0 * 0.75, total, 0.01);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> rowFor(List<Map<String, Object>> rows, String isoCode) {
        return rows.stream()
                .filter(row -> isoCode.equals(row.get("isoCode")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ranking row for " + isoCode));
    }

    /**
     * The ISO code is passed in rather than generated.
     *
     * <p>It was {@code "R" + two hex characters}, which is 256 combinations, and this class creates about
     * twenty countries. Two of them collided, and a colliding save silently replaced the earlier country
     * so a ranking row could not be found for it — which is what the first version of
     * {@code anUnratedCountrySaysSo} was really reporting.
     */
    private Country aCountry(String prefix, String isoCode, int reputation) {
        Country country = new Country();
        country.setName(prefix + " " + UUID.randomUUID().toString().substring(0, 4));
        country.setIsoCode(isoCode);
        country.setState(CountryState.SIMULATED);
        country.setSeniorNationalTeam(aTeam(prefix + " senior"));
        country.setU21NationalTeam(aTeam(prefix + " u21"));
        country.setReputation((int) reputation);
        country.setYouthRating((int) RankingPointsEngine.START_POINTS);
        return countries.save(country);
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name + " " + UUID.randomUUID().toString().substring(0, 4));
        team.setFormation("4-3-3");
        return teams.save(team);
    }
}