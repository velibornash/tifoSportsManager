package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.FinanceLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FinanceLedgerEntryRepository extends JpaRepository<FinanceLedgerEntry, Long> {

    List<FinanceLedgerEntry> findByTeamIdAndSeasonYearOrderByWeekNumberAsc(Long teamId, Integer seasonYear);

    List<FinanceLedgerEntry> findByTeamIdAndSeasonYearAndWeekNumber(Long teamId, Integer seasonYear, Integer week);

    @Query("select e from FinanceLedgerEntry e where e.seasonYear = :seasonYear and e.weekNumber = :week")
    List<FinanceLedgerEntry> findBySeasonAndWeek(@Param("seasonYear") Integer seasonYear,
                                                @Param("week") Integer week);

    @Query("select distinct e.seasonYear from FinanceLedgerEntry e where e.team.id = :teamId "
            + "and e.seasonYear is not null order by e.seasonYear desc")
    List<Integer> findDistinctSeasonsByTeamIdOrderBySeasonYearDesc(@Param("teamId") Long teamId);

    void deleteBySeasonYear(Integer seasonYear);
}
