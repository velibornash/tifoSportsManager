package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.FantasyTeamScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FantasyTeamScoreRepository extends JpaRepository<FantasyTeamScore, Long> {

    List<FantasyTeamScore> findByFantasyTeamIdAndSeasonYear(Long fantasyTeamId, Integer seasonYear);

    List<FantasyTeamScore> findByFantasyTeamIdInAndSeasonYear(List<Long> fantasyTeamIds, Integer seasonYear);

    boolean existsByFantasyTeamIdAndSeasonYearAndWeekNumber(Long fantasyTeamId, Integer seasonYear,
                                                            Integer weekNumber);
}
