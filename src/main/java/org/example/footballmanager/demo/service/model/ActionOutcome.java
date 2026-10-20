package org.example.footballmanager.demo.service.model;

/**
 * Action outcome categories for event recording.
 * Corresponds to demo/ActionOutcome but independent.
 */
public enum ActionOutcome {
    PASS_COMPLETED,
    PASS_LOOSE,
    PASS_DUEL_LOST,
    PASS_OUT,
    CARRY_COMPLETED,
    CARRY_DUEL_LOST,
    SHOT_GOAL,
    SHOT_MISS,
    SHOT_SAVE,
    CHASE_POSSESSION,
    CHASE_CONTINUE,
    CLEAR_LOOSE,
    CROSS_DUEL_LOST,
    CENTER_DUEL_LOST,
    AERIAL_LOST
}
