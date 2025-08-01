package org.example.footballmanager.repository;

import org.example.footballmanager.model.Training;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrainingRepository extends JpaRepository<Training, Long> {
    Optional<Training> findByPlayerId(Long playerId);
}