package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.controller.ControllerAuthFixture;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code User} and {@code Team} were joined by a string, and this is the test that says they no longer are.
 *
 * <p>The old contract was {@code User.cTeam.name == Team.name}. Every reader re-derived that join
 * independently, and it cost four defects that were all the same mistake in different costume — treating
 * a {@code CTeam} id as a {@code Team} id. {@code User.footballTeam} is now a real foreign key, read by
 * {@link ClubOwnershipLinker}, with the name-join kept only as a backfill.
 *
 * <p><b>The reverse direction is the new part and the reason this class exists.</b> Nothing could answer
 * "who manages this club" at all: there was no {@code findByCTeam}, no {@code findByManagedTeam}, and
 * {@code UserRepository.findDistinctManagedTeamIds} selected CTeam ids and returned no identity. A club
 * profile asking that question had nothing to ask with.
 *
 * <p>Every test here runs against a real database, because the defects were all about id spaces and a
 * mock cannot have one. {@link ControllerAuthFixture} is imported for its country-drawing retry: a
 * shared H2 accumulates rows and {@code country.iso_code} has a unique index, so a fixed code collides
 * eventually and the failure looks like somebody else's bug.
 */
@Import(ControllerAuthFixture.class)
class ClubOwnershipLinkerTest extends BaseTest {

    @Autowired
    ClubOwnershipLinker linker;

    @Autowired
    UserRepository users;

    @Autowired
    TeamRepository teams;

    @Autowired
    CSTeamRepository csTeams;

    @Autowired
    CountryRepository countries;

    @Autowired
    ControllerAuthFixture auth;

    // ── The forward direction ──────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("the foreign key is preferred, so a stale club name cannot decide who owns what")
    void theForeignKeyWins() {
        Team realClub = aClub("Real club");
        Team otherClub = aClub("Other club");

        User manager = aUser();
        manager.setCTeam(csTeams.save(aCTeam("Some other club entirely")));
        // The legacy name points at nothing. The id is what must be honoured.
        manager.setFootballTeam(realClub);
        users.save(manager);

        assertEquals(realClub.getId(), linker.clubIdOf(manager),
                "a stale name in the legacy CTeam field overrode the real foreign key");
    }

    @Test
    @Transactional
    @DisplayName("an account written before the column existed is repaired, not reported as clubless")
    void aLegacyRowIsBackfilledOnRead() {
        Team club = aClub("Legacy club");

        User manager = aUser();
        manager.setCTeam(csTeams.save(aCTeam(club.getName())));
        assertNull(manager.getFootballTeam(), "the fixture must start with no id, or nothing is being tested");

        Long resolved = linker.clubIdOf(manager);

        assertEquals(club.getId(), resolved, "a legacy account lost its club instead of being repaired");

        // And the repair is persisted, so the second call is a plain id read with no lookup at all.
        assertEquals(club.getId(), manager.getFootballTeam().getId(),
                "the backfill was computed and thrown away instead of written back");
    }

    @Test
    @Transactional
    @DisplayName("two clubs sharing a name resolve to the human-controlled one, and do not throw")
    void duplicateClubNamesAreResolvedNotThrown() {
        // TeamRepository.findByName returns Optional<Team> and therefore throws on this exact input.
        // Two clubs sharing a name is explicitly allowed, so every reader had to avoid it.
        String sharedName = "Duplicate name";
        Team ai = aClubNamed(sharedName);
        ai.setHumanControlled(false);
        teams.save(ai);

        Team human = aClubNamed(sharedName);
        human.setHumanControlled(true);
        teams.save(human);

        assertEquals(2, teams.findAllByNameIgnoreCase(sharedName).size(),
                "the fixture did not create a genuine duplicate, so the ambiguity was never tested");

        User manager = aUser();
        manager.setCTeam(csTeams.save(aCTeam("Duplicate name")));

        assertEquals(human.getId(), linker.clubIdOf(manager),
                "picked the AI club, or threw the way findByName would have");
    }

    @Test
    @Transactional
    @DisplayName("manages() answers ownership without the duplicate-name crash")
    void managesIsFalseForARivalAndTrueForTheOwnClub() {
        Team club = aClub("Owned club");
        User manager = aUser();
        manager.setCTeam(csTeams.save(aCTeam(club.getName())));

        assertTrue(linker.manages(manager, club.getId()));
        assertFalse(linker.manages(manager, club.getId() + 9999), "a club that is not yours is not yours");

        // The specific regression: this used to go through findByName and throw here.
        Team duplicate = aClubNamed(club.getName());
        duplicate.setHumanControlled(false);
        teams.save(duplicate);
        assertTrue(linker.manages(manager, club.getId()),
                "a same-named rival club made the ownership check throw instead of answering");
    }

    @Test
    @Transactional
    @DisplayName("an account with no club at all gets null, and every gate treats that as 'not mine'")
    void noClubFailsClosed() {
        User manager = aUser();

        assertNull(linker.clubOf(manager));
        assertNull(linker.clubIdOf(manager));
        assertFalse(linker.manages(manager, 1L));
        assertFalse(linker.manages(null, 1L));
        assertFalse(linker.manages(manager, null));
    }

    // ── The reverse direction: the part that did not exist before ──────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a club profile can name the manager, which no query could do before")
    void managerOfAnswersTheReverseDirection() {
        Team club = aClub("Managed club");
        User manager = aUser();
        manager.setDisplayName("The Manager");
        manager.setFootballTeam(club);
        users.save(manager);

        User found = linker.managerOf(club);

        assertNotNull(found, "the club profile has no way to say who runs this team");
        assertEquals(manager.getId(), found.getId());
    }

    @Test
    @Transactional
    @DisplayName("an AI club has no manager, and says so instead of guessing")
    void anAiClubHasNoManager() {
        Team ai = aClub("Bot club");
        ai.setHumanControlled(false);
        teams.save(ai);

        assertNull(linker.managerOf(ai), "an AI-run club reported a human manager");
    }

    @Test
    @Transactional
    @DisplayName("the reverse lookup does NOT guess from the name, unlike the forward one")
    void theReverseLookupDoesNotGuessFromTheName() {
        // The asymmetry is deliberate and this is the test that pins it. A forward lookup can be
        // repaired by writing one column. A reverse lookup cannot: matching on a name that two clubs
        // share means guessing which of them was meant, and a club profile naming the wrong manager
        // is worse than one naming none. Repair runs as an explicit backfill instead.
        Team club = aClub("Named club");
        club.setHumanControlled(true);
        teams.save(club);

        User manager = aUser();
        manager.setCTeam(csTeams.save(aCTeam(club.getName())));
        // No footballTeam set — the legacy name matches perfectly.

        assertNull(linker.managerOf(club),
                "a reverse lookup invented a match from a name; two same-named clubs would make it wrong silently");
    }

    @Test
    @Transactional
    @DisplayName("backfillAll repairs the table and is idempotent")
    void backfillIsIdempotent() {
        Team club = aClub("Bulk club");
        User legacy = aUser();
        legacy.setCTeam(csTeams.save(aCTeam(club.getName())));
        users.save(legacy);

        User alreadyLinked = aUser();
        alreadyLinked.setFootballTeam(club);
        alreadyLinked.setCTeam(csTeams.save(aCTeam("Unrelated name")));
        users.save(alreadyLinked);

        int first = linker.backfillAll();
        int second = linker.backfillAll();

        assertTrue(first >= 1, "the repair found nothing to do, so it was never tested");
        assertEquals(0, second, "running it twice changed something the second time");
        assertEquals(club.getId(), users.findById(legacy.getId()).orElseThrow().getFootballTeam().getId());
        assertEquals(club.getId(), users.findById(alreadyLinked.getId()).orElseThrow().getFootballTeam().getId(),
                "an account that already had a link had it overwritten by the repair");
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

    private Team aClub(String label) {
        return aClubNamed(label + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * A club whose name is <b>exactly</b> what is asked for, no random suffix.
     *
     * <p>Separated from {@link #aClub} because the duplicate-name tests need two rows with an identical
     * name, and a suffix would quietly make them distinct — which is how
     * {@code duplicateClubNamesAreResolvedNotThrown} first passed for the wrong reason: it created two
     * differently-named clubs, so the ambiguity it claimed to be testing never existed.
     */
    private Team aClubNamed(String exactName) {
        Team club = new Team();
        club.setName(exactName);
        Country country = new Country();
        country.setName("Linker " + UUID.randomUUID());
        country.setIsoCode(randomIso());
        country.setState(CountryState.SIMULATED);
        club.setCountry(countries.save(country));
        club.setReputation(50.0);
        club.setBudget(1_000_000.0);
        return teams.save(club);
    }

    private CTeam aCTeam(String name) {
        CTeam cTeam = new CTeam();
        cTeam.setName(name);
        return cTeam;
    }

    private User aUser() {
        User user = new User();
        user.setUsername("linker-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Linker tester");
        user.setRole(UserRole.REGULAR);
        user.setPlusSubscription(false);
        return users.save(user);
    }

    private static String randomIso() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder(3);
        for (int i = 0; i < 3; i++) {
            code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return code.toString();
    }
}