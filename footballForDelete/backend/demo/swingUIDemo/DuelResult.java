package org.example.footballmanager.demo.swingUIDemo;

/** Rezultat resolution sloja; ne menja stanje simulacije sam po sebi. */
public record DuelResult(
        Player winner,
        DuelOutcome outcome,
        Ball.BallState ballState,
        Player possession,
        int attackerPower,
        int defenderPower) {
}
