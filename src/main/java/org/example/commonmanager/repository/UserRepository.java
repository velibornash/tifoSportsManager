package org.example.commonmanager.repository;

import org.example.commonmanager.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * How many real people are registered, so the World page reports a figure from the database
     * rather than a hard-coded one (owner, 2026-09-29).
     *
     * <p>Every row with a role is a person. The seeded accounts carry a role too, so this counts
     * them - it is a count of registered players, not of distinct humans who have typed a password
     * this session.
     */
    long countByRoleIsNotNull();

    /**
     * Accounts seen since a moment in time — the basis of the World page's "online" number.
     *
     * <p>Counts the column, not sessions, because there is no session registry: a JWT is stateless and
     * stays valid long after a browser is closed, so "has a valid token" and "is at the keyboard" are
     * different questions and only the second one is worth putting a number next to.
     */
    long countByLastSeenAtAfter(java.time.LocalDateTime moment);
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);

    boolean existsByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);

    default Optional<User> findByUsernameOrEmail(String value) {
        return findByUsername(value).or(() -> findByEmail(value));
    }

    List<User> findAllByIdNotOrderByUsernameAsc(Long id);

    /**
     * Every account that manages a given newLogic club, read through the real foreign key.
     *
     * <p>The reverse direction of {@code User.footballTeam}, and the one a club profile needs to answer
     * "who runs this team". Returns a list rather than an {@code Optional} for the same reason
     * {@code TeamRepository.findAllByNameIgnoreCase} does: two accounts pointing at one club should not
     * happen, and a query that throws when it does is a query that has to be wrapped in a try.
     */
    List<User> findAllByFootballTeamId(Long footballTeamId);

    /**
     * Every account managing one of several clubs, in one query.
     *
     * <p>Exists so the league table does not become an N+1. A table is up to 310 rows, and resolving a
     * manager per row would be 310 queries on a page the owner opens constantly — the same mistake
     * P1-4 measured and removed three times elsewhere in this codebase. The map is keyed by team id
     * because that is what a table row already holds.
     */
    java.util.List<User> findAllByFootballTeamIdIn(java.util.List<Long> footballTeamIds);

    /**
     * Accounts with a club name but no resolved id — the rows {@code ClubOwnershipLinker.backfillAll}
     * repairs.
     */
    List<User> findAllByFootballTeamIsNull();

    /**
     * @deprecated Reads {@code u.tifoCTeam.id}, which is a {@code CTeam} id, and is compared against
     * {@code Team.getId()} by {@code TransferService}. That comparison is wrong — the two entities have
     * independent {@code IDENTITY} sequences. Kept only so the migration can be measured; use
     * {@code User.footballTeam.id} instead.
     */
    @Deprecated
    @Query("SELECT DISTINCT u.tifoCTeam.id FROM CommonUser u WHERE u.tifoCTeam IS NOT NULL")
    List<Long> findDistinctManagedTeamIds();
}