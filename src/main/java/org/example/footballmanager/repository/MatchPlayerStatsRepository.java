package org.example.footballmanager.repository;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.MatchPlayerStats;
import org.example.footballmanager.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchPlayerStatsRepository extends JpaRepository<MatchPlayerStats, Long> {
    List<MatchPlayerStats> findByMatchId(Long matchId);
    List<MatchPlayerStats> findByPlayerId(Long playerId);
    List<MatchPlayerStats> findByPlayerIdIn(List<Long> playerIds);
    MatchPlayerStats findByMatchAndPlayer(Match match, Player player);
}
