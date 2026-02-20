package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.SubstitutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubstitutionEventRepository extends JpaRepository<SubstitutionEvent, Long> {
    List<SubstitutionEvent> findByMatchId(Long matchId);
}