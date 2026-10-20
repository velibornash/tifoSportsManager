package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.TeamTrainingSetup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamTrainingSetupRepository extends JpaRepository<TeamTrainingSetup, Long> {
    List<TeamTrainingSetup> findByTeamId(Long teamId);
    Optional<TeamTrainingSetup> findByTeamIdAndSeasonNumberAndWeekNumber(Long teamId, Integer seasonNumber, Integer weekNumber);
}