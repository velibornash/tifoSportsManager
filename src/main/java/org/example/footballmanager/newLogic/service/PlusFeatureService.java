package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * What a manager is allowed to see (owner, 2026-09-27).
 *
 * <p>Two facts are paid information: a player's <b>talent</b>, and his <b>training percentage</b>.
 * The owner settled the rule precisely:
 * <ul>
 *   <li>talent is visible <b>only for players in your own squad</b>;</li>
 *   <li>the percentage likewise — you do not get it for a player you can see but do not manage.</li>
 * </ul>
 *
 * <p>"Plus" is a {@link UserRole}, not a separate flag, so it is read from the user.
 *
 * <h2>Why the own-team half matters</h2>
 * The free part of the rule is the <i>role</i>: a rival's talent is a scouting secret, and knowing it
 * would let a manager buy a 19-year-old for a price that only makes sense if you can see he is
 * special. Stripping it is not a restriction, it is what makes the transfer market a market.
 *
 * <h2>Staff and owners see everything</h2>
 * {@code OWNER}, {@code DEV} and {@code ADMIN} bypass the plus check, because a developer looking at
 * a bug needs the real number and an owner running the game should not be locked out of their own
 * squad. They still only see their own teams' players.
 */
@Service
public class PlusFeatureService {

    private final TeamRepository teams;

    public PlusFeatureService(TeamRepository teams) {
        this.teams = teams;
    }

    /** Roles that see paid information without a plus subscription. */
    private static final Set<UserRole> ALWAYS_ALLOWED =
            Set.of(UserRole.OWNER, UserRole.DEV, UserRole.ADMIN);

    /**
     * Whether this user may see talent and training percentages <b>at all</b>.
     *
     * <p>Checked before the own-team test so the two reasons can be told apart in a log.
     */
    public boolean hasPlus(User user) {
        if (user == null || user.getRole() == null) {
            return false;
        }
        return ALWAYS_ALLOWED.contains(user.getRole()) || user.getRole() == UserRole.PLUS;
    }

    /** Whether this user may see paid information about this specific player. */
    public boolean canSee(Player player, User user, Long viewerTeamId) {
        if (player == null) {
            return false;
        }
        if (!hasPlus(user)) {
            return false;
        }
        return isOwnPlayer(player, viewerTeamId);
    }

    /** Convenience for the common case where the player's own club is the thing being asked about. */
    public boolean canSee(Player player, User user) {
        Long teamId = player == null || player.getTeam() == null ? null : player.getTeam().getId();
        return canSee(player, user, teamId);
    }

    /**
     * Whether the player belongs to the viewer's own club.
     *
     * <p>A null viewer team means "we cannot tell", and the answer is no. Defaulting to yes would
     * leak every player's talent to anyone whose team lookup failed, which is the wrong way round.
     */
    public boolean isOwnPlayer(Player player, Long viewerTeamId) {
        if (player == null || viewerTeamId == null) {
            return false;
        }
        Team club = player.getTeam();
        return club != null && Objects.equals(club.getId(), viewerTeamId);
    }

    /**
     * Whether this team is one the user actually manages.
     *
     * <p>Resolved by name, because that is how the rest of the application links a user to a club:
     * {@code User} holds a {@code CTeam} and {@code /auth/me} looks the newLogic club up by name. Going
     * through the same route means the check cannot disagree with what the dashboard thinks the
     * user's club is.
     *
     * <p>Fails closed: an unknown user, a user with no club, or a name that matches nothing is not
     * their team.
     */
    public boolean isOwnTeam(User user, Long teamId) {
        if (user == null || teamId == null) {
            return false;
        }
        String name = clubNameOf(user);
        if (name == null || name.isBlank()) {
            return false;
        }
        Team team = teams.findByName(name).orElse(null);
        return team != null && team.getId() != null && team.getId().equals(teamId);
    }

    private String clubNameOf(User user) {
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return user.getCTeam().getName();
        }
        return user.getTifoCTeam() != null ? user.getTifoCTeam().getName() : null;
    }

    /**
     * Talent, or null when the viewer may not see it.
     *
     * <p>Returning null rather than zero matters: zero is a real talent value and would be shown,
     * making an invisible 10-year-old look like a hopeless one.
     */
    public Double talentOrNull(Player player, User user, Long viewerTeamId) {
        return canSee(player, user, viewerTeamId) ? player.getTalent() : null;
    }

    /** The training percentage, or null when the viewer may not see it. */
    public Double trainingPercentOrNull(Double percent, Player player, User user, Long viewerTeamId) {
        return canSee(player, user, viewerTeamId) ? percent : null;
    }
}
