package org.example.footballmanager.demo;

/** Immutable event contract for the demo timeline. */
public interface SimulationEvent {
    long tick();
    int round();
    String actionId();
    Type type();

    enum Type {
        ACTION_RESULT,
        BALL_STATE_CHANGED,
        DUEL
    }
}
