package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.commonmanager.model.UserRoles;
import org.example.commonmanager.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Bans and role changes over accounts (owner, 2026-10-05).
 *
 * <h2>The ban is a forum write ban. Deliberately.</h2>
 *
 * <p>It stops a manager opening a topic or posting a reply. It does not stop him reading the forum,
 * sending private messages, or playing the game — none of which were part of the complaint that
 * prompted it. A moderation action that also takes the club away is a different decision with a
 * different owner, and one that would not be reversible by lifting the ban.
 *
 * <h2>Why the check lives here and not in the controller</h2>
 *
 * <p>{@code isForumBanned()} on the entity is the cheap half. A ban that were enforced only by the forum
 * controller would be bypassed by any second caller of {@code ForumService}, and the forum is about to
 * have one — the new topic form and the reply form are different endpoints. Enforced in
 * {@code ForumService.createTopic/createPost} it holds for every writer.
 *
 * <h2>Nobody can ban themselves</h2>
 *
 * <p>Not a nicety: an administrator who bans himself into silence and then cannot lift it has locked
 * himself out of the one page that lifts bans. The check is here rather than in the UI because the UI is
 * not the gate.
 */
@Service
public class ModerationService {

    /** A ban longer than this stops being a moderation action and becoming an account deletion. */
    private static final int MAX_BAN_DAYS = 365;

    private final UserRepository users;
    private final NotificationService notifications;

    public ModerationService(UserRepository users, NotificationService notifications) {
        this.users = users;
        this.notifications = notifications;
    }

    /**
     * Bans an account from writing in the forum for a number of days.
     *
     * @param days how long; 1..365
     * @throws IllegalArgumentException on a non-positive or absurd duration, or when the target is the caller
     * @throws ResponseStatusException 404 when there is no such account, 403 when the caller may not moderate
     */
    @Transactional
    public User banFromForum(User moderator, Long targetUserId, int days, String reason) {
        requireModerator(moderator);
        if (days < 1 || days > MAX_BAN_DAYS) {
            throw new IllegalArgumentException("A ban must be between 1 and " + MAX_BAN_DAYS + " days.");
        }

        User target = requireTarget(targetUserId);
        if (target.getId().equals(moderator.getId())) {
            throw new IllegalArgumentException("You cannot ban yourself.");
        }
        if (UserRoles.mayModerate(target)) {
            throw new IllegalArgumentException(
                    target.getDisplayName() + " is a moderator, so the ban has to come from an owner.");
        }

        String cleanReason = normalizeReason(reason);
        LocalDateTime now = LocalDateTime.now();
        target.setForumBanUntil(now.plusDays(days));
        target.setForumBanReason(cleanReason);
        target.setForumBanBy(displayNameOf(moderator));
        target.setForumBanAt(now);
        User banned = users.save(target);

        // **Tell him.** A ban applied silently is a manager discovering he cannot post and having no
        // idea why; the ban is visible on his profile, and the profile is not somewhere a banned
        // manager is told to look (owner, 2026-10-07).
        //
        // The refusal he gets if he tries to write anyway is unchanged and still happens — this is in
        // addition to it, not instead of it. A notification is what he can find later; the refusal is
        // what stops him now, and a notification alone would leave him guessing at the cause each time
        // he hits it.
        notifications.notifyForumBan(banned, displayNameOf(moderator), days, cleanReason);

        // And tell the moderators, so the decision is on the record for the people who made it.
        notifications.notifyModeratorsOfBan(banned, displayNameOf(moderator), days, cleanReason);
        return banned;
    }

    /** Lifts an active ban, keeping the reason on the row so moderators can see it happened. */
    @Transactional
    public User liftForumBan(User moderator, Long targetUserId) {
        requireModerator(moderator);

        User target = requireTarget(targetUserId);
        if (target.getId().equals(moderator.getId())) {
            throw new IllegalArgumentException("You cannot lift your own ban.");
        }
        if (target.getForumBanUntil() == null) {
            return target;
        }
        target.setForumBanUntil(null);
        return users.save(target);
    }

    /**
     * How many days of the ban are left, rounded up.
     *
     * <p>Rounded up because a ban with six hours left is still "1 day" to the manager reading it, and
     * rounding down would show him 0 days while he is still unable to post.
     */
    public long remainingBanDays(User user) {
        if (user == null || !user.isForumBanned()) {
            return 0;
        }
        long minutes = ChronoUnit.MINUTES.between(LocalDateTime.now(), user.getForumBanUntil());
        return Math.max(1, (minutes + 1439) / 1440);
    }

    /**
     * Changes an account's role.
     *
     * <p><b>This is how a MOD account comes to exist at all.</b> Before it, {@code MOD}, {@code ADMIN},
     * {@code DEV} and {@code STAFF} were enum constants with no writer anywhere in the codebase — only
     * {@code OWNER} (seeders) and {@code REGULAR} (seeders and approval) were ever assigned, so the
     * moderator role the forum needs could not be given to a person through the product.
     *
     * <p>Refused for the owner's own account: the owner is the one account that can restore an
     * administrator, and an administrator who can demote the owner can lock everyone out of the game.
     */
    @Transactional
    public User changeRole(User moderator, Long targetUserId, UserRole newRole) {
        if (!UserRoles.isStaff(moderator)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only staff may change a role.");
        }
        if (newRole == null) {
            throw new IllegalArgumentException("Pick a role.");
        }

        User target = requireTarget(targetUserId);
        if (target.getRole() == UserRole.OWNER && newRole != UserRole.OWNER) {
            throw new IllegalArgumentException("The owner's role cannot be changed.");
        }
        if (target.getId().equals(moderator.getId()) && newRole != moderator.getRole()) {
            throw new IllegalArgumentException("You cannot change your own role.");
        }

        target.setRole(newRole);
        return users.save(target);
    }

    /**
     * Throws unless the caller may act on other people's content.
     *
     * <p>The single gate. A {@code MOD}/{@code ADMIN}/{@code OWNER}/{@code DEV} account passes; everyone
     * else, including a null role, is refused with 403.
     */
    public void requireModerator(User actor) {
        if (!UserRoles.mayModerate(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a moderator may do that.");
        }
    }

    private User requireTarget(Long targetUserId) {
        if (targetUserId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Which account?");
        }
        return users.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
    }

    /**
     * A reason is mandatory, and the length is enforced here rather than trusted to the column.
     *
     * <p>A 500-character column silently truncating a moderator's explanation would leave him reading a
     * reason that ends mid-sentence and does not say why he was banned.
     */
    private String normalizeReason(String reason) {
        String clean = reason == null ? "" : reason.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Give a reason — the manager is told what it is.");
        }
        if (clean.length() > 500) {
            throw new IllegalArgumentException("That reason is too long. Keep it under 500 characters.");
        }
        return clean;
    }

    private String displayNameOf(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName().trim();
        }
        return user.getUsername();
    }
}