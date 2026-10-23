package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Optional<JobRun> findBySeasonYearAndWeekNumberAndDayNumberAndJobKey(
            Integer seasonYear, Integer weekNumber, Integer dayNumber, String jobKey);

    List<JobRun> findBySeasonYearAndWeekNumberOrderByDayNumberAscRanAtHourAsc(
            Integer seasonYear, Integer weekNumber);

    List<JobRun> findByStatusOrderByRanAtDesc(JobRun.Status status);

    /**
     * Clears stuck claims (PENDING from a crashed run, or FAILED) for a given day, so an explicit
     * admin retry can re-run them without waiting for the backoff. Used by "Run due jobs now".
     */
    @Modifying
    @Transactional
    @Query("delete from JobRun j where j.seasonYear = :season and j.weekNumber = :week "
            + "and j.dayNumber = :day and j.status in :statuses")
    int deleteStuckForDay(@Param("season") Integer season,
                          @Param("week") Integer week,
                          @Param("day") Integer day,
                          @Param("statuses") List<JobRun.Status> statuses);
}
