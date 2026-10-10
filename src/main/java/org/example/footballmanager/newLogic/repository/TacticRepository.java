package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.tactics.Tactic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TacticRepository extends JpaRepository<Tactic, Long> {

    /** A club's tactics, oldest first, so a list reads the way it was built. */
    List<Tactic> findByTeamIdOrderByIdAsc(Long teamId);

    Optional<Tactic> findByTeamIdAndName(Long teamId, String name);

    Optional<Tactic> findByIdAndTeamId(Long id, Long teamId);

    /**
     * The club's default tactic.
     *
     * <p>The single-default rule is enforced in {@code TacticLibraryService}, which clears the previous
     * default in the same transaction. This returns empty when a club somehow has none — a state the
     * service prevents and the seeder repairs — and the caller falls back rather than failing, because a
     * match still has to be playable.
     */
    @Query("select t from Tactic t where t.team.id = :teamId and t.defaultTactic = true")
    List<Tactic> findDefaultsForTeam(@Param("teamId") Long teamId);

    /** How many clubs already hold at least one tactic — the seeder's "what is left to do" number. */
    @Query("select count(distinct t.team.id) from Tactic t")
    long countClubsWithTactics();

    @Query("select count(t) from Tactic t")
    long countAll();
}