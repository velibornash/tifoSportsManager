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
 * PM→accuracy table: {2→25%, 5→35%, 8→50%, 11→65%, 14→78%, 17→88%, 20→95%}
 */
public class OptionSelector {

    private static final double[] PM_THRESHOLDS = {2, 5, 8, 11, 14, 17, 20};
    private static final double[] ACCURACY_VALUES = {0.25, 0.35, 0.50, 0.65, 0.78, 0.88, 0.95};

    private final Random random;

    public OptionSelector(Random random) {
        this.random = random;
    }

    public DecisionOption select(DecisionContext ctx, List<DecisionOption> visible) {
        if (visible.isEmpty()) {
            return new DecisionOption(DecisionType.CARRY, 0, "empty visible fallback");
        }

        List<DecisionOption> viable = visible.stream()
                .filter(o -> o.getScore() > 0)
                .collect(Collectors.toList());

        if (viable.isEmpty()) {
            return visible.stream()
                    .max(Comparator.comparingDouble(DecisionOption::getScore))
                    .orElse(visible.get(0));
        }

        DecisionOption bestViable = viable.stream()
                .max(Comparator.comparingDouble(DecisionOption::getScore))
                .orElse(viable.get(0));

        double accuracy = decisionAccuracy(ctx.playmaking());
        double roll = random.nextDouble();

        if (roll < accuracy) {
            return bestViable;
        }

        double totalWeight = viable.stream()
                .mapToDouble(o -> Math.max(0, o.getScore()))
                .sum();
        if (totalWeight <= 0) return bestViable;

        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (DecisionOption o : viable) {
            cumulative += Math.max(0, o.getScore());
            if (r <= cumulative) return o;
        }
        return viable.get(viable.size() - 1);
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
