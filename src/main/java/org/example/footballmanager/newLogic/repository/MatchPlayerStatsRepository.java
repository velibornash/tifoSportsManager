package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.MatchPlayerStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchPlayerStatsRepository extends JpaRepository<MatchPlayerStats, Long> {
    List<MatchPlayerStats> findByMatchId(Long matchId);
    List<MatchPlayerStats> findByPlayerId(Long playerId);
}