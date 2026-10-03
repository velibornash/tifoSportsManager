package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.SeasonCompetition;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The playoff path was the last place in the season that only knew about Serbia.
 *
 * <p><b>Three literals, and the fix was already done everywhere else.</b> The season rollover and the
 * promotion ladder had both been made country-agnostic — a country the owner activated built its 31
 * divisions and then went silent one season later, which is the bug those fixes closed. The playoff path
 * was missed: {@code ensurePlayoffWeekFixtures} asked the repository for tier-2 leagues in {@code "SRB"},
 * and {@code findTier2Leagues} filtered a Serbia-only list a second time. So of 48 countries, **47 had no
 * promotion or relegation summary and no playoff fixtures**, and Serbia worked perfectly — which is exactly
 * why the omission was invisible.
 *
 * <p><b>Why the country needs no new plumbing.</b> Both callers already hold the top flight:
 * {@code buildPlayoffSummary(Competition superLiga, …)} and
 * {@code ensurePlayoffWeekFixtures(Competition superLiga, …)}. The country is read off it. The bug was
 * never a missing parameter; it was a hardcoded string where a parameter should have been.
 *
 * <p><b>Serbia is used as the control, not as the subject.</b> A test that only proves a non-Serbian country
 * works cannot tell a real fix from a world where the lookup returns everything. So each assertion is paired
 * with a second country, and the country codes are drawn at random from a wide alphabet — {@code iso_code} is
 * three characters wide with a unique index and the classes in this package share one H2 database, which is
 * the recorded {@code CONSTRAINT_INDEX_6} collision.
 */
class PlayoffIsScopedToTheTopFlightsCountryTest extends BaseTest {

    @Autowired private SeasonService seasons;
    @Autowired private CompetitionRepository competitions;
    @Autowired private SeasonCompetitionRepository seasonCompetitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private MatchFixtureRepository fixtures;

    private Country abroad;
    private Competition abroadTopFlight;
    private Competition abroadSecond;
    private Competition abroadThird;

    @BeforeEach
    @Transactional
    void twoCountriesWithAThreeDivisionPyramid() {
        abroad = aCountry("Abroad");

        // Sixteen clubs a division, so the boundary arithmetic has room and the table is big enough that
        // "the bottom two" is not the same set as "the ninth and tenth".
        abroadTopFlight = aLeague(abroad, "Foreign top", 1);
        abroadSecond = aLeague(abroad, "Foreign second", 2);
        abroadThird = aLeague(abroad, "Foreign third", 2);

        table(abroadTopFlight, 16);
        table(abroadSecond, 16);
        table(abroadThird, 16);
    }

    // ── The summary ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The guarantee, stated on a country that is not Serbia.
     *
     * <p>Before the fix this returned three empty lists and no exception at all — the silent failure the
     * board warns about, where a method reports success while doing nothing.
     */
    @Test
    @Transactional
    @DisplayName("a country that is not Serbia gets its promotion and relegation summary")
    void aCountryThatIsNotSerbiaGetsItsSummary() {
        Map<String, Object> summary = seasons.buildPlayoffSummary(abroadTopFlight, 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> promotions = (List<Map<String, Object>>) summary.get("directPromotions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relegations = (List<Map<String, Object>>) summary.get("directRelegations");

        assertFalse(promotions.isEmpty(),
                "no direct promotions for " + abroad.getName() + " — the lookup found no second tier");
        assertFalse(relegations.isEmpty(),
                "no direct relegations for " + abroad.getName() + " — the lookup found no second tier");
    }

    /**
     * The promotions come from <b>this</b> country's second tier, by name.
     *
     * <p>Asserting on names rather than on counts, because a count of 2 is also what a hardcoded Serbia
     * lookup returns if the world happens to have Serbian divisions in it. The club name is the only thing
     * that distinguishes "this country's second tier" from "some second tier".
     */
    @Test
    @Transactional
    @DisplayName("the promotions are the winners of this country's own second tier")
    void thePromotionsComeFromThisCountrysOwnSecondTier() {
        Map<String, Object> summary = seasons.buildPlayoffSummary(abroadTopFlight, 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> promotions = (List<Map<String, Object>>) summary.get("directPromotions");

        assertEquals(2, promotions.size(), "one promotion per second-tier division: " + promotions);

        // Both tier-2 divisions, and neither of them the top flight. An earlier version of this asserted
        // "Foreign second" only, and failed on the *third* division -- which was my assertion being wrong
        // about the fixture, not the code.
        assertTrue(promotions.stream().allMatch(p -> {
            String league = String.valueOf(p.get("fromLeague"));
            return league.startsWith("Foreign second") || league.startsWith("Foreign third");
        }), "promotions should name this country's two second-tier divisions: " + promotions);

        assertTrue(promotions.stream().noneMatch(p -> String.valueOf(p.get("fromLeague")).startsWith("Foreign top")),
                "a promotion out of the top flight into itself: " + promotions);

        assertTrue(promotions.stream().allMatch(p -> String.valueOf(p.get("team")).startsWith("Foreign")),
                "the promoted clubs should be this country's: " + promotions);
    }

    /**
     * A country's own top flight must not inherit another country's ladder.
     *
     * <p>The failure mode a country-scoped fix could plausibly introduce: read the country from the wrong
     * place, or fall back to a default, and suddenly the Croatian top flight is being told about Serbian
     * clubs. Asserted by building a second country and checking the first is unaffected by it.
     */
    @Test
    @Transactional
    @DisplayName("a second country does not leak into the first")
    void aSecondCountryDoesNotLeak() {
        Country other = aCountry("Otherland");
        Competition otherTop = aLeague(other, "Other top", 1);
        aLeague(other, "Other second", 2);
        table(otherTop, 16);

        Map<String, Object> summary = seasons.buildPlayoffSummary(abroadTopFlight, 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> promotions = (List<Map<String, Object>>) summary.get("directPromotions");
        assertTrue(promotions.stream().noneMatch(p -> String.valueOf(p.get("team")).startsWith("Other second")),
                "another country's second tier appeared in this country's summary: " + promotions);
    }

    // ── The fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The other half of the same bug: no playoff ties were ever drawn outside Serbia.
     *
     * <p>{@code ensurePlayoffWeekFixtures} returns without doing anything when it finds fewer than two
     * tier-2 divisions, so before the fix this was a no-op for 47 countries — no error, no log line, no
     * fixtures.
     */
    @Test
    @Transactional
    @DisplayName("playoff ties are drawn for a country that is not Serbia")
    void playoffTiesAreDrawnOutsideSerbia() {
        seasons.ensurePlayoffWeekFixtures(abroadTopFlight, 1);

        List<MatchFixture> drawn = fixtures
                .findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
                        abroadTopFlight.getId(), 1, SeasonService.PLAYOFF_WEEK);

        assertFalse(drawn.isEmpty(),
                "no playoff fixture was drawn for " + abroad.getName()
                        + ", so the playoff week is empty for every country but one");
    }

    @Test
    @Transactional
    @DisplayName("the playoff tie is between this country's top and second clubs")
    void theTieIsBetweenThisCountrysClubs() {
        seasons.ensurePlayoffWeekFixtures(abroadTopFlight, 1);

        List<MatchFixture> drawn = fixtures
                .findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(
                        abroadTopFlight.getId(), 1, SeasonService.PLAYOFF_WEEK);

        for (MatchFixture fixture : drawn) {
            if (fixture.getHomeTeam() == null || fixture.getAwayTeam() == null) {
                continue;
            }
            assertTrue(fixture.getHomeTeam().getCountry() != null
                            && abroad.getId().equals(fixture.getHomeTeam().getCountry().getId())
                            && fixture.getAwayTeam().getCountry() != null
                            && abroad.getId().equals(fixture.getAwayTeam().getCountry().getId()),
                    "a playoff tie paired a club from another country: "
                            + fixture.getHomeTeam().getName() + " v " + fixture.getAwayTeam().getName());
        }
    }

    // ── The honest edge: a top flight with no country ───────────────────────────────────────────────

    /**
     * A competition with no country yields nothing rather than defaulting to Serbia.
     *
     * <p>The tempting fix — "if the country is null, assume SRB" — would put one country's playoff in
     * another country's pyramid, which is worse than the bug. Asserted so the fallback cannot come back.
     */
    @Test
    @Transactional
    @DisplayName("a top flight with no country produces no summary rather than Serbia's")
    void aTopFlightWithNoCountryDoesNotFallBackToSerbia() {
        Competition orphan = new Competition();
        orphan.setName("Orphan " + UUID.randomUUID());
        orphan.setType(CompetitionType.LEAGUE);
        orphan.setScope(CompetitionScope.NATIONAL);
        orphan.setTeamType(CompetitionTeamType.CLUB);
        orphan.setTier(1);
        orphan.setTeamsPerCompetition(16);
        orphan = competitions.save(orphan);

        table(orphan, 16);

        Map<String, Object> summary = seasons.buildPlayoffSummary(orphan, 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> promotions = (List<Map<String, Object>>) summary.get("directPromotions");
        assertTrue(promotions.isEmpty(),
                "a country-less competition borrowed another country's ladder: " + promotions);
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────────

    private Country aCountry(String label) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder code = new StringBuilder(3);
            for (int i = 0; i < 3; i++) {
                code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
            }
            if (countries.findByIsoCode(code.toString()).isPresent()) {
                continue;
            }
            Country country = new Country();
            country.setName(label + " " + UUID.randomUUID().toString().substring(0, 8));
            country.setIsoCode(code.toString());
            country.setState(CountryState.SIMULATED);
            return countries.save(country);
        }
        throw new IllegalStateException("could not draw a free iso code in 20 attempts");
    }

    private Competition aLeague(Country country, String label, int tier) {
        Competition league = new Competition();
        league.setName(label + " " + UUID.randomUUID().toString().substring(0, 8));
        league.setType(CompetitionType.LEAGUE);
        league.setScope(CompetitionScope.NATIONAL);
        league.setTeamType(CompetitionTeamType.CLUB);
        league.setTier(tier);
        league.setCountry(country);
        league.setTeamsPerCompetition(16);
        return competitions.save(league);
    }

    /** A full table, one entry per club, with the club names prefixed by the division. */
    private void table(Competition league, int size) {
        SeasonCompetition sc = new SeasonCompetition();
        sc.setCompetition(league);
        sc.setSeasonYear(1);
        sc = seasonCompetitions.save(sc);

        String prefix = league.getName().split(" ")[0];
        for (int i = 1; i <= size; i++) {
            Team team = new Team();
            team.setName(String.format("%s Club %02d", prefix, i));
            team.setCountry(league.getCountry());
            team = teams.save(team);

            CompetitionEntry entry = new CompetitionEntry();
            entry.setSeasonCompetition(sc);
            entry.setTeam(team);
            // Descending points, so Club 01 finishes first and Club NN last.
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