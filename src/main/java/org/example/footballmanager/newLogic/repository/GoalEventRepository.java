package org.example.footballmanager.newLogic.repository;

import org.example.footballmanager.newLogic.model.event.GoalEvent;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class GoalEventRepository {
    public List<GoalEvent> findByMatchId(Long matchId) {
        return Collections.emptyList();
    }

    public List<GoalEvent> findByMatchCompetitionIdAndMatchSeasonYearAndScoredTrue(Long competitionId, Integer seasonYear) {
        return Collections.emptyList();
    }

    public List<GoalEvent> findByMatchSeasonYearAndScoredTrue(Integer seasonYear) {
        return Collections.emptyList();
    }
}
