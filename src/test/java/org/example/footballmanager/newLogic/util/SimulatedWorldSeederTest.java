package org.example.footballmanager.newLogic.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.example.footballmanager.newLogic.model.Competition;
import org.example.footballmanager.newLogic.model.CompetitionEntry;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CompetitionEntryRepository;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * A simulated country is in the world without being a season's worth of database.
 *
 * <p>The distinction these pin down is the one that got confused twice: a country being in the world
 * means it has clubs and a position, and <b>not</b> that it has players or a schedule. 46 countries at
 * 25 players a club is 370k rows of nobody's squad.
 */
@SpringBootTest
@ActiveProfiles("test")
// **No @Transactional, and that is deliberate.** SimulatedWorldSeeder.seed is REQUIRES_NEW — one
// transaction per country, so the static world commits as it is built and a failure costs one country
// instead of all forty-six. A REQUIRES_NEW boundary cannot see another transaction's uncommitted rows,
// so a @Transactional fixture country was invisible to the seeder: the test failed on a foreign key
// from a Competition pointing at a Country that had never been committed. Each test uses its own ISO
// code, so committed fixtures do not collide.
class SimulatedWorldSeederTest {

    @Autowired private SimulatedWorldSeeder seeder;
    @Autowired private PyramidBuilder pyramids;
    @Autowired private CountryRepository countries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionEntryRepository entries;
    @Autowired private TeamRepository teams;
    @Autowired private org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository seasonCompetitions;
    @Autowired private PlayerRepository players;
    @Autowired private MatchFixtureRepository fixtures;

    @Autowired private org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    @DisplayName("a simulated country gets its whole pyramid, its ratings and a standing table")
    void aSimulatedCountryIsInTheWorld() {
        Country country = countries.save(country("ZZ Simulated", "ZZS"));

        seeder.seed(country, 1);

        List<Competition> divisions = competitions.findByCountryIsoCodeAndType("ZZS", CompetitionType.LEAGUE);
        assertEquals(31, divisions.size(), "a country is not a country without all thirty-one divisions");

        // 1 + 2 + 4 + 8 + 16 divisions of ten clubs.
        assertEquals(310, clubsInIso("ZZS").size(),
                "the club count is 10 per division and a missing division is a silent hole in the cup field");

        Competition premier = divisions.stream()
                .filter(c -> c.getTier() == 1)
                .findFirst()
                .orElseThrow();
        assertEquals(10, standingRows(premier), "a division with no table cannot be read by the cup draw");
    }

    @Test
    @DisplayName("a simulated country has no players and no fixtures, because it does not play")
    void aSimulatedCountryDoesNotPlay() {
        Country country = countries.save(country("ZZ Quiet", "ZZQ"));

        seeder.seed(country, 1);

        List<Team> clubs = clubsInIso("ZZQ");
        assertEquals(310, clubs.size());
        assertEquals(0, players.findAll().stream()
                        .filter(p -> clubs.stream().anyMatch(c -> c.getId().equals(p.getTeam().getId())))
                        .count(),
                "a simulated country generated squads — 370k rows of players who never kick a ball");

        assertEquals(0, fixtures.findAll().stream()
                        .filter(f -> clubs.stream().anyMatch(c -> c.getId().equals(f.getHomeTeam().getId())))
                        .count(),
                "a league that will not be played was given a fixture list");
    }

    @Test
    @DisplayName("the tiers are rated 12, 11, 10, 9, 8 and stay in that order")
    void theTiersAreRatedAndOrdered() {
        assertEquals(12, PyramidBuilder.tierSkill(1));
        assertEquals(11, PyramidBuilder.tierSkill(2));
        assertEquals(10, PyramidBuilder.tierSkill(3));
        assertEquals(9, PyramidBuilder.tierSkill(4));
        assertEquals(8, PyramidBuilder.tierSkill(5));
        assertEquals(10, PyramidBuilder.U21_SKILL);

        Country country = countries.save(country("ZZ Rated", "ZZR"));
        seeder.seed(country, 1);

        for (int tier = 1; tier <= 5; tier++) {
            double tierReputation = tierReputation(tier);
            assertTrue(tierReputation > 0, "tier " + tier + " clubs have no rating at all");
            if (tier < 5) {
                assertTrue(tierReputation > tierReputation(tier + 1),
                        "tier " + tier + " is not rated above tier " + (tier + 1)
                                + " — with no players, reputation is the whole of a club's quality");
            }
        }
    }

    @Test
    @DisplayName("an active country is refused, because it already simulates for itself")
    void anActiveCountryIsRefused() {
        Country country = countries.save(country("ZZ Active", "ZAA"));
        country.setState(CountryState.ACTIVE);
        countries.save(country);

        PyramidBuilder.Result result = seeder.seed(country, 1);

        assertTrue(result.alreadyBuilt(), "an active country was handed a static table it does not need");
        assertEquals(0, competitions.findByCountryIsoCodeAndType("ZAA", CompetitionType.LEAGUE).size(),
                "an active country got a second, static pyramid — it would now have two of everything");
    }

    @Test
    @DisplayName("seeding twice is a no-op, because this runs on every boot")
    void seedingTwiceIsANoOp() {
        Country country = countries.save(country("ZZ Twice", "ZZT"));

        seeder.seed(country, 1);
        int after = competitions.findByCountryIsoCodeAndType("ZZT", CompetitionType.LEAGUE).size();
        PyramidBuilder.Result second = seeder.seed(country, 1);

        assertEquals(after, competitions.findByCountryIsoCodeAndType("ZZT", CompetitionType.LEAGUE).size(),
                "seeding twice duplicated the pyramid");
        assertTrue(second.alreadyBuilt());
    }

    @Test
    @DisplayName("a batch seeds the simulated countries and leaves the active one alone")
    void aBatchSkipsTheActiveCountries() {
        Country bulkA = countries.save(country("ZZ Bulk A", "ZBA"));
        Country bulkB = countries.save(country("ZZ Bulk B", "ZBB"));
        Country active = countries.save(country("ZZ Bulk Active", "ZBV"));
        active.setState(CountryState.ACTIVE);
        countries.save(active);

        SimulatedWorldSeeder.Summary summary = seeder.seedAll(
                List.of(bulkA, bulkB, active), 1);

        assertEquals(31, competitions.findByCountryIsoCodeAndType("ZBA", CompetitionType.LEAGUE).size());
        assertEquals(31, competitions.findByCountryIsoCodeAndType("ZBB", CompetitionType.LEAGUE).size());
        assertEquals(0, competitions.findByCountryIsoCodeAndType("ZBV", CompetitionType.LEAGUE).size(),
                "seedAllSimulated reached into an active country");
        assertEquals(2, summary.countries(), "the active country was counted as seeded");
        assertTrue(summary.divisions() >= 62, "both simulated countries were not seeded");
        assertNotNull(summary.countries());
    }

    /** How many clubs are in a country, by name — there is no "containing" finder on the repository. */
    private List<Team> clubsInIso(String iso) {
        return teams.findByNameStartingWithIgnoreCase(iso);
    }

    private int standingRows(Competition league) {
        return seasonCompetitions.findByCompetitionOrderBySeasonYearDesc(league).stream()
                .map(sc -> entries.findBySeasonCompetition(sc).size())
                .findFirst()
                .orElse(0);
    }

    /**
     * Reads the clubs of one tier and their reputations.
     *
     * <p>Inside its own transaction, because this class is deliberately not {@code @Transactional} (the
     * seeder commits per country) and {@code Team.competition} is a lazy proxy — walking it outside a
     * session is a LazyInitializationException, not a wrong answer.
     */
    private double tierReputation(int tier) {
        return transactions.execute(status -> {
        List<Team> clubs = teams.findByNameStartingWithIgnoreCase("ZZR").stream()
                .filter(t -> t.getCompetition() != null
                        && t.getCompetition().getTier() != null
                        && t.getCompetition().getTier() == tier)
                .toList();
        assertTrue(!clubs.isEmpty(), "no clubs found for tier " + tier);
        double min = clubs.stream().mapToDouble(t -> t.getReputation() == null ? 0 : t.getReputation()).min().orElseThrow();
        double max = clubs.stream().mapToDouble(t -> t.getReputation() == null ? 0 : t.getReputation()).max().orElseThrow();
        return max;
        });
    }

    private Country country(String name, String iso) {
        Country country = new Country();
        country.setName(name);
        country.setIsoCode(iso);
        country.setState(CountryState.SIMULATED);
        return country;
    }
}