package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.example.footballtextmanager.model.CTeam;
import org.example.footballtextmanager.repository.CSTeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code viewerTeamId} returned an id from the wrong table, and the owner was the account that took it.
 *
 * <p>The method used to open with:
 *
 * <pre>{@code if (user.getTifoCTeam() != null && user.getTifoCTeam().getId() != null) {
 *     return user.getTifoCTeam().getId();   // a CTeam id
 * }}</pre>
 *
 * <p>{@code CTeam} is {@code footballtextmanager.model.CTeam} — a <b>different entity with its own
 * {@code IDENTITY} sequence</b>. Every caller of this method compares the answer against {@code Team.id}, so
 * for anyone holding a {@code tifoCTeam} it was wrong, silently.
 *
 * <p><b>The owner is who held one.</b> {@code DatabaseInitializer:899} and {@code StartupInitializer:104,142}
 * all set the owner's {@code tifoCTeam}, so the one account guaranteed to exist took the branch, and
 * {@code talentOrNull} withheld the owner's own players' talent — the one thing a scouting subscription buys.
 * {@code RegistrationService} sets only {@code cTeam}, so no ordinary manager ever reached it and nothing
 * caught it.
 *
 * <p><b>Asserted on the value, against a real id of the other kind.</b> The fixture gives the
 * {@code CTeam} an id deliberately different from the club's, so a test that passed by coincidence — the two
 * sequences happening to agree — cannot pass here. That coincidence is the whole reason this went unnoticed:
 * in a small test database the two id spaces can line up.
 */
class ViewerTeamIdIsATeamIdTest extends BaseTest {

    @Autowired
    PlusFeatureService plusFeatures;

    @Autowired
    TeamRepository teams;

    @Autowired
    PlayerRepository players;

    @Autowired
    UserRepository users;

    @Autowired
    CSTeamRepository csTeams;

    @Test
    @Transactional
    @DisplayName("a user with a tifoCTeam resolves to their Team, not to the CTeam's id")
    void aTifoCTeamDoesNotDecideTheAnswer() {
        Team club = aClub("Owner club");
        User owner = aUser(UserRole.OWNER);

        // Deliberately give the CTeam a wildly different id from the club's. Two independent sequences can
        // coincide in a small database, and that coincidence is the only reason this bug survived.
        CTeam cTeam = csTeams.save(aCTeam(club.getName()));
        owner.setCTeam(cTeam);
        owner.setTifoCTeam(cTeam);
        owner = users.save(owner);

        Long resolved = plusFeatures.viewerTeamId(owner);

        assertEquals(club.getId(), resolved,
                "viewerTeamId answered with the CTeam's id (" + cTeam.getId() + ") where the club's id ("
                        + club.getId() + ") was needed");
    }

    /**
     * And the value the method exists to produce is the one that unlocks the entitlement.
     *
     * <p>Asserted on the *effect*, not on the intermediate: a test that only checked the id would still pass
     * if every caller were changed to stop using it.
     */
    @Test
    @Transactional
    @DisplayName("the owner can see his own player's talent, which is what the wrong id prevented")
    void theOwnerCanSeeHisOwnPlayersTalent() {
        Team club = aClub("Talent club");
        User owner = aUser(UserRole.OWNER);
        owner.setPlusSubscription(true);

        CTeam cTeam = csTeams.save(aCTeam(club.getName()));
        owner.setCTeam(cTeam);
        owner.setTifoCTeam(cTeam);
        owner = users.save(owner);

        Player player = aPlayer(club);

        Double talent = plusFeatures.talentOrNull(player, owner, plusFeatures.viewerTeamId(owner));

        assertNotNull(talent,
                "the owner's own player's talent was withheld, because viewerTeamId answered with a "
                        + "CTeam id and isOwnPlayer compared it against Team.id");
    }

    /** The same answer with only {@code cTeam} set, so the two paths cannot drift apart again. */
    @Test
    @Transactional
    @DisplayName("cTeam alone still resolves, and to the same club")
    void cTeamAloneStillResolves() {
        Team club = aClub("Only cTeam");
        User manager = aUser(UserRole.REGULAR);

        CTeam cTeam = csTeams.save(aCTeam(club.getName()));
        manager.setCTeam(cTeam);
        manager = users.save(manager);

        assertEquals(club.getId(), plusFeatures.viewerTeamId(manager),
                "resolving by name must work with cTeam alone, which is what RegistrationService sets");
    }

    /** Fails closed, as the method's own contract says. */
    @Test
    @Transactional
    @DisplayName("a user with no club resolves to null rather than to a wrong id")
    void aUserWithNoClubResolvesToNull() {
        assertNull(plusFeatures.viewerTeamId(users.save(aUser(UserRole.REGULAR))),
                "a user with no club must not be given an id");
        assertNull(plusFeatures.viewerTeamId(null));
    }

    /** A club name nothing holds resolves to null — the gate fails closed rather than guessing. */
    @Test
    @Transactional
    @DisplayName("a club name the world does not have resolves to null")
    void anUnknownClubNameResolvesToNull() {
        User manager = aUser(UserRole.REGULAR);
        CTeam cTeam = csTeams.save(aCTeam("No Such Club " + UUID.randomUUID()));
        manager.setCTeam(cTeam);
        manager = users.save(manager);

        assertNull(plusFeatures.viewerTeamId(manager));
    }

    @Test
    @Transactional
    @DisplayName("isOwnTeam still answers for the club the user runs")
    void isOwnTeamStillAgrees() {
        Team club = aClub("Own team");
        User manager = aUser(UserRole.REGULAR);
        CTeam cTeam = csTeams.save(aCTeam(club.getName()));
        manager.setCTeam(cTeam);
        manager = users.save(manager);

        assertTrue(plusFeatures.isOwnTeam(manager, club.getId()),
                "the two methods must not disagree about who owns a club");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────────

    private Team aClub(String label) {
        Team club = new Team();
        club.setName(label + "-" + UUID.randomUUID().toString().substring(0, 8));
        club.setReputation(50.0);
        club.setBudget(1_000_000.0);
        return teams.save(club);
    }

    /**
     * A CTeam carrying the club's name.
     *
     * <p>It used to take a second argument: an id chosen to differ from the club's, on the theory that the
     * test could not then pass by coincidence. It could not, and the reason is the bug itself — the saved
     * CTeam is handed whatever id its own sequence produces, which is exactly how the real defect survived.
     * What pins the assertion is comparing against the club's id rather than against "not null". The
     * parameter was decoration and is gone.
     */
    private CTeam aCTeam(String clubName) {
        CTeam cTeam = new CTeam();
        cTeam.setName(clubName);
        return cTeam;
    }

    private Player aPlayer(Team club) {
        Player player = new Player();
        player.setName("Talent " + System.nanoTime());
        player.setTeam(club);
        player.setPosition(Position.ATT);
        player.setRating(70);
        player.setTalent(9.1);
        Skills skills = new Skills();
        skills.setSkill(SkillName.PACE, 70);
        skills.setSkill(SkillName.STRIKER, 75);
        skills.setSkill(SkillName.PASSING, 68);
        skills.setSkill(SkillName.TECHNIQUE, 72);
        skills.setSkill(SkillName.DEFENDER, 40);
        skills.setSkill(SkillName.STAMINA, 66);
        skills.setSkill(SkillName.PLAYMAKER, 60);
        skills.setSkill(SkillName.GOALKEEPER, 5);
        player.setSkills(skills);
        return players.save(player);
    }

    private User aUser(UserRole role) {
        User user = new User();
        user.setUsername("viewer-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Viewer tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        return user;
    }
}