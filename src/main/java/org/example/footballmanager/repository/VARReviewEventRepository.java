package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.VARReviewEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface VARReviewEventRepository extends JpaRepository<VARReviewEvent, Long> {
    List<VARReviewEvent> findByMatchId(Long matchId);
}