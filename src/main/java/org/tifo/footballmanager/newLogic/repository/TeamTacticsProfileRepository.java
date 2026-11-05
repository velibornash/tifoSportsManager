package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamTacticsProfileRepository extends JpaRepository<TeamTacticsProfile, Long> {
    @Override
    @EntityGraph(attributePaths = {"team"})
    List<TeamTacticsProfile> findAll();

    Optional<TeamTacticsProfile> findByTeamId(Long teamId);

    /** Lightweight: only formation + rulesJson, without loading the full entity (OOM prevention). */
    @org.springframework.data.jpa.repository.Query("select p.formation, p.rulesJson from TeamTacticsProfile p where p.team.id = :teamId")
    java.util.List<Object[]> findFormationAndRulesByTeamId(@org.springframework.data.repository.query.Param("teamId") Long teamId);

    /** Lightweight: only the formation string, without loading rulesJson. */
    @org.springframework.data.jpa.repository.Query("select p.formation from TeamTacticsProfile p where p.team.id = :teamId")
    Optional<String> findFormationByTeamId(@org.springframework.data.repository.query.Param("teamId") Long teamId);

    /**
     * The one profile the owner has designated as the world's starting tactic.
     *
     * <p>Returns a {@code List} rather than an {@code Optional} on purpose: if more than one row somehow
     * carries the flag the caller must see that and refuse, not silently take the first. The single-row
     * guarantee is maintained in {@code TacticLibraryService}, not here.
     */
    @EntityGraph(attributePaths = {"team"})
    List<TeamTacticsProfile> findByWorldDefaultTrue();
}
