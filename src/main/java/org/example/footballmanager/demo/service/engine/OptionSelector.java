package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.DecisionContext;
import org.example.footballmanager.demo.service.model.DecisionOption;
import org.example.footballmanager.demo.service.model.DecisionType;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * PM-based decision selector — accuracy roll + weighted random fallback.
 * PM→accuracy table: {2→50%, 5→60%, 8→72%, 11→82%, 14→90%, 17→95%, 20→98%}
 *
 * CLEAR is excluded from weighted random since it is a fallback handled by the caller.
 * Base accuracy is high enough that even low-PM players usually pick the best option,
 * with occasional suboptimal choices adding realism.
 */
public class OptionSelector {

    private static final double[] PM_THRESHOLDS = {2, 5, 8, 11, 14, 17, 20};
    private static final double[] ACCURACY_VALUES = {0.50, 0.60, 0.72, 0.82, 0.90, 0.95, 0.98};

    private final Random random;
    private String lastSelectionReason = "";

    public OptionSelector(Random random) {
        this.random = random;
    }

    /** Returns the reason for the last selection (for logging). */
    public String getLastSelectionReason() { return lastSelectionReason; }

    public DecisionOption select(DecisionContext ctx, List<DecisionOption> visible) {
        lastSelectionReason = "";
        if (visible.isEmpty()) {
            lastSelectionReason = "empty visible → CARRY fallback";
            return new DecisionOption(DecisionType.CARRY, 0, "empty visible fallback");
        }

        List<DecisionOption> viable = visible.stream()
                .filter(o -> o.getScore() > 0)
                .collect(Collectors.toList());

        if (viable.isEmpty()) {
            DecisionOption fallback = visible.stream()
                    .max(Comparator.comparingDouble(DecisionOption::getScore))
                    .orElse(visible.get(0));
            lastSelectionReason = "no viable options → " + fallback.getType();
            return fallback;
        }

        DecisionOption bestViable = viable.stream()
                .max(Comparator.comparingDouble(DecisionOption::getScore))
                .orElse(viable.get(0));

        double accuracy = decisionAccuracy(ctx.playmaking());
        double roll = random.nextDouble();

        if (roll < accuracy) {
            lastSelectionReason = String.format("PM=%.0f accuracy=%.0f%% roll=%.2f → best: %s (score=%.1f)",
                    ctx.playmaking(), accuracy * 100, roll,
                    bestViable.getType(), bestViable.getScore());
            return bestViable;
        }

        // Weighted random fallback — only consider options within 80% of the best score.
        double bestScore = bestViable.getScore();
        double threshold = bestScore * 0.8;
        List<DecisionOption> weightedOptions = viable.stream()
                .filter(o -> o.getType() != DecisionType.CLEAR)
                .filter(o -> o.getScore() >= threshold)
                .collect(Collectors.toList());

        if (weightedOptions.isEmpty()) {
            lastSelectionReason = String.format("PM=%.0f accuracy=%.0f%% roll=%.2f → no close options → best: %s",
                    ctx.playmaking(), accuracy * 100, roll, bestViable.getType());
            return bestViable;
        }

        double totalWeight = weightedOptions.stream()
                .mapToDouble(o -> Math.max(0, o.getScore()))
                .sum();
        if (totalWeight <= 0) {
            lastSelectionReason = "zero total weight → best: " + bestViable.getType();
            return bestViable;
        }

        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (DecisionOption o : weightedOptions) {
            cumulative += Math.max(0, o.getScore());
            if (r <= cumulative) {
                if (o != bestViable) {
                    lastSelectionReason = String.format(
                            "PM=%.0f low accuracy (%.0f%%) → random chose %s (score=%.1f) over best %s (score=%.1f)",
                            ctx.playmaking(), accuracy * 100,
                            o.getType(), o.getScore(),
                            bestViable.getType(), bestViable.getScore());
                } else {
                    lastSelectionReason = String.format(
                            "PM=%.0f low accuracy (%.0f%%) → random chose best: %s",
                            ctx.playmaking(), accuracy * 100, o.getType());
                }
                return o;
            }
        }
        DecisionOption last = weightedOptions.get(weightedOptions.size() - 1);
        lastSelectionReason = "weighted random tail → " + last.getType();
        return last;
    }

    private double decisionAccuracy(double pm) {
        if (pm <= PM_THRESHOLDS[0]) return ACCURACY_VALUES[0];
        if (pm >= PM_THRESHOLDS[PM_THRESHOLDS.length - 1]) return ACCURACY_VALUES[ACCURACY_VALUES.length - 1];
        for (int i = 0; i < PM_THRESHOLDS.length - 1; i++) {
            if (pm >= PM_THRESHOLDS[i] && pm <= PM_THRESHOLDS[i + 1]) {
                double t = (pm - PM_THRESHOLDS[i]) / (PM_THRESHOLDS[i + 1] - PM_THRESHOLDS[i]);
                return ACCURACY_VALUES[i] + t * (ACCURACY_VALUES[i + 1] - ACCURACY_VALUES[i]);
            }
        }
        return ACCURACY_VALUES[ACCURACY_VALUES.length - 1];
    }
}
