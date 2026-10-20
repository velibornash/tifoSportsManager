package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which country a manager's world is built from (owner, 2026-09-28).
 *
 * <p>This is the function that makes the whole country system work, and it has one job that is easy to
 * get backwards: the country a manager <b>chose at registration</b> is the one that counts, not the
 * country their club happens to sit in. They normally agree — but when they do not, the club must not
 * silently overrule a deliberate choice.
 */
class ViewerCountryTest {

    private TeamRepository teams;
    private org.example.commonmanager.repository.UserRepository userRepo;
    private PlusFeatureService service;

    @BeforeEach
    void setUp() {
        teams = mock(TeamRepository.class);
        userRepo = mock(org.example.commonmanager.repository.UserRepository.class);
        service = new PlusFeatureService(teams, new ClubOwnershipLinker(teams, userRepo));
        // Three lookups, and stubbing only some is how this file passed for the wrong reason twice: the
        // linker now resolves the club (by id, or by name as a backfill), then viewerCountryCode loads
        // that club's country BY ID. A team returned by name has to be stubbed for both or one of the
        // two steps silently answers null.
    }

    /** Stubs the club as name-resolvable, id-loadable and — because the linker writes the id back — savable. */
    private void clubResolvesTo(Team team) {
        when(teams.findAllByNameIgnoreCase("Some Club"))
                .thenReturn(team == null ? java.util.List.of() : java.util.List.of(team));
        if (team != null && team.getId() != null) {
            when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        }
    }

    @Test
    @DisplayName("the country chosen at registration is what the world is built from")
    void chosenCountryWins() {
        var user = user("BRA");
        // A club in a different country - the case where getting this wrong is invisible until a
        // Brazilian manager is shown a Serbian league and nobody can say why.
        clubResolvesTo(team("SRB"));

        assertEquals("BRA", service.viewerCountryCode(user));
    }

    @Test
    @DisplayName("an account from before the field existed falls back to its club's country")
    void fallsBackToTheClubCountry() {
        var user = user(null);
        clubResolvesTo(team("SRB"));

        assertEquals("SRB", service.viewerCountryCode(user));
    }

    @Test
    @DisplayName("a blank country is treated as absent, not as a match")
    void blankIsAbsent() {
        var user = user("   ");

        assertNull(service.viewerCountryCode(user),
                "a blank column is the same as no answer, and must fall through to the club");
    }

    @Test
    @DisplayName("lowercase and padded codes are normalised")
    void codesAreNormalised() {
        assertEquals("BRA", service.viewerCountryCode(user(" bra ")));
    }

    @Test
    @DisplayName("no user, no team, no country - all resolve to null and nothing is invented")
    void unknownResolvesToNull() {
        assertNull(service.viewerCountryCode(null));

        var noTeam = user(null);
        clubResolvesTo(new Team());
        assertNull(service.viewerCountryCode(noTeam), "a club with no country has no country");

        var unknownClub = user(null);
        when(teams.findByName("Some Club")).thenReturn(Optional.empty());
        assertNull(service.viewerCountryCode(unknownClub), "an unresolvable club is not a country");
    }

    @Test
    @DisplayName("a club with no country never counts as being in one")
    void aClubWithNoCountryIsInNoCountry() {
        var user = user(null);
        clubResolvesTo(new Team());

        assertNull(service.viewerCountryCode(user),
                "defaulting this to the first country would hand an unassigned club to somebody");
    }

    @Test
    @DisplayName("the market filter is a VIEW: the purchase path has no country gate")
    void signingAForeignerIsStillAllowed() throws IOException {
        // The owner's rule: "there is no obstacle to bringing a foreigner into the club". So the
        // country decides what the market screen lists and nothing else.
        //
        // This is asserted on the source rather than at runtime because the thing being protected is
        // an *absence* - a check that was never added. There is no call to make and no exception to
        // expect; the only way to notice is to look for a gate that is not there, and a gate added
        // later is exactly what this catches.
        String service = Files.readString(
                Path.of("src/main/java/org/example/footballmanager/newLogic/service/TransferService.java"),
                StandardCharsets.UTF_8);

        // Strip the two read methods, then assert nothing country-related is left in the write paths.
        String writePaths = service.substring(service.indexOf("public TransferDTO buyListedPlayer"));
        assertTrue(!writePaths.contains("viewerCountryCode"),
                "the purchase path must not check the viewer's country - signing a foreigner is allowed");
        assertTrue(!writePaths.contains("inCountry("),
                "the purchase path must not call the market filter - that is a VIEW rule only");
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** A user whose club resolves by name, which is how /auth/me actually finds it. */
    private org.example.commonmanager.model.User user(String countryCode) {
        var user = new org.example.commonmanager.model.User();
        user.setUsername("tester");
        user.setRole(org.example.commonmanager.model.UserRole.REGULAR);
        user.setCountryCode(countryCode);
        var club = new org.example.footballtextmanager.model.CTeam();
        club.setName("Some Club");
        user.setCTeam(club);
        return user;
    }

    private Team team(String isoCode) {
        Team team = new Team();
        team.setId(7L);
        team.setName("Some Club");
        Country country = new Country();
        country.setIsoCode(isoCode);
        team.setCountry(country);
        return team;
    }
}
