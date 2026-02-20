package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.ShotOffTargetEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface ShotOffTargetEventRepository extends JpaRepository<ShotOffTargetEvent, Long> {
    List<ShotOffTargetEvent> findByMatchId(Long matchId);
}