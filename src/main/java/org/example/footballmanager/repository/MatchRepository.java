package org.example.footballmanager.repository;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface MatchRepository extends JpaRepository<Match, Long>, PagingAndSortingRepository<Match, Long> {
    List<Match> findByHomeTeamIdOrAwayTeamId(Long homeTeamId, Long awayTeamId);
    List<Match> findByHomeTeamIdOrAwayTeamIdAndPlayedTrueOrderByMatchDateDesc(Long homeTeamId, Long awayTeamId);
    List<Match> findByMatchDateBetween(LocalDateTime start, LocalDateTime end);
    @Query("SELECT m FROM Match m WHERE m.homeTeam.id IN :teamIds AND m.awayTeam.id IN :teamIds")
    List<Match> findByHomeTeamIdInAndAwayTeamIdIn(@Param("teamIds") Collection<Long> teamIds1, @Param("teamIds") Collection<Long> teamIds2);
    List<Match> findByCompetitionIdAndSeasonYearOrderByRoundNumberAscMatchDateAsc(Long competitionId, Integer seasonYear);
    List<Match> findByCompetitionIdAndSeasonYearAndRoundNumberOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
    List<Match> findByCompetitionIdAndSeasonYearAndRoundNumberAndPlayedFalseOrderByMatchDateAsc(Long competitionId, Integer seasonYear, Integer roundNumber);
}
