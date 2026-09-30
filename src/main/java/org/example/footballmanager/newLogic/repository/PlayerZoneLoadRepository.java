package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.PlayerZoneLoad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlayerZoneLoadRepository extends JpaRepository<PlayerZoneLoad, Long> {

    List<PlayerZoneLoad> findByPlayerIdOrderByIdDesc(Long playerId);

    List<PlayerZoneLoad> findByMatchId(Long matchId);

    /**
     * Every zone load from matches played since a moment — one query for the whole world's recovery.
     *
     * <p>Added because {@code applyDailyRecovery} asked {@code recoveryFor} per player, and that asked
     * this repository per player: 16,354 players is 16,354 round trips, and the job logged
     * "7408 player(s) recovered" after <b>42 minutes</b>. The maths is unchanged, only the number of
     * queries.
     */
    @Query("SELECT load FROM PlayerZoneLoad load JOIN FETCH load.match m "
            + "WHERE m.matchDate IS NOT NULL AND m.matchDate > :after")
    List<PlayerZoneLoad> findLoadsPlayedSince(@Param("after") java.time.LocalDateTime after);
}
