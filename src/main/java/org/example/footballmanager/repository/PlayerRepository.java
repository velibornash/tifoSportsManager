package org.example.footballmanager.repository;

import org.example.footballmanager.model.Player;
import org.example.footballmanager.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long>, PagingAndSortingRepository<Player, Long> {
    List<Player> findByTeamId(Long teamId);
    Optional<Player> findByNameAndTeam(String name, Team team);

    int countByTeam(Team team);

    List<Player> findByTeam(Team homeTeam);
}