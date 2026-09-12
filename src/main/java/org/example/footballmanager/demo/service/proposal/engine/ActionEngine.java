package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.Position;

/** Helper engine — static utilities for action execution. */
public class ActionEngine {

    public static final int SHOOT_MIN_ROW = 6; // last 2 field rows (HOME row >=6, AWAY row <=2)
    public static final Position GOAL_POSITION = new Position(8.0, 4.0);   // AWAY goal center (mouth 3.5-4.5)
    public static final Position GOAL_EXIT_POSITION = new Position(8.5, 4.0);

    /** Get the goal position that a team is attacking. */
    public static Position goalPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_POSITION : new Position(1.0, 4.0);
    }

    /** Get the goal exit position (behind goal). */
    public static Position goalExitPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_EXIT_POSITION : new Position(0.5, 4.0);
    }
}