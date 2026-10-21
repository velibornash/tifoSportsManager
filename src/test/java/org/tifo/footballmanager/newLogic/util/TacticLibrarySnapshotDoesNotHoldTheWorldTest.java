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
 * back the very object it was given, so a test that stored one string and pointed several rows at it
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
 *
 * <p>And a third thing, which is the owner's own rule: a reset keeps <b>only the two managers'
 * clubs</b> (owner, 2026-10-21: "taktika default treba da prezivi reset SAMO za Omladinac ... jer
 * se timovi menjaju posle svakog reseta (sem omladinca i sremca)"), so the snapshot reads only
 * those two.
 */
class TacticLibrarySnapshotDoesNotHoldTheWorldTest extends BaseTest {

    /**
     * The clubs a reset keeps: the two managers'.
     *
     * <p>They are the two whose identity survives a reset - the rebuild draws the other fourteen
     * thousand again, with new names - so they are the only two whose tactics are worth carrying
     * across one.
     */
    private static final List<String> CLUBS_A_RESET_KEEPS = List.of("OFK Omladinac", "Sremac Berkasovo");

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

        // **A separate copy of the same rules for every club.** That is what the world holds:
        // PostgreSQL decodes every row into its own string, so fourteen thousand rows are
        // fourteen thousand strings of a hundred and thirty-two thousand characters each. A
        // test that stored one string and pointed several rows at it would hand the snapshot one
        // object to share, and would measure nothing.
        for (String name : CLUBS_A_RESET_KEEPS) {
            saveTactic(aClubNamed(country, name), "4-4-2", true, new String(payload.toCharArray()));
        }

        // The test's own copy of the data is not what the snapshot must hold. A reset reads the
        // library out of the database, so the session is emptied first and the rows are read again
        // from there.
        entityManager.clear();

        List<DatabaseInitializer.TacticLibrarySnapshot> snapshot = initializer.snapshotTacticLibrary();

        assertEquals(CLUBS_A_RESET_KEEPS.size(), snapshot.size(),
                "the snapshot did not carry the two managers' libraries, so a reset would lose them");

        assertEquals(0, managedEntities(),
                "the snapshot left " + managedEntities() + " entities in the session. It read the library "
                        + "as entities, and with 132,000 characters of rules on each of fourteen thousand "
                        + "rows that is about two gigabytes of strings in a heap of three thousand two "
                        + "hundred - which is the out-of-memory error that took the app down.");
    }

    @Test
    @Transactional
    @DisplayName("one payload carried by every club is kept once, not once per club")
    void theSnapshotKeepsOneCopyOfThePayload() {
        String payload = onePayload();
        Country country = aCountry();

        for (String name : CLUBS_A_RESET_KEEPS) {
            saveTactic(aClubNamed(country, name), "4-4-2", true, new String(payload.toCharArray()));
        }

        List<DatabaseInitializer.TacticLibrarySnapshot> snapshot = initializer.snapshotTacticLibrary();

        assertTrue(snapshot.size() >= 2, "the test needs two clubs to compare");
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
    @DisplayName("the snapshot carries only the clubs a reset keeps")
    void theSnapshotCarriesOnlyTheClubsAResetKeeps() {
        // A club the rebuild will draw again, under a new name. Its tactics are the previous
        // world's, and carrying them across the reset is two gigabytes of reading to be thrown away.
        Country country = aCountry();
        saveTactic(aClub(country, "Rebuilt Away"), "4-4-2", true, onePayload());

        List<DatabaseInitializer.TacticLibrarySnapshot> snapshot = initializer.snapshotTacticLibrary();

        for (DatabaseInitializer.TacticLibrarySnapshot club : snapshot) {
            assertTrue(CLUBS_A_RESET_KEEPS.contains(club.teamName),
                    "the snapshot carried '" + club.teamName + "', which a reset does not keep. Only the "
                            + "two managers' clubs keep their identity - the rebuild draws the rest "
                            + "again with new names - so only theirs are worth carrying across.");
        }
    }

    @Test
    @Transactional
    @DisplayName("every club's tactic still comes back whole")
    void theLibraryStillRoundTrips() {
        String payload = onePayload();
        Team club = aClubNamed(aCountry(), "OFK Omladinac");
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

    /**
     * A club with one of the names a reset keeps.
     *
     * <p>The name is the whole point: the snapshot reads the clubs by name, because a reset empties
     * the world and the rebuild draws the clubs again. So the test's club has to carry the name the
     * reset would give it.
     */
    private Team aClubNamed(Country country, String name) {
        Team existing = teams.findByName(name).orElse(null);
        if (existing != null) {
            return existing;
        }
        Team team = new Team();
        team.setName(name);
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
