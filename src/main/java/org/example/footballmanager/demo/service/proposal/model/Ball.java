package org.example.footballmanager.demo.service.proposal.model;

/**
 * Ball model — physics ONLY.
 * No carrier, no target, no "who called it".
 * All possession/restart attribution lives in MatchState.
 */
public class Ball {

    public enum BallState {
        IN_POSSESSION,  // carrier != null in MatchState
        IN_TRANSITION,  // moving (speed > 0), no carrier
        LOOSE           // speed == 0, no carrier
    }

    private Position position;
    private double velX;      // cells/tick
    private double velY;      // cells/tick
    private double spin;      // 0..1 effect intensity
    private boolean airborne; // true = air decel, false = ground decel

    public Ball(Position position) {
        this.position = position;
        this.velX = 0;
        this.velY = 0;
        this.spin = 0;
        this.airborne = false;
    }

    // --- Position ---
    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }

    // --- Velocity (cells/tick) ---
    public double getVelX() { return velX; }
    public double getVelY() { return velY; }
    public void setVelocity(double velX, double velY) {
        this.velX = velX;
        this.velY = velY;
    }

    /** Current speed magnitude in cells/tick. */
    public double getSpeed() {
        return Math.hypot(velX, velY);
    }

    /** Stop the ball completely (velocity -> 0, airborne -> false). */
    public void stop() {
        this.velX = 0;
        this.velY = 0;
        this.airborne = false;
    }

    // --- Spin ---
    public double getSpin() { return spin; }
    public void setSpin(double spin) { this.spin = Math.max(0, Math.min(1, spin)); }

    // --- Airborne ---
    public boolean isAirborne() { return airborne; }
    public void setAirborne(boolean airborne) { this.airborne = airborne; }

    // --- Derived state (needs carrier from MatchState) ---
    public BallState getBallState(Player carrier) {
        if (carrier != null) return BallState.IN_POSSESSION;
        if (getSpeed() > 0) return BallState.IN_TRANSITION;
        return BallState.LOOSE;
    }
}