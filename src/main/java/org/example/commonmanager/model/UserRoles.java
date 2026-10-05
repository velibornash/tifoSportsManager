package org.example.commonmanager.model;

import java.util.Set;

/**
 * The one place that answers "what may this account do".
 *
 * <p><b>It exists because the answer was written fourteen times.</b> Before this class the set of
 * privileged roles appeared as: two byte-identical private {@code isAdminRole} methods
 * ({@code CommunityController} and {@code CommunityMessageService}), a fourth private set inside
 * {@code PlusFeatureService.ALWAYS_ALLOWED}, seven repetitions of the same triple inside
 * {@code @PreAuthorize} expressions, six verbatim {@code "OWNER"} string comparisons in ownership
 * helpers, one election check that additionally ORs in a hardcoded email address, and one set on the
 * client. Every one of them was a place to forget to add a role, and one of them had already been
 * narrowed to {@code OWNER} alone — so an {@code ADMIN} was allowed to reset the database while being
 * refused on their own club's lineup.
 *
 * <p><b>Two questions, deliberately not merged.</b>
 *
 * <ul>
 *   <li>{@link #mayModerate(User)} — may act on <i>another person's</i> content. Forum post deletion,
 *       a write ban. {@code MOD} belongs here and nowhere else.</li>
 *   <li>{@link #isStaff(User)} — may reach the world-building tooling. {@code MOD} does not: deleting a
 *       rude post is not authority over the database.</li>
 * </ul>
 *
 * <p>A {@code null} role is treated as no privilege. {@link User#getAuthorities()} would throw on it,
 * so no account authenticates with one, but a row can be read before that happens and a moderation
 * check must fail closed rather than throw.
 */
public final class UserRoles {

    /**
     * May act on other people's content: delete any forum post, ban a manager from posting.
     *
     * <p>Deliberately wider than {@link #isStaff}. A moderator's job is to keep the forum civil and has
     * no business touching the world; keeping the sets separate is what stops one of those jobs
     * quietly acquiring the other.
     */
    private static final Set<UserRole> MODERATOR_ROLES = Set.of(UserRole.MOD, UserRole.ADMIN, UserRole.OWNER, UserRole.DEV);

    /**
     * May reach world-building tooling under {@code /admin/**}.
     *
     * <p>The same set {@code SecurityConfig}'s {@code /admin/**} matcher enforces. When a role is
     * added here it must be added there too, or the two will disagree.
     */
    private static final Set<UserRole> STAFF_ROLES = Set.of(UserRole.ADMIN, UserRole.OWNER, UserRole.DEV);

    private UserRoles() {
    }

    public static boolean mayModerate(User user) {
        return user != null && MODERATOR_ROLES.contains(user.getRole());
    }

    public static boolean isStaff(User user) {
        return user != null && STAFF_ROLES.contains(user.getRole());
    }

    /**
     * The role's own name, or {@code null} for an account with no role.
     *
     * <p>Replaces the three separate {@code getRole() != null ? getRole().name() : "…"} fallbacks.
     * Those invented a label — one of them the string {@code "USER"}, which is not a {@code UserRole}
     * value and which the client then had to render literally.
     */
    public static String nameOf(UserRole role) {
        return role == null ? null : role.name();
    }
}