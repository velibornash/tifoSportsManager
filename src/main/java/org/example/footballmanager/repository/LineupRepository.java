package org.example.footballmanager.repository;

import org.example.footballmanager.model.Lineup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LineupRepository extends JpaRepository<Lineup, Long> {
    List<Lineup> findByMatchId(Long matchId);

}