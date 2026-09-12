package org.example.footballmanager.demo.service.proposal.model;

import java.util.Objects;

/** Decision option - a possible action the player can take with its score. */
public class DecisionOption {
    private final ActionType type;
    private final Player target;
    private final double score;
    private final String reason;

    public DecisionOption(ActionType type, Player target, double score, String reason) {
        this.type = Objects.requireNonNull(type);
        this.target = target;
        this.score = score;
        this.reason = reason;
    }

    public ActionType getType() { return type; }
    public Player getTarget() { return target; }
    public double getScore() { return score; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return type + " (" + String.format("%.2f", score) + ") - " + reason;
    }
}