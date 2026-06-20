package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerRepository extends JpaRepository<Player, Long> {
    List<Player> findByTeamId(Long teamId);
    List<Player> findByTeamIdAndPosition(Long teamId, Position position);
    Optional<Player> findByIdAndTeamId(Long id, Long teamId);

    @Query("SELECT p FROM Player p WHERE p.team.id = :teamId AND p.injured = false")
    List<Player> findAvailableByTeamId(@Param("teamId") Long teamId);
}