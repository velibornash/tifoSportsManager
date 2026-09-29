package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerZoneLoadRepository extends JpaRepository<PlayerZoneLoad, Long> {

    List<PlayerZoneLoad> findByPlayerIdOrderByIdDesc(Long playerId);

    List<PlayerZoneLoad> findByMatchId(Long matchId);
}
