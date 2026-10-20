package org.example.footballmanager.demo.service.model;

/**
 * Explicit match phases — corePrinciples Section 20.
 * Player behavior depends heavily on phase.
 */
public enum MatchPhase {
    OPEN_PLAY,
    ATTACK,
    DEFENSE,
    TRANSITION_TO_ATTACK,
    TRANSITION_TO_DEFENSE,
    SET_PIECE,
    DEAD_BALL,
    KICK_OFF,
    THROW_IN,
    CORNER,
    FREE_KICK,
    PENALTY,
    GOAL,
    MATCH_END;

    public boolean isDeadBall() {
        return this == DEAD_BALL || this == KICK_OFF || this == THROW_IN
                || this == CORNER || this == FREE_KICK || this == PENALTY || this == GOAL;
    }

    public boolean isAttacking() {
        return this == ATTACK || this == TRANSITION_TO_ATTACK;
    }

    public boolean isDefending() {
        return this == DEFENSE || this == TRANSITION_TO_DEFENSE;
    }
}
