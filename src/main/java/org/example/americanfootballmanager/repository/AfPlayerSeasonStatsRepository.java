package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfPlayerSeasonStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AfPlayerSeasonStatsRepository extends JpaRepository<AfPlayerSeasonStats, Long> {
    List<AfPlayerSeasonStats> findByPlayerIdOrderBySeasonYearAsc(Long playerId);
    Optional<AfPlayerSeasonStats> findByPlayerIdAndSeasonYearAndCompetitionId(Long playerId, Integer seasonYear, Long competitionId);
    void deleteByPlayerId(Long playerId);
}
