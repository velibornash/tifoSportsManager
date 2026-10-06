package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.model.UserRoles;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.model.Notification;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forum write bans and role changes.
 *
 * <p><b>Both features this repository could not express before.</b> There was no ban concept anywhere —
 * not a field, not an enum, not an endpoint, and nowhere to put one, because
 * {@code User.isAccountNonLocked()} is hard-coded {@code true}. And {@code MOD} was an enum constant with
 * no writer in the entire codebase, so the moderator office the forum needs could not be handed to a
 * person at all.
 *
 * <p>Assertions are on the persisted row re-read from the repository, not on a returned object. A ban
 * that exists only in memory is a ban the next request cannot see, and that is exactly the shape of bug
 * this repository has paid for repeatedly.
 */
class ModerationServiceTest extends BaseTest {

    @Autowired
    ModerationService moderation;

    @Autowired
    UserRepository users;

    @Autowired
    org.example.footballtextmanager.repository.CSTeamRepository csTeams;

    @Autowired
    org.example.footballmanager.newLogic.repository.NotificationRepository notifications;

    // ── The ban tells the person it was applied to ───────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a banned manager is notified, with the length and the reason")
    void aBanNotifiesTheBannedManager() {
        User moderator = aUser(UserRole.MOD);
        moderator.setDisplayName("The Moderator");
        users.save(moderator);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(moderator, target.getId(), 7, "Spam in the general section");

        List<Notification> theirs = notificationsFor(target);
        assertEquals(1, theirs.size(),
                "the banned manager is told he is banned. He has no other way to find out: he discovers "
                        + "it by trying to post and being refused.");
        Notification note = theirs.get(0);
        assertEquals(NotificationKind.FORUM_BANNED, note.getKind());
        assertTrue(note.getSummary().contains("7 day(s)"), "how long: " + note.getSummary());
        assertTrue(note.getSummary().contains("Spam in the general section"),
                "why, because a ban nobody can see the reason for cannot be argued with: " + note.getSummary());
        assertTrue(note.getSummary().contains("The Moderator"), "who: " + note.getSummary());
        assertNull(note.getReadAt(), "and it arrives unread, which is the point of a notification");
    }

    @Test
    @Transactional
    @DisplayName("the moderator is told too, so the decision is on the record")
    void aBanNotifiesTheModerators() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(moderator, target.getId(), 5, "Insisting");

        List<Notification> theirs = notificationsFor(moderator);
        assertEquals(1, theirs.size(),
                "notifyModeratorsOfBan existed with no callers, so a ban was recorded nowhere at all");
        assertTrue(theirs.get(0).getSummary().contains("banned"), theirs.get(0).getSummary());
    }

    @Test
    @Transactional
    @DisplayName("lifting a ban does not send another notification")
    void liftingABanSendsNothing() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);
        moderation.banFromForum(moderator, target.getId(), 2, "Insisting");

        moderation.liftForumBan(moderator, target.getId());

        List<Notification> theirs = notificationsFor(target);
        assertEquals(1, theirs.size(),
                "only the ban is announced. A lift is the absence of something, and a notification for it "
                        + "would be a second row explaining that the first row no longer applies.");
    }

    // ── The ban stops writing, and nothing else ────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a ban stops posting and leaves reading, messaging and the game alone")
    void aBanIsAForumWriteBanOnly() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(moderator, target.getId(), 7, "Spam in the general section");

        User banned = users.findById(target.getId()).orElseThrow();
        assertTrue(banned.isForumBanned(), "the ban was not persisted");
        assertEquals("Spam in the general section", banned.getForumBanReason());

        // Nothing else about the account moved. These are the assertions that stop a future change
        // turning "stop posting" into "lock him out", which would be a different decision.
        assertEquals(UserRole.REGULAR, banned.getRole(), "a forum ban also changed his role");
        assertNotNull(banned.getPassword(), "a forum ban touched his credentials");
        assertNotNull(banned.getCTeam(), "a forum ban detached his club from his account");
        // isAccountNonLocked is hard-coded true for every account and nothing in the ban path may change
        // that. A future change wiring the ban into Spring Security's account flags would pass every
        // other test here and lock the manager out of his own game.
        assertTrue(banned.isAccountNonLocked(), "a forum ban disabled the whole account");
        assertTrue(banned.isEnabled(), "a forum ban disabled login for the whole account");
    }

    @Test
    @Transactional
    @DisplayName("the ban is recorded against the moderator who applied it, not just as a timestamp")
    void theBanRemembersWhoAppliedIt() {
        User moderator = aUser(UserRole.MOD);
        moderator.setDisplayName("The Moderator");
        users.save(moderator);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(moderator, target.getId(), 3, "Insisting");

        assertEquals("The Moderator",
                users.findById(target.getId()).orElseThrow().getForumBanBy());
    }

    @Test
    @Transactional
    @DisplayName("a reason is mandatory, because a ban nobody can see the reason for cannot be argued with")
    void aReasonIsRequired() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        assertThrows(IllegalArgumentException.class,
                () -> moderation.banFromForum(moderator, target.getId(), 7, "   "));
        assertFalse(users.findById(target.getId()).orElseThrow().isForumBanned(),
                "a ban with no reason was applied anyway");
    }

    @Test
    @Transactional
    @DisplayName("a ban with no length is refused rather than defaulting to forever")
    void aBanNeedsARealLength() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        assertThrows(IllegalArgumentException.class,
                () -> moderation.banFromForum(moderator, target.getId(), 0, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> moderation.banFromForum(moderator, target.getId(), -3, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> moderation.banFromForum(moderator, target.getId(), 10_000, "x"),
                "an absurd duration was accepted, which is an account deletion wearing a ban's clothes");
        assertFalse(users.findById(target.getId()).orElseThrow().isForumBanned());
    }

    @Test
    @Transactional
    @DisplayName("an expired ban is not a ban, while the reason stays on the row for moderators")
    void anExpiredBanStopsApplying() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(moderator, target.getId(), 7, "Was a nuisance");
        User banned = users.findById(target.getId()).orElseThrow();
        banned.setForumBanUntil(LocalDateTime.now().minusMinutes(1));
        users.save(banned);

        User reread = users.findById(target.getId()).orElseThrow();
        assertFalse(reread.isForumBanned(), "a ban that ended yesterday still blocks posting today");
        assertEquals(0, moderation.remainingBanDays(reread));
        assertEquals("Was a nuisance", reread.getForumBanReason(),
                "the reason vanished when the ban expired, so nobody can see it happened");
    }

    @Test
    @Transactional
    @DisplayName("days left is rounded up, so a six-hour ban does not read as zero")
    void remainingDaysRoundsUp() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(moderator, target.getId(), 1, "Short one");
        User banned = users.findById(target.getId()).orElseThrow();
        banned.setForumBanUntil(LocalDateTime.now().plusHours(6));
        users.save(banned);

        assertEquals(1, moderation.remainingBanDays(users.findById(target.getId()).orElseThrow()),
                "six hours reported as zero days while the manager is still blocked");
    }

    @Test
    @Transactional
    @DisplayName("lifting a ban restores posting and keeps the record")
    void liftingABan() {
        User moderator = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);
        moderation.banFromForum(moderator, target.getId(), 7, "Fixed his behaviour");

        moderation.liftForumBan(moderator, target.getId());

        User lifted = users.findById(target.getId()).orElseThrow();
        assertFalse(lifted.isForumBanned());
        assertEquals("Fixed his behaviour", lifted.getForumBanReason());
    }

    // ── The gates that protect the moderator office ────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("a REGULAR account cannot ban anybody, including another REGULAR account")
    void aRegularManagerCannotModerate() {
        User nobody = aUser(UserRole.REGULAR);
        User target = aUser(UserRole.REGULAR);

        assertThrows(ResponseStatusException.class,
                () -> moderation.banFromForum(nobody, target.getId(), 7, "Because I can"));
        assertFalse(users.findById(target.getId()).orElseThrow().isForumBanned());
    }

    @Test
    @Transactional
    @DisplayName("a MOD can moderate — this is the role that had no writer before")
    void aModCanModerate() {
        User mod = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        moderation.banFromForum(mod, target.getId(), 2, "Off topic");

        assertTrue(users.findById(target.getId()).orElseThrow().isForumBanned());
    }

    @Test
    @Transactional
    @DisplayName("a MOD cannot ban a moderator, so the office cannot be attacked from inside")
    void aModCannotBanAModerator() {
        User mod = aUser(UserRole.MOD);
        User otherMod = aUser(UserRole.MOD);

        assertThrows(IllegalArgumentException.class,
                () -> moderation.banFromForum(mod, otherMod.getId(), 7, "Disagreeing with you"));
        assertFalse(users.findById(otherMod.getId()).orElseThrow().isForumBanned());
    }

    @Test
    @Transactional
    @DisplayName("nobody can ban themselves, because they could not then lift it")
    void youCannotBanYourself() {
        User mod = aUser(UserRole.MOD);

        assertThrows(IllegalArgumentException.class,
                () -> moderation.banFromForum(mod, mod.getId(), 7, "A experiment"));
        assertFalse(users.findById(mod.getId()).orElseThrow().isForumBanned());
    }

    @Test
    @Transactional
    @DisplayName("an unknown account is a 404, not a silent success")
    void banningNobodyIsANotFound() {
        User mod = aUser(UserRole.MOD);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> moderation.banFromForum(mod, 999_999_999L, 7, "Nobody"));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    // ── Roles ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Transactional
    @DisplayName("staff can appoint a MOD, which is the only way that role is ever written")
    void staffCanAppointAModerator() {
        User owner = aUser(UserRole.OWNER);
        User target = aUser(UserRole.REGULAR);

        moderation.changeRole(owner, target.getId(), UserRole.MOD);

        assertEquals(UserRole.MOD, users.findById(target.getId()).orElseThrow().getRole());
    }

    @Test
    @Transactional
    @DisplayName("a MOD cannot change roles, or it could promote itself to ADMIN and reset the database")
    void aModCannotChangeRoles() {
        User mod = aUser(UserRole.MOD);
        User target = aUser(UserRole.REGULAR);

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> moderation.changeRole(mod, target.getId(), UserRole.ADMIN));
        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        assertEquals(UserRole.REGULAR, users.findById(target.getId()).orElseThrow().getRole());
    }

    @Test
    @Transactional
    @DisplayName("the owner's own role cannot be changed, so nobody can lock everyone out")
    void theOwnersRoleIsFixed() {
        User otherOwner = aUser(UserRole.OWNER);
        User theOwner = aUser(UserRole.OWNER);

        assertThrows(IllegalArgumentException.class,
                () -> moderation.changeRole(otherOwner, theOwner.getId(), UserRole.REGULAR));
        assertEquals(UserRole.OWNER, users.findById(theOwner.getId()).orElseThrow().getRole());
    }

    @Test
    @Transactional
    @DisplayName("the two privilege questions have different answers")
    void moderatingAndStaffAreDifferentQuestions() {
        // MOD may delete a post and ban a manager. MOD may not reach the world-building tooling.
        // Merging the two sets is how a forum moderator quietly acquires the ability to reset a database.
        User mod = aUser(UserRole.MOD);
        assertTrue(UserRoles.mayModerate(mod));
        assertFalse(UserRoles.isStaff(mod));

        for (UserRole role : new UserRole[]{UserRole.ADMIN, UserRole.OWNER, UserRole.DEV}) {
            User staff = aUser(role);
            assertTrue(UserRoles.mayModerate(staff), role + " cannot moderate");
            assertTrue(UserRoles.isStaff(staff), role + " is not staff");
        }

        assertFalse(UserRoles.mayModerate(aUser(UserRole.REGULAR)));
        assertFalse(UserRoles.mayModerate(aUser(UserRole.PLUS)));
        assertFalse(UserRoles.mayModerate(aUser(UserRole.STAFF)));
        assertFalse(UserRoles.mayModerate(null), "a null principal was treated as a moderator");
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────────

    /** One account's notifications, newest first. */
    private List<Notification> notificationsFor(User user) {
        return notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(
                user.getId(), org.springframework.data.domain.PageRequest.of(0, 30));
    }

    private User aUser(UserRole role) {
        User user = new User();
        user.setUsername("mod-" + UUID.randomUUID() + "@test.local");
        user.setEmail(user.getUsername());
        user.setPassword("not-a-real-hash");
        user.setDisplayName("Moderation tester");
        user.setRole(role);
        user.setPlusSubscription(false);
        // A club, so "a ban does not detach the manager from his club" is an assertion about something
        // that exists. Without one the check passes trivially and proves nothing.
        org.example.footballtextmanager.model.CTeam club = new org.example.footballtextmanager.model.CTeam();
        club.setName("Moderation " + UUID.randomUUID().toString().substring(0, 8));
        user.setCTeam(csTeams.save(club));
        return users.save(user);
    }
}