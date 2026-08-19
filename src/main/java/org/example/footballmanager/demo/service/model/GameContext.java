package org.example.footballmanager.demo.service.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable match context — scoreline, time, urgency factors.
 * corePrinciples Section 25: decisions depend on game context.
 */
public record GameContext(
    int homeScore,
    int awayScore,
    int matchMinute,        // 0-90+
    MatchPhase phase,
    boolean isHomeTeam,
    double urgencyFactor,   // 0-1: how urgent the situation is (driven by time + score diff)
    double riskTolerance    // 0-1: willingness to take risks
) {
    /**
     * Urgency increases when trailing late, decreases when leading.
     * A team leading 2-0 in minute 80 has low urgency.
     * A team losing 0-1 in minute 88 has high urgency.
     */
    public static GameContext of(int homeScore, int awayScore, int matchMinute,
                                  MatchPhase phase, boolean isHomeTeam) {
        int goalDiff = isHomeTeam ? (homeScore - awayScore) : (awayScore - homeScore);
        double timeFactor = Math.min(1.0, matchMinute / 90.0);
        double urgency = 0.5;
        if (goalDiff < 0) {
            urgency = 0.5 + 0.5 * timeFactor; // trailing: urgency grows with time
        } else if (goalDiff > 0) {
            urgency = 0.5 - 0.3 * timeFactor; // leading: urgency decreases with time
        }
        double risk = goalDiff < 0 ? 0.4 + 0.4 * timeFactor : 0.5 - 0.2 * timeFactor;
        return new GameContext(homeScore, awayScore, matchMinute, phase, isHomeTeam,
                Math.max(0, Math.min(1, urgency)), Math.max(0, Math.min(1, risk)));
    }

    public boolean isLeading() {
        return isHomeTeam ? homeScore > awayScore : awayScore > homeScore;
    }

    public boolean isTrailing() {
        return isHomeTeam ? homeScore < awayScore : awayScore < homeScore;
    }

    public boolean isDrawing() {
        return homeScore == awayScore;
    }

    public int goalDifference() {
        return isHomeTeam ? (homeScore - awayScore) : (awayScore - homeScore);
    }
}
