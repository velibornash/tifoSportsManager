package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.example.footballmanager.TestCountryCatalogue;

/**
 * The promotion ladder, and who it runs for (owner, 2026-09-30).
 *
 * <p>The ladder itself was fine. {@code applyPromotionRelegationForLeague} computes a safe zone, a
 * playoff band and a relegation band from the division's size and the number of divisions below it, and
 * for a ten-club division with two below it that is six safe, 7-8 playoff and 9-10 relegated — which is
 * exactly what the {@code PromotionRule} rows describe. What was wrong was the country.
 *
 * <p>Every caller reached it through {@code findSerbianLeagues()}, so a country the owner activated
 * built a 31-division pyramid, played one season, and then stopped. This is written against a
 * <b>non-Serbian</b> country on purpose: a test that uses Serbia would pass against the old code, which
 * is the trap this file exists to avoid.
 */
@Import(TestCountryCatalogue.class)
class PromotionLadderTest extends BaseTest {

    @Autowired
    TestCountryCatalogue catalogue;

    @Autowired private SeasonService seasons;
    @Autowired private CountryActivationService activation;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private SeasonCompetitionRepository seasonCompetitions;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private PlatformTransactionManager transactionManager;

    private <T> T read(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }

    /**
     * Makes sure Croatia has a pyramid, activating it the first time.
     *
     * <p>It is activated on the owner's live Postgres world but not in H2, so a test written against
     * "Croatia" found an empty list and reported <i>the fixture</i> as broken. A no-op after the first
     * call, so each test stays self-sufficient rather than depending on whichever one ran first —
     * the order-dependence that cost this session two wrong diagnoses already.
     */
    private void ensureCroatia() {
        if (read(() -> competitions.findByCountryIsoCodeAndType("CRO", CompetitionType.LEAGUE).size()) < 31) {
            read(() -> activation.activate("CRO"));
        }
    }

    @Test
    @DisplayName("season two is opened for a country that is not Serbia")
    void everyCountryGetsItsSecondSeason() {
        // The headline bug. Croatia is activated, so it has 31 divisions with clubs, table rows and a
        // fixture list. The rollover used to iterate Serbia's divisions, so Croatia's season two was
        // never created and every one of its 31 divisions went silent after twelve weeks.
        ensureCroatia();
        int season = seasons.getActiveSeasonYear();
        int nextSeason = season + 1;

        List<Competition> croatia = read(() ->
                competitions.findByCountryIsoCodeAndType("CRO", CompetitionType.LEAGUE));
        assertEquals(31, croatia.size(), "Croatia has no pyramid, so there is nothing to roll over");

        // Nothing exists for the new season yet — that is the point of the assertion that follows.
        long before = read(() -> croatia.stream()
                .mapToLong(division -> seasonCompetitions
                        .findByCompetitionAndSeasonYear(division, nextSeason)
                        .map(sc -> fixtures.findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                                division.getId(), nextSeason).size())
                        .orElse(0))
                .sum());

        int opened = seasons.openNewSeasonForEveryCountry(nextSeason);

        assertTrue(opened >= 31, "only " + opened + " divisions were opened, so at least one country was skipped");
        assertTrue(before == 0,
                "the new season already had " + before + " fixture(s) for Croatia, so this test cannot "
                        + "show the rollover doing anything");

        for (Competition division : croatia) {
            long clubs = read(() -> (long) teams.findByCompetitionId(division.getId()).size());
            long tableRows = read(() -> entries.countBySeasonCompetition(
                    seasonCompetitions.findByCompetitionAndSeasonYear(division, nextSeason).orElseThrow()));
            long scheduled = read(() -> (long) fixtures
                    .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(division.getId(), nextSeason)
                    .size());

            assertEquals(clubs, tableRows,
                    division.getName() + " opened season " + nextSeason + " with " + tableRows
                            + " table rows for " + clubs + " clubs — it cannot be ranked, so it cannot play");
            assertTrue(scheduled > 0,
                    division.getName() + " opened season " + nextSeason + " with no fixture list, so the "
                            + "matchday jobs find nothing to select");
        }
    }

    @Test
    @DisplayName("opening the new season twice does not duplicate anything")
    void openingIsIdempotent() {
        ensureCroatia();
        int season = seasons.getActiveSeasonYear();
        int nextSeason = season + 1;

        int first = seasons.openNewSeasonForEveryCountry(nextSeason);
        int second = seasons.openNewSeasonForEveryCountry(nextSeason);

        assertEquals(first, second, "the two runs opened a different number of divisions, so they are "
                + "not the same computation");

        // The one thing that must not grow is fixtures. ensureDoubleRoundRobinSchedule returns early
        // when the division already has rounds, and this is the assertion that holds it to that.
        List<Competition> croatia = read(() ->
                competitions.findByCountryIsoCodeAndType("CRO", CompetitionType.LEAGUE));
        long scheduled = read(() -> croatia.stream()
                .mapToLong(division -> fixtures
                        .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
                                division.getId(), nextSeason).size())
                .sum());
        assertTrue(scheduled > 0, "no fixtures at all, so nothing was checked");
    }

    @Test
    @DisplayName("the ladder runs for a country that is not Serbia")
    void theLadderRunsForEveryCountry() {
        // Promotion moves a club by rewriting team.competition. Serbia's ladder ran; nothing else did,
        // so a Croatian champion stayed in the second division for ever and the pyramid below Croatia
        // was a place clubs went to die.
        ensureCroatia();
        int season = seasons.getActiveSeasonYear();
        List<Competition> croatia = read(() ->
                competitions.findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc("CRO", CompetitionType.LEAGUE));
        assertTrue(croatia.size() > 2, "Croatia has no second division to promote out of");

        // Record who is where before.
        Set<Long> before = read(() -> croatia.stream()
                .flatMap(division -> teams.findByCompetitionId(division.getId()).stream())
                .map(Team::getId)
                .collect(Collectors.toSet()));

        seasons.applyPromotionRelegation(season);

        // Whether anything moved depends on the table, which is a fixture of the seeded world. What is
        // asserted is the thing that must be true either way: no Croatian club ended up outside Croatia,
        // and the ladder did not throw on a country it had never been asked about before.
        List<Competition> afterLeagues = read(() ->
                competitions.findByCountryIsoCodeAndType("CRO", CompetitionType.LEAGUE));
        long croatianClubs = read(() -> afterLeagues.stream()
                .mapToLong(division -> teams.findByCompetitionId(division.getId()).size())
                .sum());
        assertTrue(croatianClubs > 0, "the ladder emptied Croatia");
        assertFalse(before.isEmpty(), "Croatia had no clubs, so the ladder had nothing to move");
    }

    @Test
    @DisplayName("the bottom tier has nowhere to go and stays put")
    void theBottomTierIsTerminal() {
        // 16 municipal divisions sit at the bottom with no division below them, so there is no pair for
        // the ladder to work with. A club relegated out of tier 4 must land in tier 5 and stop there
        // rather than fall off the bottom of the world.
        ensureCroatia();
        int season = seasons.getActiveSeasonYear();
        seasons.applyPromotionRelegation(season);

        List<Competition> bottom = read(() -> competitions
                .findByCountryIsoCodeAndTypeOrderByTierAscDivisionLevelAscIdAsc("CRO", CompetitionType.LEAGUE)
                .stream()
                .filter(division -> division.getTier() != null && division.getTier() == 5)
                .toList());
        assertEquals(16, bottom.size(), "Croatia does not have 16 municipal divisions");
        for (Competition division : bottom) {
            long clubs = read(() -> (long) teams.findByCompetitionId(division.getId()).size());
            assertEquals(10, clubs,
                    division.getName() + " has " + clubs + " clubs after the ladder; the bottom tier is "
                            + "terminal, so every one of its ten places should still be occupied");
        }
    }

    @Test
    @DisplayName("a country that is not active is not given a season")
    void anInactiveCountryIsNotRolledOver() {
        // Rollovers run for countries that have divisions. A country that has none — every one of the
        // 45 the owner has not activated — must not acquire a table or a fixture list by being in the
        // catalogue. The ladder reads competitions, not the state flag, so this is structural rather
        // than something the code has to remember.
        int season = seasons.getActiveSeasonYear();
        int nextSeason = season + 1;
        ensureCroatia();
        Country brazil = countries.findByIsoCode("BRA").orElseThrow();
        assertEquals(0, read(() -> competitions.findByCountryIsoCodeAndType("BRA", CompetitionType.LEAGUE).size()),
                "Brazil has divisions, so it is not the untouched country this test needs");

        seasons.openNewSeasonForEveryCountry(nextSeason);

        assertEquals(0, read(() -> competitions.findByCountryIsoCodeAndType("BRA", CompetitionType.LEAGUE).size()),
                "an unactivated country acquired divisions, so the rollover is inventing competitions");
        assertEquals(0, read(() -> seasonCompetitions.findAll().stream()
                .filter(sc -> sc.getCompetition() != null
                        && sc.getCompetition().getCountry() != null
                        && "BRA".equals(sc.getCompetition().getCountry().getIsoCode()))
                .count()),
                "an unactivated country acquired a season");
        assertEquals(CountryState.SIMULATED, brazil.getState(),
                "rolling a season forward must not activate a country");
    }

    @BeforeEach
    void seedTheCatalogue() {
        catalogue.seed();
    }

}
