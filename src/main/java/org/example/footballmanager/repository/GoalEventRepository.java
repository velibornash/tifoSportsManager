package org.example.footballmanager.repository;

import org.example.footballmanager.model.event.GoalEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface GoalEventRepository extends JpaRepository<GoalEvent, Long> {
    List<GoalEvent> findByMatchId(Long matchId);

    @EntityGraph(attributePaths = {"match", "match.competition", "scorer", "scorer.team", "assistant", "assistant.team"})
    List<GoalEvent> findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(Long competitionId, Integer seasonYear);

    @EntityGraph(attributePaths = {"match", "scorer", "scorer.team", "assistant", "assistant.team"})
    List<GoalEvent> findByMatchSeasonYearAndScoredTrue(Integer seasonYear);
}
