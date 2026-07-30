package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long>, PagingAndSortingRepository<Player, Long> {
    List<Player> findByTeamId(Long teamId);
    List<Player> findByTeamIdAndPosition(Long teamId, Position position);
    Optional<Player> findByIdAndTeamId(Long id, Long teamId);
    Optional<Player> findByNameAndTeam(String name, Team team);
    int countByTeam(Team team);
    List<Player> findByTeam(Team homeTeam);
    Collection<Player> findByTeamIdIn(List<Long> teamIds);
    List<Player> findByInjuryDaysRemainingGreaterThan(int days);

    @Query("SELECT p FROM Player p WHERE p.team.id = :teamId AND p.injured = false")
    List<Player> findAvailableByTeamId(@Param("teamId") Long teamId);

    @Modifying
    @Query("update Player p set p.age = p.age + 1")
    int incrementAgeForAllPlayers();
}
