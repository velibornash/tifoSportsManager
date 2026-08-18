package org.example.footballmanager.demo;

/**
 * A single playmaking decision option generated during a carrier's turn.
 *
 * <p>Each option pairs a {@link DecisionType} with an optional target player
 * (the teammate the action is directed at, when applicable) and a computed
 * score that ranks how valuable this option is in the current situation. The
 * score is populated during the {@link PlaymakingDecisionEngine evaluation}
 * phase and is mutable so that visibility filtering and pressure adjustments
 * can refine it before selection.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code type} — what the carrier intends to do (PASS / THRU / CARRY / CLEAR / SHOT / CROSS / CENTER)</li>
 *   <li>{@code target} — the teammate receiving the ball for PASS/THRU, or {@code null} for CARRY/CLEAR/SHOT/CROSS/CENTER</li>
 *   <li>{@code score} — the computed desirability of this option (higher = better)</li>
 *   <li>{@code visible} — whether this option is visible to the carrier given their playmaking vision tier</li>
 *   <li>{@code reason} — human-readable explanation for debug logging</li>
 * </ul>
 */
public class DecisionOption {

    private final DecisionType type;
    private final Player target;
    private double score;
    private boolean visible;
    private String reason;

    public DecisionOption(DecisionType type, Player target, double score,
                          boolean visible, String reason) {
        this.type = type;
        this.target = target;
        this.score = score;
        this.visible = visible;
        this.reason = reason;
    }

    public DecisionOption(DecisionType type, Player target, double score, String reason) {
        this(type, target, score, true, reason);
    }

    public DecisionOption(DecisionType type, double score, String reason) {
        this(type, null, score, true, reason);
    }

    public DecisionType getType() {
        return type;
    }

    public Player getTarget() {
        return target;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return type
                + (target != null ? "→" + target.getLabel() : "")
                + " [score=" + String.format("%.1f", score) + "]"
                + " | " + reason;
    }
}
