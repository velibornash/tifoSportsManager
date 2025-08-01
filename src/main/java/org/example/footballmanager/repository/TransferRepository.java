package org.example.footballmanager.repository;

import org.example.footballmanager.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {
    Optional<Transfer> findByPlayerId(Long playerId);
}