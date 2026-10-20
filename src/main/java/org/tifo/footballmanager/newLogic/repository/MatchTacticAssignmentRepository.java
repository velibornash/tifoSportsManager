package org.tifo.footballmanager.newLogic.repository;

import org.tifo.footballmanager.newLogic.model.tactics.MatchTacticAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchTacticAssignmentRepository extends JpaRepository<MatchTacticAssignment, Long> {

    List<MatchTacticAssignment> findByFixtureIdOrderByTeamAscPriorityAsc(Long fixtureId);

    List<MatchTacticAssignment> findByFixtureIdAndTeamOrderByPriorityAsc(Long fixtureId, String team);
}