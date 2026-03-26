package org.example.footballmanager.repository;

import org.example.footballmanager.model.MatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchFixtureRepository extends JpaRepository<MatchFixture, Long> {
    List<MatchFixture> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    long countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndHomeTeamIdAndAwayTeamIdAndPlayedFalse(
            Long competitionId,
            Integer seasonYear,
            Integer roundNumber,
            Long homeTeamId,
            Long awayTeamId
    );

    @Query("""
            SELECT f FROM MatchFixture f
            WHERE f.competition.id = :competitionId
              AND f.seasonYear = :seasonYear
              AND (f.homeTeam.id = :teamId OR f.awayTeam.id = :teamId)
            ORDER BY f.roundNumber ASC, f.matchDate ASC, f.id ASC
            """)
    List<MatchFixture> findTeamScheduleByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(
            @Param("competitionId") Long competitionId,
            @Param("seasonYear") Integer seasonYear,
            @Param("teamId") Long teamId
    );

    @Query("""
            SELECT f FROM MatchFixture f
            WHERE f.seasonYear = :seasonYear
              AND (f.homeTeam.id = :teamId OR f.awayTeam.id = :teamId)
            ORDER BY f.roundNumber ASC, f.matchDate ASC, f.id ASC
            """)
    List<MatchFixture> findTeamScheduleBySeasonYearOrderByRoundNumberAscMatchDateAsc(
            @Param("seasonYear") Integer seasonYear,
            @Param("teamId") Long teamId
    );
}
