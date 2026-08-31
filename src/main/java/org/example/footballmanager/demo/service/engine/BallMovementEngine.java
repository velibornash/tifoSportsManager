package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.model.Position;

/**
 * Ball movement — flight toward target, carrier follow, pickup distance.
 * Identical to demo/BallMovementEngine but using service model.
 */
public class BallMovementEngine {

    public static final double BALL_SPEED = 2.0;
    public static final double CARRIER_FOLLOW_SPEED = 1.5;
    public static final double PICKUP_DISTANCE = 0.5;

    private final MatchState state;

    public BallMovementEngine(MatchState state) {
        this.state = state;
    }

    public void moveBallTowardCurrentTarget() {
        Ball ball = state.getBall();
        // Read the current action's pass speed — better passers hit faster balls
        // (range 1.0..3.0 cells/tick), weaker passers lob slower ones. Fall back to
        // the constant BALL_SPEED when no action (e.g., loose ball recovery).
        double speed = BALL_SPEED;
        if (state.getAction() != null && state.getAction().getPassSpeed() > 0) {
            speed = state.getAction().getPassSpeed();
        }
        moveBallToward(ball, ball.getTarget(), speed);
    }

    public void followCarrier() {
        Ball ball = state.getBall();
        if (ball.getCarrier() == null) return;
        Position bp = ball.getPosition();
        Position cp = ball.getCarrier().getPosition();
        double dr = cp.getRow() - bp.getRow();
        double dc = cp.getColumn() - bp.getColumn();
        double dist = Math.hypot(dr, dc);
        if (dist <= CARRIER_FOLLOW_SPEED) {
            ball.setPosition(cp);
        } else {
            ball.setPosition(new Position(bp.getRow() + dr / dist * CARRIER_FOLLOW_SPEED,
                    bp.getColumn() + dc / dist * CARRIER_FOLLOW_SPEED));
        }
    }

    public static void moveBallToward(Ball ball, Position target, double speed) {
        if (target == null) return;
        Position pos = ball.getPosition();
        double dx = target.getColumn() - pos.getColumn();
        double dy = target.getRow() - pos.getRow();
        double dist = Math.hypot(dx, dy);
        if (dist <= speed) {
            ball.setPosition(new Position(target.getRow(), target.getColumn()));
        } else {
            ball.setPosition(new Position(pos.getRow() + dy / dist * speed,
                    pos.getColumn() + dx / dist * speed));
        }
    }
}
