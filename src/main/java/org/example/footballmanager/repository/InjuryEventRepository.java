package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.InjuryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository public interface InjuryEventRepository extends JpaRepository<InjuryEvent, Long> {
    List<InjuryEvent> findByMatchId(Long matchId);
}
