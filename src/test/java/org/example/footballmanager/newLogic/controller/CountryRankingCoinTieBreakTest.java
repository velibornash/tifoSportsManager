package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountrySeasonRankingPoints;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.NationalTeamLevel;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.CountrySeasonRankingPointsRepository;
import org.example.footballmanager.newLogic.model.RankingTieBreakSeed;
import org.example.footballmanager.newLogic.repository.RankingTieBreakSeedRepository;
import org.example.footballmanager.newLogic.service.RankingTieBreakService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Countries level on points get different positions, settled by a coin that does not move (owner,
 * 2026-10-08).
 *
 * <p><b>The rule being enforced:</b> equal totals get <b>distinct</b> positions, never a shared rank and
 * never alphabetical. Both of the alternatives this replaces were wrong in the same way — each one is an
 * arbitrary rule presented as a sporting result, and a manager can see either of them instantly.
 *
 * <p>So the test states three separate things, because they fail separately:
 *
 * <ol>
 *   <li>two countries on identical points get <b>two different positions</b>;</li>
 *   <li>the order between them is <b>the same on every read</b> — a coin re-rolled per read is a ladder
 *       that reorders itself while nobody is watching;</li>
 *   <li>that order is <b>not the alphabet</b>, so it cannot have come from the old name tie-break.</li>
 * </ol>
 */
class CountryRankingCoinTieBreakTest extends BaseTest {

    @Autowired CountryController controller;
    @Autowired CountryRepository countries;
    @Autowired CountrySeasonRankingPointsRepository points;
    @Autowired RankingTieBreakSeedRepository seeds;
    @Autowired org.example.footballmanager.newLogic.service.SeasonService seasons;
    @Autowired org.example.footballmanager.newLogic.repository.TeamRepository sides;

    private int season() {
        return seasons.getActiveSeasonYear();
    }

    @Test
    @Transactional
    @DisplayName("two countries level on points get two positions, and neither shares the other's rank")
    void levelCountriesGetDistinctPositions() {
        int season = season();
        // Named so that alphabetical order and a coin are visibly different things to assert on.
        Country alpha = aCountry("Alpha level");
        Country zulu = aCountry("Zulu level");
        points.save(new CountrySeasonRankingPoints(alpha, NationalTeamLevel.SENIOR, season, 200.0));
        points.save(new CountrySeasonRankingPoints(zulu, NationalTeamLevel.SENIOR, season, 200.0));

        List<Map<String, Object>> ranking = controller.ranking("senior");
        int alphaPosition = positionOf(ranking, alpha.getIsoCode());
        int zuluPosition = positionOf(ranking, zulu.getIsoCode());

        assertFalse(alphaPosition == zuluPosition,
                "identical totals must not share a position: alpha is " + alphaPosition
                        + " and zulu is " + zuluPosition + ". The owner's rule is distinct positions.");
    }

    @Test
    @Transactional
    @DisplayName("the coin gives the same answer twice, so the ladder does not reorder itself")
    void theCoinIsStableAcrossReads() {
        int season = season();
        Country alpha = aCountry("Stable alpha");
        Country zulu = aCountry("Stable zulu");
        points.save(new CountrySeasonRankingPoints(alpha, NationalTeamLevel.SENIOR, season, 150.0));
        points.save(new CountrySeasonRankingPoints(zulu, NationalTeamLevel.SENIOR, season, 150.0));

        int firstAlpha = positionOf(controller.ranking("senior"), alpha.getIsoCode());
        int firstZulu = positionOf(controller.ranking("senior"), zulu.getIsoCode());

        assertEquals(firstAlpha, positionOf(controller.ranking("senior"), alpha.getIsoCode()),
                "reading the ladder twice must not move a country");
        assertEquals(firstZulu, positionOf(controller.ranking("senior"), zulu.getIsoCode()),
                "reading the ladder twice must not move a country");
    }

    @Test
    @Transactional
    @DisplayName("the tie is not the alphabet, so it cannot have come from the old name tie-break")
    void theTieIsNotAlphabetical() {
        int season = season();
        // Twelve names, all level: with an alphabetical tie-break the order is A, B, C … and with a coin
        // it is a shuffle. Twelve is enough that "it happens to come out alphabetical" is not luck.
        List<Country> level = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Country country = aCountry(String.format("Level %02d", i));
            points.save(new CountrySeasonRankingPoints(country, NationalTeamLevel.SENIOR, season, 100.0));
            level.add(country);
        }

        List<String> order = controller.ranking("senior").stream()
                .filter(row -> "senior".equals(row.get("level")))
                .map(row -> String.valueOf(row.get("name")))
                .filter(name -> name.startsWith("Level "))
                .toList();

        assertEquals(12, order.size(), "all twelve level countries are on the list");
        List<String> alphabetical = order.stream().sorted().toList();
        assertFalse(alphabetical.equals(order),
                "the twelve level countries came back in alphabetical order, which is the name tie-break "
                        + "the owner rejected, not a coin: " + order);
    }

    @Test
    @Transactional
    @DisplayName("the seed is written once and read back, so the draw is a record rather than arithmetic")
    void theSeedIsStoredOnce() {
        int season = season();
        aCountry("Seeded once");

        controller.ranking("senior");
        controller.ranking("senior");
        controller.ranking("u21");

        long rows = seeds.findAll().stream()
                .filter(row -> row.getSeasonYear() == season)
                .filter(row -> row.getScope() == org.example.footballmanager.newLogic.model.RankingTieBreakSeed.Scope.COUNTRY)
                .count();
        assertEquals(1, rows,
                "three reads of the ladder must not write three coins — that is what makes a re-roll a "
                        + "re-roll rather than a draw");
    }

    @Test
    @Transactional
    @DisplayName("senior and U-21 are level in their own right, each with its own coin")
    void theTwoLevelsAreCoinedSeparately() {
        int season = season();
        Country alpha = aCountry("Both alpha");
        Country zulu = aCountry("Both zulu");
        points.save(new CountrySeasonRankingPoints(alpha, NationalTeamLevel.SENIOR, season, 90.0));
        points.save(new CountrySeasonRankingPoints(zulu, NationalTeamLevel.SENIOR, season, 90.0));
        points.save(new CountrySeasonRankingPoints(alpha, NationalTeamLevel.U21, season, 90.0));
        points.save(new CountrySeasonRankingPoints(zulu, NationalTeamLevel.U21, season, 90.0));

        List<Map<String, Object>> senior = controller.ranking("senior");
        List<Map<String, Object>> youth = controller.ranking("u21");

        assertFalse(positionOf(senior, alpha.getIsoCode()) == positionOf(senior, zulu.getIsoCode()),
                "the senior ladder separates the two");
        assertFalse(positionOf(youth, alpha.getIsoCode()) == positionOf(youth, zulu.getIsoCode()),
                "and so does the U-21 ladder — they are separate ladders, not one list with a filter");
    }

    @Test
    @Transactional
    @DisplayName("sanity: two ids draw different coins and one id always draws the same")
    void theCoinSeparatesAndRepeats() {
        RankingTieBreakService tieBreaks = new RankingTieBreakService(seeds);
        long coin = tieBreaks.seedFor(RankingTieBreakSeed.Scope.COUNTRY, season(), "");

        assertTrue(tieBreaks.coin(coin, 1L) != tieBreaks.coin(coin, 2L),
                "two ids drawing the same coin would leave the tie-break ordering nothing to do");
        assertEquals(tieBreaks.coin(coin, 7L), tieBreaks.coin(coin, 7L),
                "and the same id must always draw the same coin, or the ladder shuffles on every read");
    }
    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private static int positionOf(List<Map<String, Object>> rows, String isoCode) {
        return rows.stream()
                .filter(row -> isoCode.equals(row.get("isoCode")))
                .map(row -> (Integer) row.get("position"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ranking row for " + isoCode));
    }

    private int nextIso = 0;

    private Country aCountry(String name) {
        Country country = new Country();
        country.setName(name);
        // ISO_CODE is three characters in the schema, so uniqueness comes from a counter rather than a
        // random suffix that would not fit.
        country.setIsoCode("X" + (char) ('a' + (nextIso++ % 26)));
        org.example.footballmanager.newLogic.model.Team side = new org.example.footballmanager.newLogic.model.Team();
        side.setName("NT " + name);
        side.setFormation("4-3-3");
        country.setSeniorNationalTeam(sides.save(side));
        country.setState(CountryState.SIMULATED);
        country.setReputation(1500);
        return countries.save(country);
    }


}