package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchFixtureRepository extends JpaRepository<MatchFixture, Long> {
    List<MatchFixture> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndPlayedFalse(Long competitionId, Integer seasonYear);
    List<MatchFixture> findBySeasonYearAndWeekNumber(Integer seasonYear, Integer weekNumber);
    Optional<MatchFixture> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);
}