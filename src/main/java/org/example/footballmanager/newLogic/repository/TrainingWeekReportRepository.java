package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.TrainingWeekReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrainingWeekReportRepository extends JpaRepository<TrainingWeekReport, Long> {
    List<TrainingWeekReport> findByTeamIdOrderBySeasonNumberDescWeekNumberDesc(Long teamId);
    Optional<TrainingWeekReport> findByTeamIdAndSeasonNumberAndWeekNumber(Long teamId, Integer seasonNumber, Integer weekNumber);
}

