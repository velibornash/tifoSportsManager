package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.Position;

/** Helper engine - provides static utility methods for action execution. */
public class ActionEngine {

    public static final int SHOOT_MIN_ROW = 6; // last 2 field rows
    public static final Position GOAL_POSITION = new Position(8.0, 3.5);
    public static final Position GOAL_EXIT_POSITION = new Position(8.5, 3.5);

    /**
     * Get the goal position that a team is attacking.
     * HOME attacks toward row 8.0, AWAY attacks toward row 1.0.
     */
    public static Position goalPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_POSITION : new Position(1.0, 3.5);
    }

    /**
     * Get the goal exit position (behind goal).
     */
    public static Position goalExitPositionFor(String team) {
        return "HOME".equals(team) ? GOAL_EXIT_POSITION : new Position(-0.5, 3.5);
    }
}