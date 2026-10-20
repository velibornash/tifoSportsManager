package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BbMatchRepository extends JpaRepository<BbMatch, Long> {

    List<BbMatch> findByCompetitionIdAndSeasonYearOrderByMatchDate(Long competitionId, Integer seasonYear);

    @Query("SELECT m FROM BbMatch m WHERE (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId) AND m.seasonYear = :seasonYear ORDER BY m.roundNumber")
    List<BbMatch> findByTeamIdAndSeasonYear(@Param("teamId") Long teamId, @Param("seasonYear") Integer seasonYear);

    @Query("SELECT m FROM BbMatch m WHERE m.competitionId = :competitionId AND m.seasonYear = :seasonYear AND m.roundNumber = :round ORDER BY m.matchDate")
    List<BbMatch> findByCompetitionAndRound(@Param("competitionId") Long competitionId, @Param("seasonYear") Integer seasonYear, @Param("round") Integer round);

    List<BbMatch> findByCompetitionIdAndSeasonYearAndPlayedOrderByMatchDate(Long competitionId, Integer seasonYear, Boolean played);

    Optional<BbMatch> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);
}