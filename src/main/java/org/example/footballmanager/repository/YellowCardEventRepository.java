package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.YellowCardEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface YellowCardEventRepository extends JpaRepository<YellowCardEvent, Long> {
    List<YellowCardEvent> findByMatchId(Long matchId);
}