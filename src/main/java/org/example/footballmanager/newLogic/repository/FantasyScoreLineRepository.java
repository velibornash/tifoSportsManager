package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.FantasyScoreLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FantasyScoreLineRepository extends JpaRepository<FantasyScoreLine, Long> {

    List<FantasyScoreLine> findByFantasyTeamIdAndSeasonYear(Long fantasyTeamId, Integer seasonYear);

    List<FantasyScoreLine> findByFantasyTeamIdInAndSeasonYear(List<Long> fantasyTeamIds, Integer seasonYear);

    boolean existsByFantasyTeamIdAndSeasonYearAndWeekNumber(Long fantasyTeamId, Integer seasonYear,
                                                            Integer weekNumber);

    boolean existsByFantasyTeamIdInAndSeasonYearAndWeekNumber(List<Long> fantasyTeamIds, Integer seasonYear,
                                                              Integer weekNumber);
}
