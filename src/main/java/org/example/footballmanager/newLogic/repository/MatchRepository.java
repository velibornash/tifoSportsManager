package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchRepository extends JpaRepository<Match, Long> {
    List<Match> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);
    List<Match> findByCompetitionIdAndSeasonYear(Long competitionId, Integer seasonYear);
    List<Match> findBySeasonYearAndWeekNumber(Integer seasonYear, Integer weekNumber);
    Optional<Match> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);
}