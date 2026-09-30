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

    @Query("SELECT DISTINCT u.tifoCTeam.id FROM CommonUser u WHERE u.tifoCTeam IS NOT NULL")
    List<Long> findDistinctManagedTeamIds();
}