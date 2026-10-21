package org.tifo.footballmanager.newLogic.util;

import org.tifo.footballmanager.BaseTest;
import org.tifo.footballmanager.newLogic.model.CompetitionTeamType;
import org.tifo.footballmanager.newLogic.model.Country;
import org.tifo.footballmanager.newLogic.model.CountryState;
import org.tifo.footballmanager.newLogic.model.Team;
import org.tifo.footballmanager.newLogic.model.tactics.Tactic;
import org.tifo.footballmanager.newLogic.repository.CountryRepository;
import org.tifo.footballmanager.newLogic.repository.TeamRepository;
import org.tifo.footballmanager.newLogic.repository.TacticRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tactic library's snapshot must not hold the world in the heap.
 *
 * <p><b>The defect this holds.</b> Owner, 2026-10-21: "reset db na oracle vratio Database operation
 * failed. Error: Server returned HTML instead of JSON: 504." The server's log said what it was:
 *
 * <pre>
 * Clearing football data (preserving owner account + tactics profiles)...
 * java.lang.OutOfMemoryError: Java heap space
 *   at ...DatabaseInitializer.snapshotTacticLibrary(DatabaseInitializer.java:1017)
 * </pre>
 *
 * <p>A tactic's rules are 132,000 characters of JSON, the world holds <b>14,976</b> of them, and the
 * snapshot held every one at once — about 2 GB of strings in a 3,200 MB heap. The app died, the JVM
 * exited ({@code -XX:+ExitOnOutOfMemoryError}), and nginx answered the browser with a 504.
 *
 * <p><b>And the world is not what it looks like.</b> Measured on the server: 14,976 rows and
 * <b>three</b> distinct payloads. The library is one tactic replicated fourteen thousand times.
 *
 * <p><b>What is measured here, and why not the heap.</b> The heap cannot be measured on H2: it hands
 * back the very object it was given, so a test that stored one string and pointed sixteen rows at it
 * would measure nothing at all. What differs between the broken snapshot and the fixed one is
 * <em>what is held</em>, and that is measured two ways:
 *
 * <ul>
 *   <li>the <b>session</b> — the broken snapshot read the library as entities, so fourteen thousand
 *       rows of a hundred and thirty-two thousand characters sat in the persistence context; the
 *       fixed one reads a projection and leaves the session empty;
 *   <li>the <b>payload</b> — the broken snapshot held one string per row; the fixed one keeps the
 *       identical payloads once, which is what makes the whole library one copy instead of fourteen
 *       thousand.
 * </ul>
 */
class TacticLibrarySnapshotDoesNotHoldTheWorldTest extends BaseTest {

    /** How many clubs carry the same payload. The owner's world has 14,976. */
    private static final int CLUBS = 16;

    /** How big the payload is. The owner's is 132,000 characters; a megabyte is not a rounding error. */
    private static final int PAYLOAD_CHARS = 1_000_000;

    @Autowired private DatabaseInitializer initializer;
    @Autowired private TacticRepository tactics;
    @Autowired private TeamRepository teams;
    @Autowired private CountryRepository countries;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("the snapshot does not put the library in the session")
    void theSnapshotDoesNotLoadTheLibraryIntoTheSession() {
        String payload = onePayload();
        Country country = aCountry();

        for (int i = 0; i < CLUBS; i++) {
            // **A separate copy of the same rules for every club.** That is what the world holds:
            // PostgreSQL decodes every row into its own string, so fourteen thousand rows are
            // fourteen thousand strings of a hundred and thirty-two thousand characters each. A
            // test that stored one string and pointed sixteen rows at it would hand the snapshot one
            // object to share, and would measure nothing.
            saveTactic(aClub(country, "Snapshot " + i), "4-4-2", true, new String(payload.toCharArray()));
        }

        // The test's own copy of the data is not what the snapshot must hold. A reset reads the
        // library out of the database, so the session is emptied first and the rows are read again
        // from there.
        entityManager.clear();

        List<DatabaseInitializer.TacticLibrarySnapshot> snapshot = initializer.snapshotTacticLibrary();

        assertEquals(CLUBS, snapshot.size(),
                "the snapshot did not carry every club's library, so a reset would lose a club's tactics");

        assertEquals(0, managedEntities(),
                "the snapshot left " + managedEntities() + " entities in the session. It read the library "
                        + "as entities, and with 132,000 characters of rules on each of fourteen thousand "
                        + "rows that is about two gigabytes of strings in a heap of three thousand two "
                        + "hundred — which is the out-of-memory error that took the app down.");
    }

    @Test
    @Transactional
    @DisplayName("one payload carried by every club is kept once, not once per club")
    void theSnapshotKeepsOneCopyOfThePayload() {
        String payload = onePayload();
        Country country = aCountry();

        for (int i = 0; i < CLUBS; i++) {
            saveTactic(aClub(country, "Payload " + i), "4-4-2", true, new String(payload.toCharArray()));
        }

        List<DatabaseInitializer.TacticLibrarySnapshot> snapshot = initializer.snapshotTacticLibrary();

        String first = snapshot.get(0).tactics.get(0).rulesJson;
        for (int i = 1; i < snapshot.size(); i++) {
            // `assertSame` would print two megabytes of rules into the report when it fails, so the
            // identity is compared by hand and the message says what is wrong.
            assertTrue(first == snapshot.get(i).tactics.get(0).rulesJson,
                    "the snapshot kept a separate copy of the same rules for club " + i + ". The world "
                            + "holds one tactic replicated fourteen thousand times, and the snapshot has to "
                            + "hold it once - fourteen thousand copies of it is the two gigabytes that took "
                            + "the server down.");
        }
    }

    @Test
    @Transactional
    @DisplayName("every club's tactic still comes back whole")
    void theLibraryStillRoundTrips() {
        String payload = onePayload();
        Team club = aClub(aCountry(), "Snapshot Round Trip");
        saveTactic(club, "Owner's own", true, payload);

        var snapshot = initializer.snapshotTacticLibrary();
        tactics.deleteAll();
        initializer.restoreTacticLibrary(snapshot, tactics, teams);

        List<Tactic> restored = tactics.findByTeamIdOrderByIdAsc(club.getId());
        assertEquals(1, restored.size(), "the club's tactic did not come back");
        assertEquals("Owner's own", restored.get(0).getName());
        assertEquals(payload, restored.get(0).getRulesJson(),
                "the rules came back different, so the editor would open a blank pitch");
    }

    // --- helpers ----------------------------------------------------------

    /** One megabyte of the same rules, the shape the world carries: a grid of slot and cell. */
    private String onePayload() {
        String entry = "{\"slotKey\":\"GK\",\"ballStateKey\":\"CELL_0_0\","
                + "\"possessionContext\":\"WE_HAVE_BALL\",\"targetCellKey\":\"CELL_0_2\"},";
        return "[" + entry.repeat(PAYLOAD_CHARS / entry.length()) + "{}]";
    }

    /** How many entities the session is holding. */
    private int managedEntities() {
        Object delegate = entityManager.getDelegate();
        if (delegate instanceof org.hibernate.engine.spi.SessionImplementor session) {
            return session.getPersistenceContext().getNumberOfManagedEntities();
        }
        throw new IllegalStateException("The session is a " + delegate.getClass().getName()
                + " and this guard cannot count what it holds, so it cannot guard anything.");
    }

    /** A throwaway country, following TacticLibraryRestoreTest so the two agree on what one is. */
    private Country aCountry() {
        Country country = new Country();
        country.setName("ZZ Snapshot " + UUID.randomUUID());
        country.setIsoCode("R" + UUID.randomUUID().toString().substring(0, 2).toUpperCase());
        country.setState(CountryState.SIMULATED);
        return countries.save(country);
    }

    /** A throwaway club, in the country above. */
    private Team aClub(Country country, String label) {
        Team team = new Team();
        team.setName("ZZ Snapshot club " + label + " " + UUID.randomUUID());
        team.setCountry(country);
        team.setType(CompetitionTeamType.CLUB);
        return teams.save(team);
    }

    private void saveTactic(Team club, String name, boolean isDefault, String rulesJson) {
        Tactic tactic = new Tactic();
        tactic.setTeam(club);
        tactic.setName(name);
        tactic.setFormation("4-4-2");
        tactic.setStyle(isDefault ? "BALANCED" : "Balanced");
        tactic.setRulesJson(rulesJson);
        tactic.setVersion(5L);
        tactic.setDefaultTactic(isDefault);
        tactics.save(tactic);
    }
}
