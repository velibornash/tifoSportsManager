package org.example.footballmanager.newLogic.controller;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.commonmanager.util.JwtUtil;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.CountryState;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the principals a controller authorization test needs, and nothing else.
 *
 * <p><b>Every account is created here rather than read from the seed.</b> The {@code test} profile runs on
 * H2 with its own data, so a test that depends on a particular row in a particular database passes on the
 * owner's machine and fails in CI for a reason that has nothing to do with authorization. A test about a role
 * must build the role.
 *
 * <p><b>Every address is unique per call, and that is load-bearing rather than tidy.</b> {@code @SpringBootTest}
 * does not roll back between methods, so a fixed address made one method's lookup return two users and the
 * JWT filter refused the token — every test in the class failed at 401 for a reason unrelated to what they
 * were testing. Creating the user each time, with a fresh address, is the only version of this that runs in
 * any order.
 *
 * <p><b>A manager is made by naming a club, not by pointing at one.</b> {@code PlusFeatureService.viewerTeamId}
 * and {@code isOwnTeam} both resolve the viewer's club through {@code User.cTeam}'s <i>name</i> and then
 * {@code TeamRepository.findByName(name)}. That is the same resolution {@code /auth/me} performs, so a fixture
 * that attached the id directly would be testing a path the product does not use. It also means
 * {@code findByName} must be given a name no other row holds: it returns {@code Optional<Team>} and therefore
 * <b>throws</b> when two clubs share a name.
 *
 * <p>Brought in with {@code @Import(ControllerAuthFixture.class)}, exactly as {@code TestCountryCatalogue} is.
 */
@org.springframework.boot.test.context.TestConfiguration
public class ControllerAuthFixture {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository users;

    @Autowired
    private CSTeamRepository csTeams;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private CountryRepository countries;

/**
 * A country of its own, so a fixture club is never attached to somebody else's.
 *
 * <p><b>The retry is not defensive noise — it is a defect this repository has already paid for.</b>
 * {@code country.iso_code} carries a unique index ({@code CONSTRAINT_INDEX_6}), it is three characters wide,
 * and there are only 46,656 three-letter codes. A shared H2 database that every {@code @SpringBootTest}
 * accumulates into therefore collides, and the collision surfaces as an unrelated-looking
 * {@code DataIntegrityViolationException} in whichever test happened to draw the same code. That is
 * {@code CountryCatalogQueryCountTest}'s recorded failure. Drawing again is the fix; a wider alphabet does not
 * help when a full suite draws a hundred times.
 */
public Country country() {
    DataIntegrityViolationException last = null;
    for (int attempt = 0; attempt < 20; attempt++) {
        try {
            Country country = new Country();
            country.setName("Auth " + UUID.randomUUID());
            country.setIsoCode(randomIsoCode());
            country.setState(CountryState.SIMULATED);
            return countries.save(country);
        } catch (DataIntegrityViolationException collision) {
            last = collision;
        }
    }
    throw new IllegalStateException(
            "Could not draw a free country iso code in 20 attempts", last);
}

/** Three characters from a 36-letter alphabet — the width the column actually allows. */
private static String randomIsoCode() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    StringBuilder code = new StringBuilder(3);
    for (int i = 0; i < 3; i++) {
        code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
    }
    return code.toString();
}

    /**
     * A club with a name nothing else holds.
     *
     * <p>The random marker is joined with a hyphen, not a space. Club names appear in JSON bodies, and the
     * assertion helpers in these tests strip whitespace before matching — so a fixture name containing a space
     * can never be found in a stripped body, and the test fails for a reason that has nothing to do with what
     * it is testing.
     *
     * <p>{@code reputation} and {@code budget} are set because readers of a club assume both are present —
     * {@code TeamController.getProfile} compares {@code getReputation()} against 70 without a null check — so a
     * fixture that left them null would fail in the reader rather than in the thing under test.
     */
    public Team club(String label) {
        Team club = new Team();
        club.setName(label + "-" + UUID.randomUUID().toString().substring(0, 8));
        club.setCountry(country());
        club.setReputation(50.0);
        club.setBudget(1_000_000.0);
        return teams.save(club);
    }

    /** A token for an account of the given role that runs no club. */
    public String bearer(UserRole role) {
        return tokenFor(role, null);
    }

    /**
     * A token for an account of the given role that <b>runs the given club</b>.
     *
     * <p>The role is a parameter rather than implied, because the two halves of an authorization test are
     * different questions: an administrator is refused another club's data, and a manager is refused an
     * administrator's route. Asserting only one of them leaves the other untested.
     */
    public String bearerManaging(UserRole role, Team club) {
        return tokenFor(role, club);
    }

    private String tokenFor(UserRole role, Team managedClub) {
        User user = new User();
        user.setUsername("auth-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Authorization tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        if (managedClub != null) {
            CTeam cTeam = new CTeam();
            cTeam.setName(managedClub.getName());
            // It has to be saved. An unsaved transient instance fails the FK on flush and the request 401s
            // with "object references an unsaved transient instance", which reads like an auth failure and is
            // not one.
            cTeam = csTeams.save(cTeam);
            user.setCTeam(cTeam);
            // **Only cTeam, deliberately.** `PlusFeatureService.viewerTeamId` checks `tifoCTeam` FIRST and
            // returns `getTifoCTeam().getId()` -- and `CTeam` is a different entity with its own id
            // sequence, so that value is not a `Team` id. Setting both here made every ownership check that
            // goes through `viewerTeamId` fail, which showed up as a manager being refused his own club.
            //
            // It is not a fixture artefact to be shrugged off: `DatabaseInitializer` and `StartupInitializer`
            // both set the **owner's** `tifoCTeam`, so the owner really does get a `CTeam` id back from
            // `viewerTeamId`. Recorded as P0-18.
        }
        return "Bearer " + jwtUtil.generateToken(users.save(user));
    }
}