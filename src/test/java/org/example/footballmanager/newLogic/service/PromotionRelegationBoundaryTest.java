package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summary names the clubs the season actually moved.
 *
 * <p><b>This exists because the boundary was written down twice and the two copies disagreed.</b>
 * {@code applyPromotionRelegationForLeague} computed it — {@code safeCount = expectedTeams - 2 *
 * movementSlots} — so a sixteen-club league over two lower leagues relegates the <b>15th and 16th</b>. The
 * summary that tells the manager which clubs those are hardcoded {@code top.get(8)} and {@code top.get(9)}:
 * the <b>9th and 10th</b>.
 *
 * <p>So the game relegated one pair of clubs and told the manager about another. Both answers look entirely
 * plausible, which is why nothing caught it — and it is the one finding the audit escalated rather than
 * listed.
 *
 * <p>There is now one definition, {@code boundaryFor}, called by both paths. The test asserts the property
 * that matters rather than the arithmetic: <b>the summary must name clubs from the bottom of the table</b>,
 * and it must change when the league size changes — which is precisely what the hardcoded indices could not
 * do.
 */
class PromotionRelegationBoundaryTest extends BaseTest {

    @Autowired private SeasonService seasons;
    @Autowired private CompetitionRepository competitions;
    @Autowired private SeasonCompetitionRepository seasonCompetitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;

    @Test
    @Transactional
    @DisplayName("the summary relegates the bottom of the table, not the middle")
    void theSummaryRelegatesTheBottomOfTheTable() {
        Competition top = aLeague("Boundary top", 1, 16);
        ensureTier2LeaguesExist(2);
        ensureTier2LeaguesExist(2);
        table(top, 16);

        Map<String, Object> summary = seasons.buildPlayoffSummary(top, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relegations = (List<Map<String, Object>>) summary.get("directRelegations");

        assertTrue(relegations.size() >= 2,
                "expected at least two relegations for a 16-club league, got " + relegations.size());

        // The 15th and 16th of a sixteen-club league. With names "Club 01".."Club 16" and the table sorted,
        // the bottom two are the highest-numbered names.
        assertTrue(relegations.stream().anyMatch(r -> "Club 16".equals(r.get("team"))),
                "the bottom club is not among the relegated: " + relegations);
        assertTrue(relegations.stream().anyMatch(r -> "Club 15".equals(r.get("team"))),
                "the second-from-bottom club is not among the relegated: " + relegations);

        // And explicitly: the clubs the hardcoded indices used to name must NOT be here.
        assertTrue(relegations.stream().noneMatch(r -> "Club 09".equals(r.get("team"))
                        || "Club 10".equals(r.get("team"))),
                "the summary still names the 9th and 10th, which is what the hardcoded indices did: "
                        + relegations);

    }

    /**
     * The property the hardcoded indices could not have: <b>the boundary moves with the league's size.</b>
     *
     * <p>Rather than assume how many lower leagues exist — the test profile may already have several, and
     * {@code safeCount = expectedTeams - 2 * movementSlots} goes negative once there are too many — this
     * derives the expected answer from the same inputs and asserts the summary agrees.
     *
     * <p>An earlier version asserted a fixed answer for a ten-club league and failed with an empty list,
     * because the boundary was correctly unusable for that league given the profile's real number of lower
     * leagues. The assertion was wrong, not the code.
     */
    @Test
    @Transactional
    @DisplayName("the boundary follows the league's size")
    void theBoundaryFollowsTheLeagueSize() {
        ensureTier2LeaguesExist(2);

        for (int size : new int[]{16, 20, 24}) {
            Competition league = aLeague("Boundary sized " + size, 1, size);
            table(league, size);

            Map<String, Object> summary = seasons.buildPlayoffSummary(league, 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relegations = (List<Map<String, Object>>) summary.get("directRelegations");

            int movementSlots = serbianTier2Count();
            int safeCount = size - movementSlots * 2;
            if (safeCount < 1) {
                // The boundary genuinely does not exist for this size, and an empty list is honest.
                assertTrue(relegations.isEmpty(),
                        "a " + size + "-club league over " + movementSlots + " lower leagues has no "
                                + "boundary, yet it reported " + relegations);
                continue;
            }

            // The relegated clubs are the last movementSlots of the table, whatever size the league is.
            String bottom = String.format("Club %02d", size);
            String justAboveBoundary = String.format("Club %02d", safeCount + 1);

            assertTrue(relegations.stream().anyMatch(r -> bottom.equals(r.get("team"))),
                    "for a " + size + "-club league the bottom club should be relegated: " + relegations);
            assertTrue(relegations.stream().noneMatch(r -> justAboveBoundary.equals(r.get("team"))),
                    "for a " + size + "-club league the boundary is at " + safeCount + ", so "
                            + justAboveBoundary + " must not be relegated: " + relegations);
        }
    }

    /**
     * Guarantee at least {@code wanted} Serbian tier-2 leagues.
     *
     * <p>{@code findTier2Leagues} is hardcoded to Serbia and filters on tier 2, and the summary produces
     * nothing at all without two of them — so the test creates what it needs rather than inheriting whatever
     * the profile happens to hold. The PostgreSQL world has two; the H2 test profile has its own set, and an
     * earlier version of this test that assumed two produced empty results and was wrong about the code.
     */
    private void ensureTier2LeaguesExist(int wanted) {
        while (serbianTier2Count() < wanted) {
            aLeague("Boundary lower " + serbianTier2Count(), 2, 16);
        }
    }

    /** How many tier-2 leagues the summary will actually see, which the test must not assume. */
    private int serbianTier2Count() {
        return seasons.getSerbianLeaguesInOrder().stream()
                .filter(c -> c.getCountry() != null && "SRB".equalsIgnoreCase(c.getCountry().getIsoCode()))
                .filter(c -> java.util.Objects.equals(c.getTier(), 2))
                .toList()
                .size();
    }

    private Competition aLeague(String label, int tier, int teamsPerCompetition) {
        // findTier2Leagues() is hardcoded to "SRB" (SeasonService:1083), so a summary for any other
        // country silently reports nothing. That is a separate finding, on the board - the test uses
        // Serbia because of it, not because it is testing Serbia.
        //
        // Serbia already exists on the test profile and iso_code is unique, so it is looked up rather
        // than created. Creating it fails on the unique index.
        Country country = countries.findByIsoCode("SRB").orElseGet(() -> {
            Country fresh = new Country();
            fresh.setName("Boundary Serbia");
            fresh.setIsoCode("SRB");
            fresh.setState(CountryState.SIMULATED);
            return countries.save(fresh);
        });

        Competition league = new Competition();
        league.setName(label + " " + UUID.randomUUID());
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(tier);
        league.setCountry(country);
        league.setTeamsPerCompetition(teamsPerCompetition);
        return competitions.save(league);
    }

    /** A full table, one entry per club, with predictable names. */
    private void table(Competition league, int size) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(league);
        sc.setSeasonYear(1);
        sc = seasonCompetitions.save(sc);

        for (int i = 1; i <= size; i++) {
            Country country = league.getCountry();
            Team team = new Team();
            team.setName(String.format("Club %02d", i));
            team.setCountry(country);
            team = teams.save(team);

            CompetitionEntry entry = new CompetitionEntry();
            entry.setSeasonCompetition(sc);
            entry.setTeam(team);
            // Descending points so the table order is Club 01 first, Club NN last.
            entry.setPoints(size - i);
            entry.setWins(Math.max(0, size - i));
            entry.setDraws(0);
            entry.setLosses(0);
            entry.setGoalsScored(size - i);
            entry.setGoalsConceded(0);
            entries.save(entry);
        }
    }
}