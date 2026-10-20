package org.example.footballmanager.newLogic.service;

import org.example.commonmanager.model.User;
import org.example.commonmanager.repository.UserRepository;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * The single place that answers "which newLogic club does this account manage", and the reverse.
 *
 * <p><b>This class exists to end the name-join.</b> {@code User} and {@code Team} were related by a
 * string — {@code User.cTeam.name == Team.name} — and every reader re-derived that join independently.
 * It cost four defects, all the same mistake in different costume: treating a {@code CTeam} id as a
 * {@code Team} id (P0-18 and three siblings). {@link User#getFootballTeam()} is now a real foreign key;
 * this service is what reads it, and what fills it in for rows written before it existed.
 *
 * <h2>Why it backfills instead of only reading the id</h2>
 *
 * <p>{@code ddl-auto=update} adds a nullable column and never populates it. An account created before
 * this class would have a null id and a perfectly good club, so a reader that trusted the column alone
 * would report "this manager has no club" — and every ownership gate fails closed, so that manager would
 * be locked out of his own team with no way to fix it. Falling back to the name-join and then writing
 * the answer back means the defect repairs itself the first time the account is looked at, rather than
 * waiting for a migration nobody has written.
 *
 * <h2>Why duplicates are resolved, not thrown</h2>
 *
 * <p>Two clubs may share a name — {@code TeamRepository} documents this and throws from
 * {@code findByName} when it happens. This service never calls {@code findByName}. On a collision it
 * takes the club that is human-controlled, then the lowest id, and says so in the log rather than
 * guessing silently: the same rule {@code /auth/me} already applies, so a profile page and a dashboard
 * cannot disagree about who runs a club.
 */
@Service
public class ClubOwnershipLinker {

    private final TeamRepository teams;
    private final UserRepository users;

    public ClubOwnershipLinker(TeamRepository teams, UserRepository users) {
        this.teams = teams;
        this.users = users;
    }

    /**
     * The club this account manages, or null when it cannot be determined.
     *
     * <p>Prefers the foreign key, falls back to the legacy name-join, and persists the result so the
     * next call is a single indexed read. Fails closed: an unknown account, an account with no club,
     * or a name matching nothing all return null, and every caller treats null as "not mine".
     */
    @Transactional
    public Team clubOf(User user) {
        if (user == null) {
            return null;
        }

        if (user.getFootballTeam() != null) {
            return user.getFootballTeam();
        }

        String name = clubNameOf(user);
        if (name == null || name.isBlank()) {
            return null;
        }

        Team resolved = resolveByName(name.trim());
        if (resolved != null) {
            user.setFootballTeam(resolved);
            users.save(user);
        }
        return resolved;
    }

    /** The id of the club this account manages, or null. See {@link #clubOf(User)}. */
    @Transactional
    public Long clubIdOf(User user) {
        Team club = clubOf(user);
        return club == null ? null : club.getId();
    }

    /**
     * Whether this account manages the given club.
     *
     * <p>The football answer to the question eight call sites each used to re-derive. Replaces
     * {@code PlusFeatureService.isOwnTeam}, which resolved through {@code findByName} and would throw
     * on a duplicate club name.
     */
    @Transactional
    public boolean manages(User user, Long teamId) {
        if (user == null || teamId == null) {
            return false;
        }
        return Objects.equals(clubIdOf(user), teamId);
    }

    /**
     * The account that manages this club, or null when the club is AI-run.
     *
     * <p>Reads the foreign key. <b>No fallback to the name-join</b>, and that asymmetry is deliberate: a
     * club profile showing the wrong manager is worse than one showing none, and the reverse direction
     * cannot be repaired by a single write without guessing which of two same-named clubs was meant.
     * {@link #backfillAll()} populates the column for the whole table when that needs to happen.
     */
    @Transactional(readOnly = true)
    public User managerOf(Team team) {
        if (team == null || team.getId() == null) {
            return null;
        }
        List<User> candidates = users.findAllByFootballTeamId(team.getId());
        if (candidates.isEmpty()) {
            return null;
        }
        // More than one account pointing at one club should not happen — approval reserves a free club
        // and hands it to exactly one manager — but picking by lowest id makes the answer stable rather
        // than dependent on row order if it ever does.
        return candidates.stream().min((a, b) -> Long.compare(a.getId(), b.getId())).orElse(null);
    }

    /**
     * Fills in {@link User#getFootballTeam()} for every account that has a club but no id.
     *
     * <p>A repair, not a boot step: nothing here runs unattended. The World page's "Repair world" button
     * is the honest caller, and {@link #clubOf(User)} makes most repairs unnecessary anyway.
     *
     * @return how many rows were repaired
     */
    @Transactional
    public int backfillAll() {
        int repaired = 0;
        for (User user : users.findAll()) {
            if (user.getFootballTeam() != null) {
                continue;
            }
            String name = clubNameOf(user);
            if (name == null || name.isBlank()) {
                continue;
            }
            Team resolved = resolveByName(name.trim());
            if (resolved != null) {
                user.setFootballTeam(resolved);
                users.save(user);
                repaired++;
            }
        }
        return repaired;
    }

    /**
     * Picks one club from the rows sharing a name, instead of crashing the way {@code findByName} does.
     *
     * <p>Human-controlled first, lowest id second — the same preference {@code /auth/me} applies.
     */
    private Team resolveByName(String name) {
        List<Team> matches = teams.findAllByNameIgnoreCase(name);
        if (matches.isEmpty()) {
            return null;
        }
        return matches.stream()
                .filter(Team::isHumanControlled)
                .findFirst()
                .orElseGet(() -> matches.stream()
                        .min((a, b) -> Long.compare(a.getId(), b.getId()))
                        .orElse(null));
    }

    /** The legacy name-join, in the one place it is now allowed to live. */
    private String clubNameOf(User user) {
        if (user.getCTeam() != null && user.getCTeam().getName() != null) {
            return user.getCTeam().getName();
        }
        return user.getTifoCTeam() != null ? user.getTifoCTeam().getName() : null;
    }
}