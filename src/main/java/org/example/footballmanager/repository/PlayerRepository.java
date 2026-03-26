package org.example.footballmanager.repository;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long>, PagingAndSortingRepository<Player, Long> {
    List<Player> findByTeamId(Long teamId);
    Optional<Player> findByNameAndTeam(String name, Team team);
    int countByTeam(Team team);
    List<Player> findByTeam(Team homeTeam);
    Collection<Player> findByTeamIdIn(List<Long> teamIds);
    List<Player> findByInjuryDaysRemainingGreaterThan(int days);

    @Modifying
    @Query("update Player p set p.age = p.age + 1")
    int incrementAgeForAllPlayers();
}
