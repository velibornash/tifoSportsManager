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

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE m.id = :id")
    Optional<Match> findWithTeamsById(@Param("id") Long id);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam LEFT JOIN FETCH m.homeLineup LEFT JOIN FETCH m.awayLineup WHERE m.id = :id")
    Optional<Match> findDetailedById(@Param("id") Long id);

    List<Match> findByHomeTeamIdInAndAwayTeamIdIn(List<Long> homeTeamIds, List<Long> awayTeamIds);

    List<Match> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE (m.homeTeam.id = :homeId OR m.awayTeam.id = :awayId) AND m.played = true ORDER BY m.matchDate DESC")
    List<Match> findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(@Param("homeId") Long homeId, @Param("awayId") Long awayId);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam LEFT JOIN FETCH m.homeLineup LEFT JOIN FETCH m.awayLineup LEFT JOIN FETCH m.competition LEFT JOIN FETCH m.stadium WHERE m.id = :id")
    Optional<Match> findWithTeamsAndLineupsById(@Param("id") Long id);

    @Query("SELECT m FROM Match m LEFT JOIN FETCH m.homeTeam LEFT JOIN FETCH m.awayTeam WHERE m.competition.id = :competitionId AND m.seasonYear = :seasonYear AND m.roundNumber = :roundNumber AND (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId) AND m.started = true AND m.played = false")
    List<Match> findPreparedMatchesForTeamInRound(@Param("competitionId") Long competitionId, @Param("seasonYear") Integer seasonYear, @Param("roundNumber") Integer roundNumber, @Param("teamId") Long teamId);
}