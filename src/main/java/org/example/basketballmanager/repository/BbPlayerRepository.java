package org.example.basketballmanager.repository;

import org.example.basketballmanager.model.BbPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BbPlayerRepository extends JpaRepository<BbPlayer, Long> {

    List<BbPlayer> findByTeamId(Long teamId);

    @Query("SELECT p FROM BbPlayer p WHERE p.team.id = :teamId ORDER BY p.position, p.jerseyNumber")
    List<BbPlayer> findByTeamIdOrderByPositionAndNumber(@Param("teamId") Long teamId);

    List<BbPlayer> findByTeamIdAndInjuredFalse(Long teamId);

    @Query("SELECT p FROM BbPlayer p WHERE p.team.competition.id = :competitionId")
    List<BbPlayer> findByCompetitionId(@Param("competitionId") Long competitionId);

    @Modifying
    @Query("UPDATE BbPlayer p SET p.stats = null, p.fatigue = 0, p.injured = false, p.injuryDaysRemaining = 0")
    void resetAllPlayers();
}