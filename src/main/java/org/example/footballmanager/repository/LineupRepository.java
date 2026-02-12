package org.example.footballmanager.repository;

import org.example.footballmanager.model.Lineup;
import org.example.footballmanager.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LineupRepository extends JpaRepository<Lineup, Long> {
    List<Lineup> findByMatchId(Long matchId);
    Optional<Lineup> findByTeamAndFormation(Team team, String formation);

}