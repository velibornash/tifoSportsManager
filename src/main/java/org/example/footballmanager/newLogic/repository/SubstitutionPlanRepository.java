package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.SubstitutionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubstitutionPlanRepository extends JpaRepository<SubstitutionPlan, Long> {
    Optional<SubstitutionPlan> findByMatchId(Long matchId);
    void deleteByMatchId(Long matchId);
}
