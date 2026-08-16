package org.example.footballmanager.demo;

/**
 * Model lopte. Lopta poseduje svoje stanje:
 * trenutnu {@link #getPosition() position}, pocetnu
 * {@link #getInitialPosition() initialPosition} (za reset), cilj
 * {@link #getTarget() target} ka kom trenutno leti (null = nije u letu)
 * i nosioca {@link #getCarrier() carrier} (null = lopta je slobodna/leti).
 *
 * Kao i {@link Player}, ovo je ISKLJUCIVO podatkovni model — logiku
 * kretanja lopte vodi SimulationEngine.
 */
public class Ball {

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
}
