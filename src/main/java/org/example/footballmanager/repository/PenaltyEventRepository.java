package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.PenaltyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface PenaltyEventRepository extends JpaRepository<PenaltyEvent, Long> {
    List<PenaltyEvent> findByMatchId(Long matchId);
}