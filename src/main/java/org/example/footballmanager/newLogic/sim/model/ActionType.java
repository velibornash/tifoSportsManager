package org.example.footballmanager.newLogic.sim.model;

/** Action type enum - what action is being performed. */
public enum ActionType {
    PASS,
    /** Driven pass played into the space BEHIND the defence for a forward. */
    THRU,
    /** Lofted delivery from the flank into the box, for a team-mate to attack. */
    CROSS,
    /** Lofted delivery from a wide position into the centre of the box. */
    CENTER,
    SHOT,
    DRIBBLE,
    CLEAR,
    THROW_IN,
    GOAL_KICK,
    FREE_KICK,
    CORNER,
    KICK_OFF,
    PENALTY_KICK,
    GOAL,
    OUT,
    EXTRA_TIME,
    STOPPAGE_TIME
}