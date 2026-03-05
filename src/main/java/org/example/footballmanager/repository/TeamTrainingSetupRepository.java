package org.example.footballmanager.repository;

import org.example.footballmanager.model.TeamTrainingSetup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeamTrainingSetupRepository extends JpaRepository<TeamTrainingSetup, Long> {
    Optional<TeamTrainingSetup> findByTeamIdAndSeasonNumberAndWeekNumber(Long teamId, Integer seasonNumber, Integer weekNumber);
}

