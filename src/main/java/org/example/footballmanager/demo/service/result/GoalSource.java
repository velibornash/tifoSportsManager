package org.example.footballmanager.demo.service.result;

/**
 * Source of a goal — used for stats tracking (real football: ~25-30% of goals
 * come from set pieces — penalties, free kicks, corners, crosses, etc.).
 *
 * OPEN_PLAY     — regular shot from open play (no preceding set piece)
 * CROSS         — direct cross from wing finished with one touch
 * CENTER        — cut-back / center from goal-line finished with one touch
 * THRU_BALL     — through ball finished with one touch
 * CORNER        — header / shot immediately after a corner
 * FREE_KICK     — direct free kick (not from open play)
 * PENALTY       — penalty kick
 */
public enum GoalSource {
    OPEN_PLAY,
    CROSS,
    CENTER,
    THRU_BALL,
    CORNER,
    FREE_KICK,
    PENALTY
}
