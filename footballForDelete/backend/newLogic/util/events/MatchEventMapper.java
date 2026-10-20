package org.example.footballmanager.newLogic.util.events;

import lombok.extern.slf4j.Slf4j;
import org.example.footballmanager.newLogic.dto.*;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.event.*;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MatchEventMapper {

    public MatchEventDTO toDto(MatchEvent event) {
        if (event == null) {
            return null;
        }
        // Legacy mapper body commented out: old-style getters (getMinute, getScorer, etc.)
        // do not exist on the new record-based MatchEvent types, and several case types
        // (YellowCardEvent, RedCardEvent, ShotOnTargetEvent, VARReviewEvent, MatchEndedEvent,
        // ChanceEvent, CornerEvent, ThrowInEvent, GoalKickEvent, FreeKickEvent) are JPA
        // entities that do not implement the MatchEvent sealed interface.
        log.warn("MatchEventMapper.toDto is deprecated for new event model: {}", event.getClass().getSimpleName());
        return null;
    }
}
