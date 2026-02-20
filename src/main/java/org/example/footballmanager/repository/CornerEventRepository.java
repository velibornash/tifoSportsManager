package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.CornerEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface CornerEventRepository extends JpaRepository<CornerEvent, Long> {
    List<CornerEvent> findByMatchId(Long matchId);
}