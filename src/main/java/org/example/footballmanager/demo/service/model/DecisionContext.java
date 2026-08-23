package org.example.footballmanager.demo.service.model;

import java.util.List;

/**
 * Immutable context for playmaking decision generation.
 * Carries all situational data the decision engine needs.
 */
public record DecisionContext(
        Player player,
        Ball.BallState ballState,
        Position ballPosition,
        List<Player> teammates,
        List<Player> opponents,
        double pressure,
        double danger,
        double fieldPosition,
        double playmaking,
        boolean isHome,
        boolean isGoalkeeper,
        boolean inFinalThird,
        boolean onWing,
        boolean inOpponentHalf,
        boolean canShoot,
        boolean isKickoff,
        int matchTick,
        List<DecisionOption> options
) {}
