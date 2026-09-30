package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballmanager.newLogic.service.CountryActivationService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.example.footballmanager.newLogic.util.players.BotLeagueStandard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning a country from represented into played (owner, 2026-09-30).
 *
 * <p>{@code CountryState.ACTIVE} used to be a label: set once for Serbia, styled on the World page, and
 * read by nothing that did any work. The panel the owner asked for is only worth building if the flag
 * means football exists, so these assert the football.
 *
 * <p>Written against the <b>seeded</b> world rather than fixtures, because the point is what activating
 * a real catalogue country does. A country is created without a pyramid, exactly as the 47 represented
 * ones are.
 */
class CountryActivationTest extends BaseTest {

    @Autowired private CountryActivationService activation;
    @Autowired private PyramidBuilder pyramids;
    @Autowired private SeasonService seasons;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private PlayerRepository players;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private MatchFixtureRepository fixtures;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * A read that owns its session.
     *
     * <p>{@code Team.country} and {@code Competition.country} are lazy proxies, and these tests are
     * deliberately not {@code @Transactional} — activation commits in its own transaction, so a
     * long-lived test transaction would be asserting against a world that had not been written yet.
     * That leaves the proxies uninitialised and the failure is a LazyInitializationException rather
     * than anything about the code under test.
     */
    private <T> T read(java.util.function.Supplier<T> body) {
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return template.execute(status -> body.get());
    }

    @Test
    @DisplayName("an activated country ends up with a real, playable pyramid")
    void activationBuildsPlayableFootball() {
        // Austria is a real catalogue country and, on the seeded world, holds two stray clubs and no
        // divisions — the same state as every country the owner has not activated.
        Country austria = countries.findByIsoCode("AUT").orElseThrow();
        int divisionsBefore = competitions
                .findByCountryIsoCodeAndType("ARG", CompetitionType.LEAGUE).size();

        CountryActivationService.Result result = read(() -> activation.activate("ARG"));
        int seasonYear = seasons.getActiveSeasonYear();

        assertEquals(CountryState.ACTIVE, result.state(), "activation did not mark the country active");
        assertEquals(31, result.divisions(),
                "expected 1 + 2 + 4 + 8 + 16 divisions, got " + result.divisions()
                        + " (was " + divisionsBefore + " before)");
        assertEquals(310, result.clubs(), "expected 31 x 10 clubs, got " + result.clubs());

        List<Competition> divisions = read(() ->
                competitions.findByCountryIsoCodeAndType("ARG", CompetitionType.LEAGUE));
        assertEquals(31, divisions.size(), "the divisions on the database do not match the report");

        // A division with no table rows plays no football, and neither does one with no fixture list.
        for (Competition division : divisions) {
            List<Team> clubs = read(() -> teams.findByCompetitionId(division.getId()));
            assertEquals(10, clubs.size(),
                    division.getName() + " has " + clubs.size() + " clubs, not 10");
            long tableRows = read(() -> entries.countBySeasonCompetition(
                    seasons.ensureSeasonCompetition(division, seasonYear)));
            assertEquals(10, tableRows, division.getName() + " has no table row for a club");
            long scheduled = read(() -> countFixturesOf(division, seasonYear));
            assertTrue(scheduled > 0, division.getName() + " has no fixture list, so it never plays");
        }
    }

    @Test
    @DisplayName("every club gets a squad, and a keeper")
    void everyClubGetsASquad() {
        read(() -> activation.activate("AUS"));

        List<Team> clubs = read(() -> clubsOf("AUS"));
        assertFalse(clubs.isEmpty(), "no clubs were created, so there is nothing to check");

        for (Team club : clubs) {
            List<Player> squad = read(() -> players.findByTeamId(club.getId()));
            assertEquals(25, squad.size(), club.getName() + " has " + squad.size() + " players, not 25");
            long keepers = squad.stream()
                    .filter(player -> player.getPosition() == org.example.footballmanager.newLogic.model.Position.GK)
                    .count();
            assertTrue(keepers >= 2,
                    club.getName() + " has " + keepers + " goalkeeper(s); an injured or sent-off keeper "
                            + "with no replacement forfeits the match");
        }
    }

    @Test
    @DisplayName("the pyramid has a strength gradient down it")
    void thePyramidHasAGradient() {
        // The reason the previous commit exists. A pyramid where the municipal division is as strong as
        // the premier league is a list of divisions, not a league system.
        read(() -> activation.activate("BEL"));

        double premier = averageForTier("BEL", 1);
        double municipal = averageForTier("BEL", 5);

        assertTrue(premier > municipal,
                "the premier division averages " + premier + " and the municipal " + municipal);
        assertTrue(premier - municipal >= 3.0,
                "the top and the bottom of the pyramid differ by only " + (premier - municipal)
                        + ", which a season of results would wash out");
    }

    @Test
    @DisplayName("activating twice does not build a second pyramid")
    void activationIsIdempotent() {
        CountryActivationService.Result first = read(() -> activation.activate("BIH"));
        int clubsAfterFirst = countClubs("BIH");
        long fixturesAfterFirst = countFixtures("BIH");

        CountryActivationService.Result second = read(() -> activation.activate("BIH"));

        assertTrue(second.alreadyBuilt(), "the second activation rebuilt the pyramid from scratch");
        assertEquals(CountryState.ACTIVE, second.state());
        assertEquals(clubsAfterFirst, countClubs("BIH"),
                "the second activation added clubs: " + clubsAfterFirst + " → " + countClubs("BIH"));
        assertEquals(fixturesAfterFirst, countFixtures("BIH"),
                "the second activation re-drew the fixture lists");
        assertNotEquals(0, first.clubs(), "the first activation built nothing, so the test proves nothing");
    }

    @Test
    @DisplayName("division and club names do not collide inside a country")
    void namesAreUnique() {
        // Trimming "First Division A" and "First Division B" to "First" made two divisions of one tier
        // generate the same ten club names, and findByName throws on a duplicate rather than returning
        // the first — so the second division failed the activation outright.
        read(() -> activation.activate("BRA"));

        List<Competition> divisions = read(() ->
                competitions.findByCountryIsoCodeAndType("BRA", CompetitionType.LEAGUE));
        assertEquals(31, divisions.size(), "the activation did not finish, so names cannot be checked");
        Set<String> divisionNames = divisions.stream().map(Competition::getName).collect(Collectors.toSet());
        assertEquals(31, divisionNames.size(),
                "two divisions share a name: " + divisions.size() + " rows, " + divisionNames.size() + " names");

        List<Team> clubs = read(() -> clubsOf("BRA"));
        Set<String> clubNames = clubs.stream().map(Team::getName).collect(Collectors.toSet());
        assertEquals(clubs.size(), clubNames.size(),
                clubs.size() + " clubs share only " + clubNames.size() + " names");
    }

    @Test
    @DisplayName("another country's activation leaves this one alone")
    void activationIsScopedToOneCountry() {
        read(() -> activation.activate("BUL"));
        int serbiaDivisions = competitions.findByCountryIsoCodeAndType("SRB", CompetitionType.LEAGUE).size();
        int serbiaClubs = countClubs("SRB");

        read(() -> activation.activate("BUL"));

        assertEquals(serbiaDivisions,
                competitions.findByCountryIsoCodeAndType("SRB", CompetitionType.LEAGUE).size(),
                "activating Bulgaria changed Serbia's division count");
        assertEquals(serbiaClubs, countClubs("SRB"), "activating Austria changed Serbia's club count");
        // And the national sides are not club-division business at all.
        assertNotEquals(0, serbiaDivisions, "Serbia has no pyramid, so this test proves nothing");
    }

    @Test
    @DisplayName("the panel lists every country with its state")
    void thePanelCanListTheWorld() {
        List<CountryActivationService.CountryRow> rows = read(activation::overview);

        assertTrue(rows.size() >= 48, "the panel lists " + rows.size() + " countries");
        assertTrue(rows.stream().anyMatch(CountryActivationService.CountryRow::active),
                "no country is active, so the panel would show nothing to compare against");
        assertTrue(rows.stream().filter(row -> !row.active()).count() > 0,
                "every country is active, so the button would never be shown");

        for (CountryActivationService.CountryRow row : rows) {
            assertTrue(row.name() != null && !row.name().isBlank(), "a country has no name: " + row);
            assertTrue("ACTIVE".equals(row.state()) || "SIMULATED".equals(row.state()),
                    "unexpected state " + row.state() + " for " + row.name());
        }
    }

    @Test
    @DisplayName("a country that is already active but has a pyramid is only re-flagged")
    void aHalfFinishedActivationHeals() {
        // The interrupted case: the divisions were built, the flag write never happened. The guard asks
        // the database rather than the flag, so this is reachable and has to be correct.
        read(() -> activation.activate("CAN"));
        Country canada = countries.findByIsoCode("CAN").orElseThrow();
        read(() -> {
            canada.setState(CountryState.SIMULATED);
            countries.save(canada);
            return null;
        });
        int clubs = countClubs("CAN");

        CountryActivationService.Result result = read(() -> activation.activate("CAN"));

        assertTrue(result.alreadyBuilt());
        assertEquals(CountryState.ACTIVE, result.state(),
                "a country holding a pyramid is still marked SIMULATED, so nothing will read it as played");
        assertEquals(clubs, countClubs("CAN"), "the healing pass built a second pyramid");
    }

    // --- helpers ---

    private int countClubs(String iso) {
        return read(() -> (int) competitions.findByCountryIsoCodeAndType(iso, CompetitionType.LEAGUE).stream()
                .mapToInt(division -> teams.findByCompetitionId(division.getId()).size())
                .sum());
    }

    /** Every club of a country that sits in a league division, read inside a session. */
    private List<Team> clubsOf(String iso) {
        return read(() -> teams.findAll().stream()
                .filter(team -> team.getCountry() != null && iso.equals(team.getCountry().getIsoCode()))
                .filter(team -> team.getCompetition() != null
                        && team.getCompetition().getType() == CompetitionType.LEAGUE)
                .toList());
    }

    private long countFixtures(String iso) {
        int seasonYear = seasons.getActiveSeasonYear();
        return competitions.findByCountryIsoCodeAndType(iso, CompetitionType.LEAGUE).stream()
                .mapToLong(division -> countFixturesOf(division, seasonYear))
                .sum();
    }

    private long countFixturesOf(Competition division, int seasonYear) {
        return fixtures
                .findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(division.getId(), seasonYear)
                .size();
    }

    /** The mean of the eight football skills over every player in one tier of a country. */
    private double averageForTier(String iso, int tier) {
        double[] result = read(() -> {
        double total = 0.0;
        int count = 0;
        for (Competition division : competitions.findByCountryIsoCodeAndType(iso, CompetitionType.LEAGUE)) {
            if (division.getTier() == null || division.getTier() != tier) {
                continue;
            }
            for (Team club : teams.findByCompetitionId(division.getId())) {
                for (Player player : players.findByTeamId(club.getId())) {
                    int sum = 0;
                    for (var skill : BotLeagueStandard.FOOTBALL_SKILLS.keySet()) {
                        sum += player.getSkills().visibleInt(skill);
                    }
                    total += sum / (double) BotLeagueStandard.FOOTBALL_SKILLS.size();
                    count++;
                }
            }
        }
        assertTrue(count > 0, "tier " + tier + " has no players in it");
        double average = Math.round(total / count * 100.0) / 100.0;
        return new double[]{average};
        });
        return result[0];
    }
}
