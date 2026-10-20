package org.example.americanfootballmanager.repository;

import org.example.americanfootballmanager.model.AfPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AfPlayerRepository extends JpaRepository<AfPlayer, Long> {

    List<AfPlayer> findByTeamId(Long teamId);

    @Query("SELECT p FROM AfPlayer p WHERE p.team.id = :teamId ORDER BY p.position, p.jerseyNumber")
    List<AfPlayer> findByTeamIdOrderByPositionAndNumber(@Param("teamId") Long teamId);

    List<AfPlayer> findByTeamIdAndInjuredFalse(Long teamId);

    @Query("SELECT p FROM AfPlayer p WHERE p.team.competition.id = :competitionId")
    List<AfPlayer> findByCompetitionId(@Param("competitionId") Long competitionId);

    @Modifying
    @Query("UPDATE AfPlayer p SET p.stats = null, p.fatigue = 0, p.injured = false, p.injuryDaysRemaining = 0")
    void resetAllPlayers();
}
