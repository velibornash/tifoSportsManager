package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.MatchEndedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface MatchEndedEventRepository extends JpaRepository<MatchEndedEvent, Long> {
    List<MatchEndedEvent> findByMatchId(Long matchId);
}