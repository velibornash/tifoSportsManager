package org.example.footballmanager.repository;

import org.example.footballmanager.model.Match;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MatchRepository extends JpaRepository<Match, Long>, PagingAndSortingRepository<Match, Long> {
    List<Match> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);
    List<Match> findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(Long homeTeamId, Long awayTeamId);
    List<Match> findByMatchDateBetween(LocalDateTime start, LocalDateTime end);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    Optional<Match> findWithTeamsById(Long id);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam", "homeLineup", "awayLineup"})
    @Query("SELECT m FROM Match m WHERE m.id = :id")
    Optional<Match> findWithTeamsAndLineupsById(@Param("id") Long id);

    @EntityGraph(attributePaths = {
            "homeTeam", "awayTeam", "competition", "stadium",
            "homeLineup", "awayLineup",
            "homeLineup.startingPlayers", "homeLineup.substitutes",
            "awayLineup.startingPlayers", "awayLineup.substitutes"
    })
    @Query("SELECT m FROM Match m WHERE m.id = :id")
    Optional<Match> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam"})
    @Query("SELECT m FROM Match m WHERE m.homeTeam.id IN :homeTeamIds AND m.awayTeam.id IN :awayTeamIds")
    List<Match> findByHomeTeamIdInAndAwayTeamIdIn(@Param("homeTeamIds") Collection<Long> homeTeamIds,
                                                  @Param("awayTeamIds") Collection<Long> awayTeamIds);

    @EntityGraph(attributePaths = {"homeTeam", "awayTeam", "competition"})
    @Query("""
            SELECT m FROM Match m
            WHERE m.competition.id = :competitionId
              AND m.seasonYear = :seasonYear
              AND m.roundNumber = :roundNumber
              AND m.played = false
              AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId)
            ORDER BY m.id DESC
            """)
    List<Match> findPreparedMatchesForTeamInRound(@Param("competitionId") Long competitionId,
                                                  @Param("seasonYear") Integer seasonYear,
                                                  @Param("roundNumber") Integer roundNumber,
                                                  @Param("teamId") Long teamId);

    List<Match> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);
    List<Match> findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<Match> findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
}
