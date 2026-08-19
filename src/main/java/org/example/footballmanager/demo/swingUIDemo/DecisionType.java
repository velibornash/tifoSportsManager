package org.example.footballmanager.demo.swingUIDemo;

/**
 * Decision types — what the carrier *attempts* to do during their turn.
 *
 * <p>This enum is the playmaking decision layer's output vocabulary. It is
 * deliberately separate from {@link Action.Type} (CHASE, AERIAL, and the
 * action lifecycle types) because playmaking only chooses between the
 * *intentional* football actions that a player with the ball can make.
 * The {@link ActionEngine} execution methods are then invoked for each type.</p>
 *
 * <pre>
 * PASS   → short/medium pass to a teammate        (ActionEngine.executePassTo / executePass)
 * THRU   → pass played into space behind defense   (ActionEngine.executeThruPass)
 * CARRY  → dribble forward one cell                (ActionEngine.executeCarry)
 * CLEAR  → safe kick forward under pressure        (ActionEngine.executeClearance)
 * SHOT   → attempt goal                              (ActionEngine.executeShot)
 * CROSS  → cross from the wing into the box         (ActionEngine.executeCross)
 * CENTER → cross/center from deep into the box       (ActionEngine.executeCenter)
 * </pre>
 */
public enum DecisionType {
    PASS,
    THRU,
    CARRY,
    CLEAR,
    SHOT,
    CROSS,
    CENTER;

    /** Human-readable label for debug logging. */
    @Override
    public String toString() {
        return name();
    }
}
