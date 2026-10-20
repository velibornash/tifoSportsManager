package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.model.Position;

/**
 * Ball movement — flight toward target, carrier follow, pickup distance.
 * corePrinciples §49: ball speed is set ONCE by the action, then the ball
 * travels A→B independently of whatever action is active.
 */
public class BallMovementEngine {

    /** Max struck-ball speed: 14 m/s = 1.0 cells/s = 1.5 cells/tick @ 40 TPM. */
    public static final double MAX_BALL_SPEED = 1.5;
    /** Free rolling-ball floor: 7 m/s = 0.5 cells/s = 0.75 cells/tick. */
    public static final double MIN_ROLLING_SPEED = 0.75;
    /** Rapid braking applied to a loose rolling ball every tick (§49.5). */
    public static final double BRAKE_FACTOR = 0.80;
    /** Below this the rolling ball stops completely. */
    public static final double STOP_SPEED = 0.01;
    /** Ball is considered reached / receivable within this distance (feet radius).
     * 0.6 cells ≈ 8 m — a ball landing within a body length of the target is
     * controlled, which keeps completed passes complete instead of spawning a
     * loose-ball chase. Was 0.5: combined with the tighter §49 speeds the
     * receiver frequently missed a short loose ball by a whisker (~4%) and the
     * ball rolled loose, generating a fresh chase every time. */
    public static final double PICKUP_DISTANCE = 0.6;

    private final MatchState state;

    public BallMovementEngine(MatchState state) {
        this.state = state;
    }

    /**
     * Skill-driven ball speed in cells/tick (§49.2). Pass uses passing,
     * shot uses striker, clear uses defending. Formula in m/s:
     * 7.0 + (skill/20)*7.0 → max 14 m/s. Converted: /14.0 cells/s ×1.5 cells/tick.
     */
    public static double ballSpeedForSkill(double skill) {
        double ms = 7.0 + (skill / 20.0) * 7.0;
        return (ms / 14.0) * 1.5;
    }

    public void moveBallTowardCurrentTarget() {
        Ball ball = state.getBall();
        double speed = ball.getSpeed() > 0 ? ball.getSpeed() : MAX_BALL_SPEED;
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
        // Ball with a carrier moves at the carrier's pace-scaled speed (§49).
        double speed = MovementEngine.playerSpeedFor(ball.getCarrier().getSkills().pace())
                * MovementEngine.CARRIER_FACTOR;
        if (dist <= speed) {
            ball.setPosition(cp);
            ball.setSpeed(0);
        } else {
            ball.setPosition(new Position(bp.getRow() + dr / dist * speed,
                    bp.getColumn() + dc / dist * speed));
        }
    }

    /**
     * Loose ball rolling (§49.5): a free rolling ball continues in its stored
     * roll direction, decaying ×0.8 per tick until it drops below STOP_SPEED.
     * Called when the ball has no carrier and no target.
     */
    public void moveLooseBall() {
        Ball ball = state.getBall();
        Position dir = ball.getRollDirection();
        if (dir == null || ball.getSpeed() <= STOP_SPEED) {
            ball.setSpeed(0);
            return;
        }
        Position pos = ball.getPosition();
        ball.setPosition(new Position(
                pos.getRow() + dir.getRow() * ball.getSpeed(),
                pos.getColumn() + dir.getColumn() * ball.getSpeed()));
        ball.setSpeed(ball.getSpeed() * BRAKE_FACTOR);
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
