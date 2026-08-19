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
    public void setCarrier(Player carrier) { this.carrier = carrier; }

    public BallState getBallState() {
        if (carrier != null) return BallState.IN_POSSESSION;
        if (target != null) return BallState.IN_TRANSITION;
        return BallState.LOOSE;
    }
}
