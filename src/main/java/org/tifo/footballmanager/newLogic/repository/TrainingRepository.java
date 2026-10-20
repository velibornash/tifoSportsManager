package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.Training;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingRepository extends JpaRepository<Training, Long> {
    Optional<Training> findByPlayerId(Long playerId);
    Optional<Training> findByPlayerIdAndFormation(Long playerId, String formation);
}