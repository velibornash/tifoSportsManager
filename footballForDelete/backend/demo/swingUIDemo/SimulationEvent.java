package org.example.footballmanager.demo.swingUIDemo;

/** Immutable event contract for the demo timeline. */
public interface SimulationEvent {
    long tick();
    int round();
    String actionId();
    Type type();

    enum Type {
        ACTION_STARTED,
        ACTION_RESULT,
        BALL_STATE_CHANGED,
        DUEL
    }
}
