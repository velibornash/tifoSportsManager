package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.tactics.MatchTacticOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The mid-match tactic instructions for a fixture. */
@Repository
public interface MatchTacticOrderRepository extends JpaRepository<MatchTacticOrder, Long> {

    List<MatchTacticOrder> findByFixtureIdOrderByTeamAscPositionAsc(Long fixtureId);

    List<MatchTacticOrder> findByFixtureIdAndTeamOrderByPositionAsc(Long fixtureId, String team);

    Optional<MatchTacticOrder> findByIdAndFixtureId(Long id, Long fixtureId);

    long countByFixtureIdAndTeam(Long fixtureId, String team);

    void deleteByFixtureId(Long fixtureId);
}