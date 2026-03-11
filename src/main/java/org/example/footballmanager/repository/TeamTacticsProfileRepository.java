package org.example.footballmanager.repository;

import org.example.footballmanager.model.tactics.TeamTacticsProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeamTacticsProfileRepository extends JpaRepository<TeamTacticsProfile, Long> {
    Optional<TeamTacticsProfile> findByTeamId(Long teamId);
}