package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.SubstitutionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubstitutionPlanRepository extends JpaRepository<SubstitutionPlan, Long> {

    /**
     * The plan for one fixture.
     *
     * <p>Keyed by fixture, not by match: a plan is written before kickoff, when no {@code Match} row
     * exists yet, and it is read by the simulation of that exact fixture.
     */
    Optional<SubstitutionPlan> findByFixtureId(Long fixtureId);

    void deleteByFixtureId(Long fixtureId);
}