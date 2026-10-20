package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.FeeStructure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeeStructureRepository extends JpaRepository<FeeStructure, Long> {
    Optional<FeeStructure> findByTransferId(Long transferId);
    List<FeeStructure> findByOutstandingGreaterThan(Double amount);
}
