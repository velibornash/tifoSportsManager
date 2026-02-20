package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.FreeKickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface FreeKickEventRepository extends JpaRepository<FreeKickEvent, Long> {
    List<FreeKickEvent> findByMatchId(Long matchId);
}