package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.model.UserRole;
import org.example.footballmanager.newLogic.model.Junior;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
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
     * <p>Two independent things satisfy this, and conflating them is why they are written separately:
     *
     * <ul>
     *   <li><b>the purchase</b> — {@link User#getPlusSubscription()}. This is the commercial one and
     *       it is what the profile screen reports.</li>
     *   <li><b>the role</b> — {@code OWNER}, {@code DEV} and {@code ADMIN}. A developer looking at a
     *       bug needs the real number, and an owner running the game should not be locked out of his
     *       own squad. This is a <b>debugging affordance</b>, not a subscription, which is exactly why
     *       it must not be what the profile calls "PLUS".</li>
     * </ul>
     *
     * <p>Even so, the role bypass does <b>not</b> skip the own-club test — see {@link #isOwnPlayer}.
     */
    public boolean hasPlus(User user) {
        if (user == null || user.getRole() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(user.getPlusSubscription())) {
            return true;
        }
        return ALWAYS_ALLOWED.contains(user.getRole()) || user.getRole() == UserRole.PLUS;
    }


    /**
     * Whether this user may see a <b>junior's</b> reported talent.
     *
     * <p>The owner's rule covers "your own first team <b>and your own academy juniors</b>", and this is
     * the academy half. It is a separate method from {@link #canSee} rather than an overload because
     * the subject is a different type, and because a junior is a <b>report</b> rather than a player:
     * what is revealed is a band that firms up (Sprint 5.2), not a number.
     *
     * <p>Deliberately <b>not</b> widened to "any junior in the game". A scouting network reports on
     * foreign prospects, and if this returned true for those the subscription would be worth nothing.
     */
    public boolean canSeeJunior(Junior junior, User user, Long viewerTeamId) {
        if (junior == null || viewerTeamId == null) {
            return false;
        }
        if (!hasPlus(user)) {
            return false;
        }
        Team club = junior.getTeam();
        return club != null && club.getId() != null && club.getId().equals(viewerTeamId);
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
    /**
     * The club this user actually runs, resolved the way {@code /auth/me} resolves it — by name —
     * so that the answer here cannot disagree with the club the dashboard is showing.
     *
     * <p>Exists as a method because three call sites each grew their own private copy of this lookup,
     * which is how the entitlement rule ended up implemented in several places at once. Fails closed:
     * an unknown user, or one with no club, gets null, and every gate treats null as "not mine".
     *
     * <p><b>It used to short-circuit on {@code tifoCTeam} and return that id directly</b>, which was a
     * {@code CTeam} id — {@code CTeam} is {@code footballtextmanager.model.CTeam}, a different entity with
     * its own {@code IDENTITY} sequence — returned from a method whose every caller compares it against
     * {@code Team.id}. The two number spaces are unrelated, so the answer was wrong for anyone who had a
     * {@code tifoCTeam}.
     *
     * <p><b>It was not a rare branch.</b> {@code DatabaseInitializer} and {@code StartupInitializer} all set
     * the <b>owner's</b> {@code tifoCTeam}, so the one account guaranteed to exist took it, and
     * {@code talentOrNull} silently withheld the owner's own players' talent. {@code RegistrationService}
     * sets only {@code cTeam}, which is why no ordinary manager ever reached the branch and nothing caught
     * it. Resolving by name alone handles both fields, because {@link #clubNameOf} reads {@code cTeam}
     * first and falls back to {@code tifoCTeam}.
     */
    public Long viewerTeamId(User user) {
        if (user == null) {
            return null;
        }
        String name = clubNameOf(user);
        if (name == null || name.isBlank()) {
            return null;
        }
        Team team = teams.findByName(name).orElse(null);
        return team == null ? null : team.getId();
    }

    /**
     * The country this manager plays in, as a three-letter code.
     *
     * <p>Prefers the country <b>chosen at registration</b> ({@code User.countryCode}) and falls back to
     * the country of the club they hold, for an account created before that field existed.
     *
     * <p>The chosen value wins deliberately. They normally agree, but when they do not — a manager who
     * registers for one country and is later handed a club in another — the answer that reflects what
     * they asked for is the one that decides which league system, transfer market and national team
     * they see. Otherwise the club silently overrules the choice they made.
     *
     * <p>Null when neither is known, and callers must treat that as "no country" rather than defaulting
     * to one. A default here would be a quiet way to show a manager somebody else's country.
     */
    public String viewerCountryCode(User user) {
        if (user == null) {
            return null;
        }
        if (user.getCountryCode() != null && !user.getCountryCode().isBlank()) {
            return user.getCountryCode().trim().toUpperCase(Locale.ROOT);
        }
        Long teamId = viewerTeamId(user);
        if (teamId == null) {
            return null;
        }
        return teams.findById(teamId)
                .map(Team::getCountry)
                .filter(Objects::nonNull)
                .map(Country::getIsoCode)
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .orElse(null);
    }

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
        return canSee(player, user, viewerTeamId) ? round2(player.getTalent()) : null;
    }

    /**
     * Rounds a talent to two decimals, or leaves it null if there is nothing to show.
     *
     * <p>Senior talent is <i>derived</i> — {@code (20 - (discipline + form)) / 2} over double-valued
     * attributes — so the raw column holds values like {@code 7.340364151890263}. That is not a
     * figure anyone can read, and calling it an "exact talent" while showing sixteen digits
     * contradicts the point. Two decimals is a rounding of the same value, not a band: a manager still
     * knows precisely how good the player is, and the number shown equals the number stored.
     *
     * <p>Junior talent arrives as an exact integer and passes through unchanged.
     */
    private Double round2(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    /** The training percentage, or null when the viewer may not see it. */
    public Double trainingPercentOrNull(Double percent, Player player, User user, Long viewerTeamId) {
        return canSee(player, user, viewerTeamId) ? percent : null;
    }
}
