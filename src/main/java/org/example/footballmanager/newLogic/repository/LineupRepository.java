package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.Lineup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LineupRepository extends JpaRepository<Lineup, Long> {
    List<Lineup> findByTeamId(Long teamId);
    Optional<Lineup> findByTeamIdAndMatchId(Long teamId, Long matchId);
}