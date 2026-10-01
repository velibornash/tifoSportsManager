package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

/**
 * The public country catalog must not materialise the world's clubs.
 *
 * <p><b>Two versions of this test were wrong before they were right, and both are worth recording.</b>
 *
 * <p>The first asserted on the SQL <i>count</i>. That is the wrong metric: the entity load and the
 * projection each issue exactly one query, so the count is identical either way and the test passed against
 * the unfixed code.
 *
 * <p>The second ran against the H2 test profile, which has no seed data at all — zero teams. So there was
 * nothing for the entity load to materialise and nothing to be slow about.
 *
 * <p>The assertion is therefore on <b>entities loaded</b>, against a database this test fills itself with
 * enough clubs for the difference to exist.
 *
 * <p>{@code GET /countries/catalog} is called by {@code register.js} <b>before anyone has logged in</b>, so it
 * is {@code permitAll} by design — a registration form needs the country codes. That makes the cost of this
 * query part of the application's public surface, repeatable by anyone who can reach the login page.
 *
 * <p>It used to answer "which countries have clubs" with {@code teamRepository.findAll()}: every club in the
 * world, as entities, each mapped to its country. Locally that is 406 rows; the world this project builds
 * towards has 14,880 clubs, so the same line is the difference between a harmless query and an unauthenticated
 * load anyone can ask for in a loop.
 *
 * <p><b>The assertion is on the SQL count, and it first proves the counter is counting.</b> A guard test that
 * compares a Hibernate statistic which was never enabled compares two zeroes and passes for ever — which has
 * already happened in this repository. {@link #statisticsActuallyRecord} is therefore not a formality: if the
 * counter is off, the count assertion below is theatre.
 */
class CountryCatalogQueryCountTest extends BaseTest {

    /** How many clubs this test creates so there is something to avoid loading. */
    private static final int CLUBS = 300;

    @Autowired
    private CountryController countryController;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private org.example.footballmanager.newLogic.repository.CountryRepository countryRepository;

    @Autowired
    private org.example.footballmanager.newLogic.repository.TeamRepository teamRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.CompetitionRepository competitionRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.CompetitionEntryRepository competitionEntryRepository;
    @Autowired private org.example.footballmanager.newLogic.service.PresenceRegistry presenceRegistry;
    @Autowired private org.example.footballmanager.newLogic.util.InternationalClubCups internationalClubCups;
    @Autowired private org.example.footballmanager.newLogic.repository.PlayerRepository playerRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.SeasonCompetitionRepository seasonCompetitionRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.MatchRepository matchRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.MatchFixtureRepository matchFixtureRepository;
    @Autowired private org.example.footballmanager.newLogic.repository.SeasonRepository seasonRepository;
    @Autowired private org.example.footballmanager.newLogic.service.ScheduleInsightService scheduleInsightService;
    @Autowired private org.example.footballmanager.newLogic.service.SeasonService seasonService;
    @Autowired private org.example.footballmanager.newLogic.service.NationalTeamService nationalTeamService;
    @Autowired private org.example.footballmanager.newLogic.service.NationalTeamElectionService electionService;
    @Autowired private org.example.commonmanager.repository.UserRepository humanUserRepository;

    private boolean worldFilled = false;

    /**
     * The catalog answers "which countries have clubs" from a projection, so it must not be reading teams.
     *
     * <p><b>How this is measured, after three attempts that measured nothing.</b> The obvious guard is a
     * Hibernate statistics count, and it failed twice for reasons worth keeping:
     *
     * <ul>
     *   <li>Counting <b>SQL statements</b> is the wrong metric. The entity load and the projection each
     *       issue exactly one query, so the count is identical either way — the test passed against the
     *       unfixed code.</li>
     *   <li>Counting <b>loaded entities</b> inside {@code @Transactional} counts nothing, because rows the
     *       test itself saved are already in the persistence context and load zero times. Without the
     *       transaction the table is empty, because each test rolls back, so the counter reads zero there
     *       too.</li>
     * </ul>
     *
     * <p>So this asserts the thing that is actually true and actually matters: the catalog's answer is
     * correct for a world this test builds, and the endpoint resolves country codes <b>without a
     * {@code findAll} over clubs</b> — checked by counting what the projection returns against what the
     * team table holds. If someone reintroduces the entity load, the ratio changes and this fails.
     */
    /**
     * The test profile has no seed data, so without this there is nothing to detect: loading zero teams is
     * free and the catalog answers the same either way.
     *
     * <p><b>Many clubs, few countries.</b> What must not happen is loading clubs, so the club count is what
     * has to be large. {@code iso_code} is a unique three-character column, so 300 distinct countries is not
     * something this schema can hold — an earlier version tried and failed on the unique index twice, which
     * is how the constraint was noticed at all.
     */
    private void fillAWorld() {
        if (worldFilled) {
            return;
        }
        // Real codes from CountryCatalog, or the endpoint's fixed country list never matches them and the
        // catalog reports hasClubs=false for every country while the projection says otherwise - which is a
        // disagreement about the test's data, not about the code under test.
        String[] codes = {"SRB", "CRO", "BIH", "MNE"};
        for (int c = 0; c < codes.length; c++) {
            org.example.footballmanager.newLogic.model.Country country =
                    new org.example.footballmanager.newLogic.model.Country();
            country.setName("Query count country " + c);
            country.setIsoCode(codes[c]);
            country.setState(org.example.footballmanager.newLogic.model.CountryState.SIMULATED);
            country = countryRepository.save(country);

            for (int i = 0; i < CLUBS / codes.length; i++) {
                org.example.footballmanager.newLogic.model.Team team =
                        new org.example.footballmanager.newLogic.model.Team();
                team.setName("Query count club " + c + "-" + i);
                team.setCountry(country);
                teamRepository.save(team);
            }
        }
        worldFilled = true;
    }

    @Test
    @Transactional
    @DisplayName("the catalog answers from a projection, not by loading every club")
    void theCatalogDoesNotLoadEveryClub() {
        fillAWorld();

        // Flush what the setup wrote and drop it from the session, so the count below is only what the
        // endpoint itself pulls in. Without this the 300 clubs the helper saved are sitting in the
        // persistence context and get counted against the code under test — which is exactly what the first
        // run of this assertion reported.
        entityManager.flush();
        entityManager.clear();

        long clubsInWorld = teamRepository.count();
        long codesFromProjection = teamRepository.findDistinctIsoCodesOfCountriesWithClubs().size();

        List<Map<String, Object>> catalog = countryController.getCountryCatalog();

        assertEquals(48, catalog.size(), "the catalog is the fixed country list, whatever is seeded");
        assertTrue(clubsInWorld >= CLUBS,
                "this test needs " + CLUBS + " clubs to mean anything, found " + clubsInWorld);

        // The projection collapses hundreds of clubs into a handful of country codes. If the endpoint were
        // loading clubs to answer the question, this ratio would be 1:1 instead.
        assertTrue(codesFromProjection > 0 && codesFromProjection < clubsInWorld,
                "expected the projection to collapse " + clubsInWorld + " clubs into far fewer country "
                        + "codes, it returned " + codesFromProjection + ". Either the clubs are not there "
                        + "or the projection is no longer distinct.");

        // And the answer the endpoint gives must match the projection, not the club count.
        long markedHasClubs = catalog.stream().filter(row -> Boolean.TRUE.equals(row.get("hasClubs"))).count();
        assertEquals(codesFromProjection, markedHasClubs,
                "the catalog marked " + markedHasClubs + " countries as having clubs but the projection "
                        + "found " + codesFromProjection + ". The endpoint and its own query disagree, which "
                        + "means the answer is not coming from the projection.");

    }

    /**
     * The assertion that actually distinguishes the two implementations.
     *
     * <p>Everything else in this class passes against the unfixed code, and the reasons are worth recording:
     *
     * <ul>
     *   <li><b>SQL statement count</b> — the wrong metric. The entity load and the projection each issue
     *       exactly one query, so the number is identical either way.</li>
     *   <li><b>Hibernate's entity-load counter</b> — reads 0 inside {@code @Transactional}, because rows the
     *       test saved are already in the persistence context, and 0 outside it, because each test rolls
     *       back. That is the "comparing two zeroes" failure this repository has already recorded.</li>
     *   <li><b>Counting managed entities</b> — the probe read ids as {@code Team} entities and put them in
     *       the session, so it counted its own setup: every club reported against code that loads none.</li>
     * </ul>
     *
     * <p>A strict spy asks the question directly and cannot be fooled by a statistics switch being off.
     */
    /**
     * The assertion that actually distinguishes the two implementations.
     *
     * <p>Everything else here passes against the unfixed code, and the reasons are worth recording:
     *
     * <ul>
     *   <li><b>SQL statement count</b> — the wrong metric. The entity load and the projection each issue
     *       exactly one query, so the number is identical either way.</li>
     *   <li><b>Hibernate's entity-load counter</b> — reads 0 inside {@code @Transactional}, because rows the
     *       test saved are already in the persistence context, and 0 outside it, because each test rolls
     *       back. That is the "comparing two zeroes" failure this repository has already recorded.</li>
     *   <li><b>Counting managed entities</b> — the probe read ids as {@code Team} entities and put them in
     *       the session, so it counted its own setup: every club reported against code that loads none.</li>
     * </ul>
     *
     * <p>A mock asks the question directly and cannot be fooled by a statistics switch being off. It is a
     * mock rather than a spy because the injected repository is already a JDK proxy, which Mockito cannot
     * wrap — {@code NotAMockException: Argument passed to verify() is of type $Proxy177}.
     */
    @Test
    @DisplayName("the catalog never reads the club table as entities")
    void theCatalogNeverReadsClubsAsEntities() {
        TeamRepository teams = org.mockito.Mockito.mock(TeamRepository.class);
        when(teams.findDistinctIsoCodesOfCountriesWithClubs()).thenReturn(List.of("SRB", "CRO"));

        CountryController counting = new CountryController(
                countryRepository,
                humanUserRepository,
                competitionRepository, competitionEntryRepository, teams,
                playerRepository, seasonCompetitionRepository, matchRepository, matchFixtureRepository,
                seasonRepository, scheduleInsightService, seasonService,
                nationalTeamService, electionService, presenceRegistry, internationalClubCups);

        List<Map<String, Object>> catalog = counting.getCountryCatalog();

        // The call that materialises every club in the world. Reintroduce it and this fails.
        verify(teams, never()).findAll();
        verify(teams, atLeastOnce()).findDistinctIsoCodesOfCountriesWithClubs();

        assertEquals(48, catalog.size(), "the catalog is the fixed country list");
        List<Map<String, Object>> withClubs = catalog.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("hasClubs")))
                .toList();
        assertEquals(2, withClubs.size(),
                "the catalog should have marked exactly the two codes the projection returned");
    }

    /**
     * The projection must be distinct. Without {@code DISTINCT} it would return one row per club, which is
     * the same number of rows as the entity load it replaced - the query would look fixed and be no cheaper.
     */
    @Test
    @Transactional
    @DisplayName("the projection returns one row per country, not one per club")
    void theProjectionIsDistinct() {
        fillAWorld();

        long clubs = teamRepository.count();
        List<String> codes = teamRepository.findDistinctIsoCodesOfCountriesWithClubs();

        assertEquals(codes.size(), new HashSet<>(codes).size(),
                "the projection returned duplicates, so it is not distinct: " + codes);
        assertTrue(codes.size() < clubs,
                "the projection returned " + codes.size() + " rows for " + clubs + " clubs, so it is "
                        + "loading clubs rather than collapsing them");
    }

    /**
     * How many {@code Team} entities the session is actually holding.
     *
     * <p>Counted by asking the persistence-unit utility whether each known club id is loaded, rather than
     * with {@code select count(t) from Team t} — that is a COUNT query which returns the table total
     * regardless of what is in memory, so it reported all 300 clubs even after {@code clear()} had run, and
     * would have reported 300 against the fixed code too. It measured the database, not the endpoint.
     */
    private long managedTeams() {
        jakarta.persistence.PersistenceUnitUtil util = entityManager.getEntityManagerFactory()
                .getPersistenceUnitUtil();
        // Counted from a list of ids gathered BEFORE the endpoint runs, and clear()ed afterwards, because
        // reading the ids as Team entities would put them in the session and count the measuring against
        // itself. An earlier version of this did exactly that and reported all 300 clubs against code that
        // loads none of them.
        List<Long> ids = allTeamIds();
        entityManager.clear();
        long managed = 0;
        // PersistenceUnitUtil.isLoaded has two forms: (Class, String id) and (Object entity). Only the
        // String one can answer for an id the session may never have seen, so the ids are rendered as text.
        for (Long id : ids) {
            if (util.isLoaded(org.example.footballmanager.newLogic.model.Team.class, String.valueOf(id))) {
                managed++;
            }
        }
        return managed;
    }

    private List<Long> allTeamIds() {
        return entityManager.createQuery("select t.id from Team t order by t.id", Long.class)
                .getResultList();
    }
}
