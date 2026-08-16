package org.example.footballmanager.newLogic.model.event;

public sealed interface MatchEvent
    permits GoalEvent, ShotEvent, PassEvent, DuelEvent, FoulEvent, CardEvent,
            OffsideEvent, SetPieceEvent, PenaltyEvent, InjuryEvent, SubstitutionEvent,
            MatchStartEvent, MatchEndEvent,
            PossessionStartEvent, PossessionEndEvent,
            ReceiveEvent, PassInterceptedEvent, PassIncompleteEvent,
            DribbleEvent, DribbleLostEvent,
            TackleEvent, TackleFoulEvent,
            ShotSavedEvent, ShotBlockedEvent, ShotMissedEvent,
            CrossEvent, CrossClearedEvent, CrossHeaderEvent,
            ClearanceEvent,
            GkSaveEvent, GkCatchEvent, GkPunchEvent, GkDistributionEvent,
            ThroughBallEvent, LongBallEvent, VarReviewEvent, LooseBallEvent,
            BallCarrierDecisionEvent {

    int minute();
    int tick();
    MatchEventType type();


    enum MatchEventType {
        MATCH_START, MATCH_END,
        GOAL, SHOT_ON_TARGET, SHOT_OFF_TARGET, SHOT_SAVED, SHOT_BLOCKED, SHOT_MISSED,
        PASS, PASS_SHORT, PASS_LONG, PASS_INTERCEPTED, PASS_INCOMPLETE, INTERCEPTION,
        RECEIVE,
        THROUGH_BALL, LONG_BALL,
        CROSS, CROSS_CLEARED, CROSS_HEADER,
        DRIBBLE, DRIBBLE_LOST,
        TACKLE, TACKLE_FOUL,
        DUEL,
        FOUL, FREE_KICK, PENALTY,
        YELLOW_CARD, RED_CARD,
        OFFSIDE,
        CORNER, THROW_IN, GOAL_KICK,
        CLEARANCE,
        GK_SAVE, GK_CATCH, GK_PUNCH, GK_DISTRIBUTION,
        POSSESSION_START, POSSESSION_END, LOOSE_BALL, BALL_CARRIER_DECISION,
        INJURY, SUBSTITUTION,
        VAR_REVIEW
    }
}
