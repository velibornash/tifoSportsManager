package org.example.footballmanager.demo.service.model;

/**
 * Real-time spatial analysis for a player — corePrinciples Section 4.3.
 * Feeds into threat assessment and decision evaluation.
 */
public record SpaceInfo(
    double pressure,         // 0-1: how much opponent pressure the player faces
    double openness,         // 0-1: how open/free the player is
    double passLaneScore,    // 0-1: quality of passing lane to this player
    double shotLaneScore,    // 0-1: quality of shooting lane from this position
    boolean isThreatened,    // whether this player is in immediate danger
    int nearbyOpponents,     // count of opponents within pressing range
    int nearbyTeammates,     // count of teammates within support range
    double distanceToGoal,   // distance to own goal
    double distanceToBall    // distance to ball
) {
    public static SpaceInfo empty() {
        return new SpaceInfo(0, 1, 0, 0, false, 0, 0, 0, 0);
    }
}
