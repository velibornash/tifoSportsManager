package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.MatchFixture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatchFixtureRepository extends JpaRepository<MatchFixture, Long> {
    List<MatchFixture> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);

    /**
     * Every fixture a team appears in, home or away.
     *
     * <p>Spring Data cannot derive "home OR away" from a single parameter, so this is an explicit
     * query. The national-team screen needs exactly this, and pairing it with the existing
     * home-and-away-id method would force callers to invent a sentinel id for the side they do not
     * know.
     */
    @Query("select f from MatchFixture f where f.homeTeam.id = :teamId or f.awayTeam.id = :teamId")
    List<MatchFixture> findAllForTeam(@Param("teamId") Long teamId);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndPlayedFalse(Long competitionId, Integer seasonYear);
    List<MatchFixture> findBySeasonYearAndWeekNumber(Integer seasonYear, Integer weekNumber);
    Optional<MatchFixture> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);

    List<MatchFixture> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    long countByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalse(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<MatchFixture> findByCompetitionIdAndSeasonYearAndRoundNumberAndHomeTeamIdAndAwayTeamIdAndPlayedFalse(
            Long competitionId, Integer seasonYear, Integer roundNumber, Long homeTeamId, Long awayTeamId
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
