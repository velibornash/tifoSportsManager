package org.example.footballmanager.demo;

/**
 * Model lopte. Lopta poseduje svoje stanje:
 * trenutnu {@link #getPosition() position}, pocetnu
 * {@link #getInitialPosition() initialPosition} (za reset), cilj
 * {@link #getTarget() target} ka kom trenutno leti (null = nije u letu)
 * i nosioca {@link #getCarrier() carrier} (null = lopta je slobodna/leti).
 *
 * Semanticko stanje lopte je izvedeno iz carrier/target:
 *  - {@link BallState#IN_POSSESSION} — carrier != null
 *  - {@link BallState#IN_TRANSITION} — target != null, carrier == null
 *  - {@link BallState#LOOSE} — carrier == null && target == null
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

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    /** Pozicija na pocetku (koristi se za reset na gol/početak). */
    public Position getInitialPosition() {
        return initialPosition;
    }

    /** Celija ka kojoj lopta trenutno leti (null = nije u letu). */
    public Position getTarget() {
        return target;
    }

    public void setTarget(Position target) {
        this.target = target;
    }

    /** Igrac koji poseduje loptu (null = slobodna). */
    public Player getCarrier() {
        return carrier;
    }

    public void setCarrier(Player carrier) {
        this.carrier = carrier;
    }

    /** Semanticko stanje lopte — izvedeno iz carrier i target. */
    public BallState getBallState() {
        if (carrier != null) return BallState.IN_POSSESSION;
        if (target != null) return BallState.IN_TRANSITION;
        return BallState.LOOSE;
    }
}
