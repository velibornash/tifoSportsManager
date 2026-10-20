package org.tifo.footballmanager.newLogic.controller;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.Competition;
import org.tifo.footballmanager.newLogic.model.CompetitionScope;
import org.tifo.footballmanager.newLogic.model.CompetitionType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.SeasonCompetition;
import org.tifo.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.tifo.footballmanager.newLogic.repository.CompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.model.CompetitionEntry;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.service.SeasonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The qualifying race on the country page is a league table (owner, 2026-10-13).
 *
 * <p>The owner read a Masters Cup race off the screen and asked how a club on 14 points could be above a
 * club on 16. It is the one page in the product where the order decides <b>who goes to a continental
 * competition</b>, so an order that is wrong there is a wrong answer about football, not a cosmetic one.
 *
 * <p>Measured on the owner's database before the fix, tier 2 of Serbia:
 *
 * <pre>
 *   1  OFK Niš                16 pts   gd 7   Qualifies
 *   2  GFK Nova Pazova 1923   14 pts   gd 2   Qualifies
 *   3  GFK Borski Zaječar     16 pts   gd 7   Candidate
 *   4  ŽFK Tamiš Požarevac    12 pts   gd 6   Candidate
 * </pre>
 *
 * <p>The cause was not a tie-break that was slightly wrong. The Masters race is two positions from every
 * division in the tier — 2nd and 3rd — and it was assembled as {@code poolAt(1) + poolAt(2)}: two lists,
 * each sorted on its own, concatenated and never sorted together. Every 2nd-placed club therefore ranked
 * above every 3rd-placed one <em>whatever their points</em>. Borski Zaječar, on 16 points, lost its place
 * to a club on 14.
 *
 * <p>The rule this pins, in the owner's words: <b>points, then goal difference, then the draw.</b> The
 * existing {@code LeagueTableOrder} already carries points → goal difference → goals scored → team id; the
 * third key is the one place the owner has been asked to choose, and this test states the part he was
 * explicit about — <b>a club with fewer points never ranks above one with more</b>, whatever happens below
 * that line.
 */
class CountryQualifyingRaceOrderTest extends BaseTest {

    @Autowired private CountryController controller;
    @Autowired private SeasonService seasons;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private SeasonCompetitionRepository seasonCompetitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private TeamRepository teams;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /**
     * Two divisions in tier 2, so the Masters race is 2nd and 3rd from each — the shape that broke.
     *
     * <p>The points are chosen so that the correct order is impossible to get by accident: the 2nd place
     * of division A has fewer points than the 3rd place of division B. Any implementation that concatenates
     * the two sub-pools without sorting them together puts the 2nd-placed club first.
     */
    @Test
    @DisplayName("the Masters race is ordered by points, not by which division a club finished in")
    void theMastersRaceIsOrderedByPoints() {
        String stamp = Integer.toString(SEQUENCE.incrementAndGet());
        Country country = aCountry("ZZ Qualifying " + stamp, "ZQ" + stamp);
        Competition leagueA = aLeague(country, "ZZ Qualifying League A " + stamp, 2);
        Competition leagueB = aLeague(country, "ZZ Qualifying League B " + stamp, 2);

        // Division A: 2nd place on 12 points.  Division B: 3rd place on 30 points.
        enter(leagueA, "ZZ A1 " + stamp, 40, 10, 20);   // 1st
        enter(leagueA, "ZZ A2 " + stamp, 12, 20, 10);   // 2nd - must NOT win on position alone
        enter(leagueA, "ZZ A3 " + stamp, 5, 15, 10);    // 3rd
        enter(leagueB, "ZZ B1 " + stamp, 35, 20, 10);   // 1st
        enter(leagueB, "ZZ B2 " + stamp, 25, 20, 10);   // 2nd
        enter(leagueB, "ZZ B3 " + stamp, 30, 25, 10);   // 3rd - must win on points

        List<Map<String, Object>> masters = mastersRace(country, 2);
        assertTrue(masters.size() >= 4, "the Masters race drew only " + masters.size()
                + " clubs, so there is nothing to reorder and this test proves nothing");

        assertPointsNeverGoBackwards(masters);
        assertTrue(qualifies(masters, 0), "the top club does not qualify, so this test proves nothing");

        // And the specific pair the owner's screen showed: 30 points in third place of one division has
        // to beat 12 points in second place of another.
        String topName = String.valueOf(masters.get(0).get("teamName"));
        assertTrue(topName.startsWith("ZZ B"), "a club on 12 points, second in its division, took the top "
                + "place in the Masters race ahead of a club on 30 points: " + topName);
    }

    /** Tier 1 has one division, so nothing is concatenated — and it must still come out right. */
    @Test
    @DisplayName("a single-division tier's races are ordered by points too")
    void singleDivisionTiersAreOrderedByPoints() {
        String stamp = Integer.toString(SEQUENCE.incrementAndGet());
        Country country = aCountry("ZZ Qualifying Top " + stamp, "ZT" + stamp);
        Competition league = aLeague(country, "ZZ Qualifying Top League " + stamp, 1);

        enter(league, "ZZ T1 " + stamp, 30, 20, 10);
        enter(league, "ZZ T2 " + stamp, 20, 20, 10);
        enter(league, "ZZ T3 " + stamp, 10, 20, 10);
        enter(league, "ZZ T4 " + stamp, 5, 20, 10);

        // Tier 1 is the one division's table read straight: Champions is the winner, Masters the two
        // runners-up, Challenge the fourth. Two or more clubs each, or the ordering has nothing to order.
        for (Map.Entry<String, Integer> race : Map.of(
                "Champions Cup", 1, "Masters Cup", 2, "Challenge Cup", 1).entrySet()) {
            List<Map<String, Object>> standings = race(country, 1, race.getKey());
            assertTrue(standings.size() >= race.getValue(),
                    race.getKey() + " drew " + standings.size() + " clubs and needs at least "
                            + race.getValue() + ", so there is nothing to reorder");
            assertPointsNeverGoBackwards(standings);
        }
    }

    // --- the invariant the owner stated ---

    /**
     * Points are the first key, so the sequence can only stay level or fall. It never rises.
     */
    private static void assertPointsNeverGoBackwards(List<Map<String, Object>> race) {
        int previous = Integer.MAX_VALUE;
        for (Map<String, Object> row : race) {
            int points = ((Number) row.get("points")).intValue();
            assertTrue(points <= previous, "the race is not ordered by points: " + previous
                    + " points is followed by " + points + " (" + row.get("teamName")
                    + "). A club with fewer points is ranked above one with more, and this page decides "
                    + "who qualifies for a continental competition.");
            previous = points;
        }
    }

    private static boolean qualifies(List<Map<String, Object>> race, int index) {
        return Boolean.TRUE.equals(race.get(index).get("qualifies"));
    }

    private List<Map<String, Object>> mastersRace(Country country, int tier) {
        return race(country, tier, "Masters Cup");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> race(Country country, int tier, String cup) {
        Map<String, Object> response = controller.qualifying(country.getIsoCode());
        List<Map<String, Object>> tiers = (List<Map<String, Object>>) response.get("tiers");
        return tiers.stream()
                .filter(t -> ((Number) t.get("tier")).intValue() == tier)
                .flatMap(t -> ((List<Map<String, Object>>) t.get("cups")).stream())
                .filter(c -> cup.equals(c.get("cup")))
                .findFirst()
                .map(c -> (List<Map<String, Object>>) c.get("standings"))
                .orElseThrow(() -> new AssertionError("no " + cup + " race for tier " + tier
                        + ", so this test proves nothing"));
    }

    // --- world ---

    private Country aCountry(String name, String isoCode) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(isoCode);
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    private Competition aLeague(Country country, String name, int tier) {
        Competition league = new Competition();
        league.setName(name);
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setCountry(country);
        league.setTier(tier);
        return competitions.save(league);
    }

    private void enter(Competition league, String name, int points, int scored, int conceded) {
        SeasonCompetition sc = seasonCompetitions
                .findByCompetitionAndSeasonYear(league, seasons.getActiveSeasonYear())
                .orElseGet(() -> {
                    SeasonCompetition created = new SeasonCompetition();
                    created.setCompetition(league);
                    created.setSeasonYear(seasons.getActiveSeasonYear());
                    return seasonCompetitions.save(created);
                });

        Team club = new Team();
        club.setName(name);
        club.setCountry(league.getCountry());
        club.setCompetition(league);
        club.setHumanControlled(false);
        club.setReputation(50.0);
        club = teams.save(club);

        CompetitionEntry entry = new CompetitionEntry();
        entry.setSeasonCompetition(sc);
        entry.setTeam(club);
        entry.setPoints(points);
        entry.setGoalsScored(scored);
        entry.setGoalsConceded(conceded);
        entry.setWins(4);
        entry.setDraws(0);
        entry.setLosses(6);
        entries.save(entry);
    }
}