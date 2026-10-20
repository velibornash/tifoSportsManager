package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbPlayerSeasonStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BbPlayerSeasonStatsRepository extends JpaRepository<BbPlayerSeasonStats, Long> {
    List<BbPlayerSeasonStats> findByPlayerIdOrderBySeasonYearAsc(Long playerId);
    Optional<BbPlayerSeasonStats> findByPlayerIdAndSeasonYearAndCompetitionId(Long playerId, Integer seasonYear, Long competitionId);
    void deleteByPlayerId(Long playerId);
}
