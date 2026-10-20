package org.example.footballmanager.demo.service.model;

/**
 * A scored decision option. Mutable — visibility flag is set by VisionFilter.
 */
public class DecisionOption {

    private final DecisionType type;
    private final Player target;
    private double score;
    private final String reason;
    private boolean visible;
    private boolean straightLineCarry;
    private boolean emptyGoal;

    public DecisionOption(DecisionType type, double score, String reason) {
        this(type, null, score, reason);
    }

    public DecisionOption(DecisionType type, Player target, double score, String reason) {
        this.type = type;
        this.target = target;
        this.score = score;
        this.reason = reason;
    }

    public DecisionType getType() { return type; }
    public Player getTarget() { return target; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public String getReason() { return reason; }
    public void setReason(String reason) { /* reason is final in practice */ }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public boolean isStraightLineCarry() { return straightLineCarry; }
    public void setStraightLineCarry(boolean straightLineCarry) { this.straightLineCarry = straightLineCarry; }
    public boolean isEmptyGoal() { return emptyGoal; }
    public void setEmptyGoal(boolean emptyGoal) { this.emptyGoal = emptyGoal; }

    @Override
    public String toString() {
        return type + " score=" + String.format("%.1f", score)
                + (target != null ? " -> " + target.getLabel() : "")
                + " | " + reason;
    }
}
