package org.example.footballmanager.repository;

import org.example.footballmanager.model.tactics.TeamTacticsProfile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamTacticsProfileRepository extends JpaRepository<TeamTacticsProfile, Long> {
    @Override
    @EntityGraph(attributePaths = {"team"})
    List<TeamTacticsProfile> findAll();

    Optional<TeamTacticsProfile> findByTeamId(Long teamId);
}
