package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.MatchStartEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface MatchStartEventRepository extends JpaRepository<MatchStartEvent, Long> {
    List<MatchStartEvent> findByMatchId(Long matchId);
}