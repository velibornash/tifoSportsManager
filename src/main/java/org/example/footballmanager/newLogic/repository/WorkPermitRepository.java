package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.WorkPermit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkPermitRepository extends JpaRepository<WorkPermit, Long> {

    Optional<WorkPermit> findByPlayerIdAndClubIdAndSeason(Long playerId, Long clubId, Integer season);

    List<WorkPermit> findByClubIdAndSeason(Long clubId, Integer season);
}
