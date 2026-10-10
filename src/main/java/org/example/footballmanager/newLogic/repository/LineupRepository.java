package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Lineup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LineupRepository extends JpaRepository<Lineup, Long> {
    List<Lineup> findByTeamId(Long teamId);
    Optional<Lineup> findByTeamIdAndMatchId(Long teamId, Long matchId);
    Optional<Lineup> findFirstByTeamIdAndMatchIsNullOrderByIdDesc(Long teamId);

    /**
     * The club template lineup for many teams at once.
     *
     * <p>{@code findFirstByTeamIdAndMatchIsNullOrderByIdDesc} was called once per team when building
     * snapshots for the world. This returns the same rows - a lineup with no match is a template, and a
     * team has one - for every team in one read.
     */
    @Query("SELECT l FROM Lineup l WHERE l.match IS NULL AND l.team.id IN :teamIds ORDER BY l.id DESC")
    List<Lineup> findTemplatesForTeams(Collection<Long> teamIds);
}
