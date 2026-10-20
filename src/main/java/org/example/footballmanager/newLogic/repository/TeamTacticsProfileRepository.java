package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.tactics.TeamTacticsProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamTacticsProfileRepository extends JpaRepository<TeamTacticsProfile, Long> {
    @Override
    @EntityGraph(attributePaths = {"team"})
    List<TeamTacticsProfile> findAll();

    Optional<TeamTacticsProfile> findByTeamId(Long teamId);

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
