package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AfMatchRepository extends JpaRepository<AfMatch, Long> {

    List<AfMatch> findByCompetitionIdAndSeasonYearOrderByMatchDate(Long competitionId, Integer seasonYear);

    @Query("SELECT m FROM AfMatch m WHERE (m.homeTeam.id = :teamId OR m.awayTeam.id = :teamId) AND m.seasonYear = :seasonYear ORDER BY m.roundNumber")
    List<AfMatch> findByTeamIdAndSeasonYear(@Param("teamId") Long teamId, @Param("seasonYear") Integer seasonYear);

    @Query("SELECT m FROM AfMatch m WHERE m.competitionId = :competitionId AND m.seasonYear = :seasonYear AND m.roundNumber = :round ORDER BY m.matchDate")
    List<AfMatch> findByCompetitionAndRound(@Param("competitionId") Long competitionId, @Param("seasonYear") Integer seasonYear, @Param("round") Integer round);

    List<AfMatch> findByCompetitionIdAndSeasonYearAndPlayedOrderByMatchDate(Long competitionId, Integer seasonYear, Boolean played);

    Optional<AfMatch> findByHomeTeamIdAndAwayTeamIdAndSeasonYearAndRoundNumber(Long homeTeamId, Long awayTeamId, Integer seasonYear, Integer roundNumber);
}
