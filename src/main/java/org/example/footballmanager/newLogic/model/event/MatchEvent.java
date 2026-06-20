package org.example.footballmanager.newLogic.model.event;

public sealed interface MatchEvent
    permits GoalEvent, ShotEvent, PassEvent, DuelEvent, FoulEvent, CardEvent,
            OffsideEvent, SetPieceEvent, PenaltyEvent, InjuryEvent, SubstitutionEvent,
            MatchStartEvent, MatchEndEvent {

    int minute();
    int tick();
    MatchEventType type();


    enum MatchEventType {
        MATCH_START, MATCH_END,
        GOAL, SHOT_ON_TARGET, SHOT_OFF_TARGET,
        PASS, INTERCEPTION,
        DUEL,
        FOUL, FREE_KICK, PENALTY,
        YELLOW_CARD, RED_CARD,
        OFFSIDE,
        CORNER, THROW_IN, GOAL_KICK,
        INJURY, SUBSTITUTION
    }
}
