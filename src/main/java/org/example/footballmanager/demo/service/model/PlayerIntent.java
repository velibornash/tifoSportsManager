package org.example.footballmanager.demo.service.model;

/**
 * Player intent — what a player is currently trying to do.
 * corePrinciples Section 5: tactical intent is the bridge between tactics and movement.
 * Intent drives movement, not the decision.
 */
public enum PlayerIntent {
    RETURN_TO_SHAPE,
    PRESS,
    MARK,
    INTERCEPT,
    CHASE_BALL,
    SUPPORT,
    OVERLAP,
    UNDERLAP,
    MAKE_RUN,
    HOLD_POSITION,
    TRACK_RUNNER,
    PROVIDE_DEFENSIVE_COVER,
    ATTACK_SPACE
}
