package org.example.footballmanager.newLogic.service;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-season ledger round-trips (P0-RANK-1).
 *
 * <p>The rolling window cannot be computed until these rows exist and read back correctly, and a mapping
 * that merely compiles is not evidence of either. Three things this pins that a compile cannot:
 *
 * <ul>
 *   <li>the columns are written and read as doubles, because the division weights make a tier-3 result
 *       {@code 28.0} and rounding at the boundary would throw that away;</li>
 *   <li>a club's seasons come back <b>as a set of seasons</b>, which is the input the window needs;</li>
 *   <li>a country's senior and U-21 rows <b>never mix</b> — the bug a single nullable-both-subjects table
 *       would invite and this pair of tables makes impossible.</li>
 * </ul>
 */
class RankingPointsLedgerTest extends BaseTest {

    @Autowired
    ClubSeasonRankingPointsRepository clubPoints;

    @Autowired
    CountrySeasonRankingPointsRepository countryPoints;

    @Autowired
    TeamRepository teams;

    @Autowired
    CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("a club's seasons round-trip as decimals and read back as a set")
    void aClubsSeasonsRoundTrip() {
        Team club = aTeam("Ledger club " + UUID.randomUUID());
        clubPoints.save(new ClubSeasonRankingPoints(club, 1, 28.0));
        clubPoints.save(new ClubSeasonRankingPoints(club, 2, 40.0));
        clubPoints.save(new ClubSeasonRankingPoints(club, 3, -12.5));

        List<ClubSeasonRankingPoints> rows = clubPoints.findByTeamId(club.getId());
        Map<Integer, Double> bySeason = new LinkedHashMap<>();
        for (ClubSeasonRankingPoints row : rows) {
            bySeason.put(row.getSeasonYear(), row.getPoints());
        }

        assertEquals(3, bySeason.size(), "one row per season, and no duplicate that would double-count: "
                + rows.size() + " rows for three seasons");
        assertEquals(28.0, bySeason.get(1), 0.0001);
        assertEquals(40.0, bySeason.get(2), 0.0001);
        assertEquals(-12.5, bySeason.get(3), 0.0001, "a losing season is a negative subtotal");

        // Current season is 3, so season 3 counts at 1.00, season 2 at 0.75 and season 1 at 0.50:
        // 1500 + 28x0.50 + 40x0.75 + (-12.5)x1.00 = 1500 + 14 + 30 - 12.5 = 1531.5.
        // The first version of this wrote the weights the other way round and expected 1551.75, which
        // is the total for a *season 1* current season - so the assertion was for a different question
        // than the one asked, and the window would have passed while reading it.
        assertEquals(1500.0 + 28.0 * 0.50 + 40.0 * 0.75 + -12.5 * 1.00,
                RankingPointsEngine.windowedTotal(3, bySeason), 0.0001,
                "the window reads straight off what was stored");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 are two independent totals and never pool")
    void seniorAndYouthNeverPool() {
        Country country = aCountry("Ledger country " + UUID.randomUUID());
        countryPoints.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.SENIOR, 1, 90.0));
        countryPoints.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.U21, 1, -30.0));

        Map<Integer, Double> senior = new LinkedHashMap<>();
        for (CountrySeasonRankingPoints row
                : countryPoints.findByCountryIdAndLevel(country.getId(), NationalTeamLevel.SENIOR)) {
            senior.put(row.getSeasonYear(), row.getPoints());
        }
        Map<Integer, Double> youth = new LinkedHashMap<>();
        for (CountrySeasonRankingPoints row
                : countryPoints.findByCountryIdAndLevel(country.getId(), NationalTeamLevel.U21)) {
            youth.put(row.getSeasonYear(), row.getPoints());
        }

        assertEquals(1, senior.size());
        assertEquals(1, youth.size());
        assertEquals(90.0, senior.get(1), 0.0001);
        assertEquals(-30.0, youth.get(1), 0.0001,
                "a country that is excellent at both levels is not one entity that did well twice");

        double seniorTotal = RankingPointsEngine.windowedTotal(1, senior);
        double youthTotal = RankingPointsEngine.windowedTotal(1, youth);
        assertNotEquals(seniorTotal, youthTotal, "the two levels must not have merged into one number");
        assertEquals(1590.0, seniorTotal, 0.0001);
        assertEquals(1470.0, youthTotal, 0.0001);
    }

    @Test
    @Transactional
    @DisplayName("one row per club per season, so a second write updates rather than duplicates")
    void oneRowPerClubPerSeason() {
        Team club = aTeam("Unique club " + UUID.randomUUID());
        clubPoints.save(new ClubSeasonRankingPoints(club, 2, 40.0));

        ClubSeasonRankingPoints existing =
                clubPoints.findByTeamIdAndSeasonYear(club.getId(), 2).orElseThrow();
        existing.setPoints(55.0);
        clubPoints.save(existing);

        assertEquals(1, clubPoints.findByTeamId(club.getId()).size(),
                "two rows for one club in one season would be added together by the window");
        assertEquals(55.0, clubPoints.findByTeamIdAndSeasonYear(club.getId(), 2).orElseThrow().getPoints(),
                0.0001);
    }

    @Test
    @Transactional
    @DisplayName("the window beats the raw sum, and the difference is exactly the decay")
    void theWindowBeatsTheRawSum() {
        Map<Integer, Double> history = new LinkedHashMap<>();
        for (int season = 1; season <= 4; season++) {
            history.put(season, 100.0);
        }
        double rawSum = 400.0;
        double windowed = RankingPointsEngine.windowedTotal(4, history) - 1500.0;

        assertTrue(windowed < rawSum,
                "an old season cannot be worth as much as a current one, or the window is decorative");
        assertEquals(250.0, windowed, 0.0001,
                "100 x (1.00 + 0.75 + 0.50 + 0.25) = 250, not 400");
    }

    private Team aTeam(String name) {
        Team team = new Team();
        team.setName(name);
        return teams.save(team);
    }

    private Country aCountry(String name) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode("L" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }
}