package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.OffsideEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface OffsideEventRepository extends JpaRepository<OffsideEvent, Long> {
    List<OffsideEvent> findByMatchId(Long matchId);
}