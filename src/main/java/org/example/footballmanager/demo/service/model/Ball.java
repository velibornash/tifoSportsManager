package org.example.footballmanager.demo.service.model;

/**
 * Ball model for the service layer. Data-only.
 * BallState is derived from carrier/target fields.
 */
public class Ball {

    public enum BallState {
        IN_POSSESSION,
        IN_TRANSITION,
        LOOSE
    }

    private Position position;
    private final Position initialPosition;
    private Position target;
    private Player carrier;
    private double speed;
    private boolean airborne;
    private Position rollDirection;

    public Ball(Position position, Position initialPosition) {
        this.position = position;
        this.initialPosition = initialPosition;
    }

    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }

    public Position getInitialPosition() { return initialPosition; }

    public Position getTarget() { return target; }
    public void setTarget(Position target) { this.target = target; }

    public Player getCarrier() { return carrier; }
    public void setCarrier(Player carrier) {
        this.carrier = carrier;
        // Picking the ball up ends any free rolling/flight motion (§49): a
        // controlled ball travels with the carrier, never on its own momentum.
        if (carrier != null) {
            this.speed = 0;
            this.rollDirection = null;
        }
    }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public boolean isAirborne() { return airborne; }
    public void setAirborne(boolean airborne) { this.airborne = airborne; }

    /** Unit direction for loose-ball rolling (§49.5); null when static. */
    public Position getRollDirection() { return rollDirection; }
    public void setRollDirection(Position rollDirection) { this.rollDirection = rollDirection; }

    public BallState getBallState() {
        if (carrier != null) return BallState.IN_POSSESSION;
        if (target != null) return BallState.IN_TRANSITION;
        return BallState.LOOSE;
    }
}
