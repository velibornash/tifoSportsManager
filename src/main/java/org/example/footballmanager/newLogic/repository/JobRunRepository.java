package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Optional<JobRun> findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(
            Integer seasonYear, Integer weekNumber, Integer dayNumber, String jobKey);

    List<JobRun> findBySeasonYearAndWeekNumberOrderByDayNumberAscRanAtHourAsc(
            Integer seasonYear, Integer weekNumber);

    List<JobRun> findByStatusOrderByRanAtDesc(JobRun.Status status);
}
