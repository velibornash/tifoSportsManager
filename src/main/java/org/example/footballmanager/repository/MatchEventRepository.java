package org.example.footballmanager.repository;

import org.example.footballmanager.model.Match;
import org.example.footballmanager.model.event.GoalEvent;
import org.example.footballmanager.model.event.MatchEvent;
import org.example.footballmanager.model.event.RedCardEvent;
import org.example.footballmanager.model.event.YellowCardEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MatchEventRepository extends JpaRepository<MatchEvent, Long> {
    List<MatchEvent> findByMatch(Match match);
    List<GoalEvent> findGoalsByMatch(Match match);
    List<YellowCardEvent> findYellowCardsByMatch(Match match);
    List<RedCardEvent> findRedCardsByMatch(Match match);
}
