package org.example.footballmanager.demo.service.proposal.model;

/**
 * Authoritative pitch geometry and physical obstacles (goals).
 * Single source of truth for field lines, OOB zones, and goal structures.
 */
public class PitchEnvironment {

    // --- Field lines (authoritative) ---
    public static final double HOME_GOAL_LINE = 1.0;
    public static final double AWAY_GOAL_LINE = 8.0;
    public static final double LEFT_TOUCHLINE = 1.0;
    public static final double RIGHT_TOUCHLINE = 7.0;
    public static final double CENTER_ROW = 4.5;
    public static final double CENTER_COL = 4.0;

    // --- OOB zones (ball visible here for 4 ticks before restart) ---
    public static final double OOB_ROW_MIN = 0.99;   // behind HOME goal
    public static final double OOB_ROW_MAX = 8.01;   // behind AWAY goal
    public static final double OOB_COL_MIN = 0.99;   // left touchline
    public static final double OOB_COL_MAX = 7.01;   // right touchline

    // --- Goals ---
    public final GoalPhysical homeGoal;   // defended by HOME (row 1.0)
    public final GoalPhysical awayGoal;   // defended by AWAY (row 8.0)

    public PitchEnvironment() {
        this.homeGoal = new GoalPhysical(HOME_GOAL_LINE);
        this.awayGoal = new GoalPhysical(AWAY_GOAL_LINE);
    }

    /** Returns the goal defended by the given team. */
    public GoalPhysical goalDefendedBy(String team) {
        return "HOME".equals(team) ? homeGoal : awayGoal;
    }

    /** Returns the goal attacked by the given team. */
    public GoalPhysical goalAttackedBy(String team) {
        return "HOME".equals(team) ? awayGoal : homeGoal;
    }

    /** Direct accessors for engine convenience. */
    public GoalPhysical getHomeGoal() { return homeGoal; }
    public GoalPhysical getAwayGoal() { return awayGoal; }

    /** Is the position out of bounds? */
    public boolean isOOB(Position p) {
        return p.getRow() <= OOB_ROW_MIN || p.getRow() >= OOB_ROW_MAX
                || p.getColumn() <= OOB_COL_MIN || p.getColumn() >= OOB_COL_MAX;
    }

    /** Determine restart type from last touch team and ball position (which OOB side). */
    public String oobRestartType(String lastTouchTeam, Position p) {
        boolean behindHome = p.getRow() <= OOB_ROW_MIN;
        boolean behindAway = p.getRow() >= OOB_ROW_MAX;
        if (behindHome) {
            return "HOME".equals(lastTouchTeam) ? "CORNER_AWAY" : "GOAL_KICK_HOME";
        }
        if (behindAway) {
            return "HOME".equals(lastTouchTeam) ? "GOAL_KICK_AWAY" : "CORNER_HOME";
        }
        // sideline
        return "HOME".equals(lastTouchTeam) ? "THROW_IN_AWAY" : "THROW_IN_HOME";
    }
}