package org.example.footballmanager.demo.service.model;

import java.util.Collections;
import java.util.List;

/**
 * Structured debug trace for a player's decision — corePrinciples Sections 29-30.
 * Captures the full causal chain: context → threat → perception → actions → selection → result.
 */
public record DecisionTrace(
    int tick,
    String playerLabel,
    String team,
    String role,
    MatchPhase phase,
    Position position,
    Position ballPosition,
    ThreatLevel threatLevel,
    double threatScore,
    PlayerIntent tacticalIntent,
    List<String> candidateActions,
    List<Double> actionScores,
    String selectedAction,
    double randomContribution,
    String explanation
) {
    public static DecisionTrace empty(int tick, String playerLabel) {
        return new DecisionTrace(tick, playerLabel, "", "", MatchPhase.OPEN_PLAY,
                Position.zero(), Position.zero(), ThreatLevel.NONE, 0,
                PlayerIntent.RETURN_TO_SHAPE, Collections.emptyList(),
                Collections.emptyList(), "", 0, "");
    }

    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[TICK ").append(tick).append("] ").append(playerLabel)
          .append(" (").append(team).append(" ").append(role).append(")\n");
        sb.append("  Phase: ").append(phase).append("\n");
        sb.append("  Position: (").append(String.format("%.1f", position.getRow()))
          .append(", ").append(String.format("%.1f", position.getColumn())).append(")\n");
        sb.append("  Ball: (").append(String.format("%.1f", ballPosition.getRow()))
          .append(", ").append(String.format("%.1f", ballPosition.getColumn())).append(")\n");
        sb.append("  Threat: ").append(threatLevel).append(" (").append(String.format("%.2f", threatScore)).append(")\n");
        sb.append("  Intent: ").append(tacticalIntent).append("\n");
        if (!candidateActions.isEmpty()) {
            sb.append("  Candidates:\n");
            for (int i = 0; i < candidateActions.size(); i++) {
                sb.append("    ").append(candidateActions.get(i)).append(": ")
                  .append(String.format("%.3f", actionScores.get(i))).append("\n");
            }
        }
        sb.append("  Selected: ").append(selectedAction);
        if (randomContribution != 0) sb.append(" (random: +").append(String.format("%.3f", randomContribution)).append(")");
        sb.append("\n");
        if (!explanation.isEmpty()) sb.append("  Explanation: ").append(explanation).append("\n");
        return sb.toString();
    }
}
