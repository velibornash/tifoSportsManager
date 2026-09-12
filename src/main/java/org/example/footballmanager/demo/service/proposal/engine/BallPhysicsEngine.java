package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;

/**
 * Ball physics engine - handles ball movement, collisions, and out-of-bounds detection.
 * Pure physics: no decision-making, no rules enforcement.
 */
public class BallPhysicsEngine {

    public static final double MAX_BALL_SPEED = 1.5; // cells/tick @ 40 TPM
    public static final double MIN_ROLLING_SPEED = 0.75;
    public static final double BRAKE_FACTOR = 0.80;
    public static final double STOP_SPEED = 0.01;
    public static final double PICKUP_DISTANCE = 0.6;

    /**
     * Move the ball toward its current target.
     * Returns true if the ball reached its target.
     */
    public boolean moveBallTowardTarget(Ball ball) {
        if (ball.getTarget() == null) return false;

        double speed = ball.getSpeed() > 0 ? ball.getSpeed() : MAX_BALL_SPEED;
        Position pos = ball.getPosition();
        Position target = ball.getTarget();

        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist <= speed) {
            ball.setPosition(new Position(target.getRow(), target.getColumn()));
            return true;
        } else {
            ball.setPosition(new Position(
                pos.getRow() + dy / dist * speed,
                pos.getColumn() + dx / dist * speed
            ));
            return false;
        }
    }

    /**
     * Move the ball with the carrier (ball follows carrier).
     */
    public void followCarrier(Ball ball) {
        if (ball.getCarrier() == null) return;

        Position bp = ball.getPosition();
        Position cp = ball.getCarrier().getPosition();
        double dr = cp.getRow() - bp.getRow();
        double dc = cp.getColumn() - bp.getColumn();
        double dist = Math.sqrt(dr * dr + dc * dc);

        double speed = MovementEngine.playerSpeedFor(ball.getCarrier().getSkills().pace())
                * MovementEngine.CARRIER_FACTOR;

        if (dist <= speed) {
            ball.setPosition(cp);
            ball.setSpeed(0);
        } else {
            ball.setPosition(new Position(
                bp.getRow() + dr / dist * speed,
                bp.getColumn() + dc / dist * speed
            ));
        }
    }

    /**
     * Move a loose ball (rolling).
     */
    public void moveLooseBall(Ball ball) {
        Position dir = ball.getRollDirection();
        if (dir == null || ball.getSpeed() <= STOP_SPEED) {
            ball.setSpeed(0);
            return;
        }

        Position pos = ball.getPosition();
        ball.setPosition(new Position(
            pos.getRow() + dir.getRow() * ball.getSpeed(),
            pos.getColumn() + dir.getColumn() * ball.getSpeed()
        ));
        ball.setSpeed(ball.getSpeed() * BRAKE_FACTOR);
    }

    /**
     * Check if the ball is out of bounds.
     * Returns the OOB type: "GOAL_LINE", "SIDELINE", or null if in play.
     */
    public String checkOutOfBounds(Ball ball) {
        Position pos = ball.getPosition();
        double row = pos.getRow();
        double col = pos.getColumn();

        if (row <= 0.99 || row >= 8.01) {
            return "GOAL_LINE";
        }
        if (col <= 0.99 || col >= 7.01) {
            return "SIDELINE";
        }
        return null;
    }

    /**
     * Check if the ball crossed the goal line (for goal kick / corner determination).
     */
    public String checkGoalLine(Ball ball, String lastTouchTeam) {
        Position pos = ball.getPosition();
        double row = pos.getRow();
        double col = pos.getColumn();

        // Ball behind HOME goal line (row <= 0.99)
        if (row <= 0.99) {
            // If last touch was AWAY (attacking), it's a goal kick for HOME
            // If last touch was HOME (defending), it's a corner for AWAY
            return "HOME".equals(lastTouchTeam) ? "CORNER_AWAY" : "GOAL_KICK_HOME";
        }

        // Ball behind AWAY goal line (row >= 8.01)
        if (row >= 8.01) {
            return "HOME".equals(lastTouchTeam) ? "GOAL_KICK_AWAY" : "CORNER_HOME";
        }

        return null;
    }

    /**
     * Check if the ball crossed the sideline (for throw-in).
     */
    public String checkSideline(Ball ball, String lastTouchTeam) {
        Position pos = ball.getPosition();
        double col = pos.getColumn();

        if (col <= 0.99) {
            // Ball went out on left side
            // Throw-in for the team that did NOT last touch the ball
            return "HOME".equals(lastTouchTeam) ? "THROW_IN_AWAY" : "THROW_IN_HOME";
        }
        if (col >= 7.01) {
            return "HOME".equals(lastTouchTeam) ? "THROW_IN_AWAY" : "THROW_IN_HOME";
        }
        return null;
    }
}