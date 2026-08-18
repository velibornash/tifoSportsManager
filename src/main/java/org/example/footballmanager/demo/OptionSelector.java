package org.example.footballmanager.demo;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Odgovornost: SELECT ACTION BASED ON PLAYMAKING ACCURACY.
 *
 * <p>Given a {@link DecisionContext} and the list of visible {@link DecisionOption}s,
 * this selector picks one option using a two-stage process:</p>
 *
 * <ol>
 *   <li><b>Accuracy roll</b> — Playmaking determines the probability of
 *       selecting the highest-scoring visible option (the "best viable").
 *       PM→Accuracy table (linearly interpolated):
 *       {2→25%, 5→35%, 8→50%, 11→65%, 14→78%, 17→88%, 20→95%}</li>
 *   <li><b>Weighted random fallback</b> — when the accuracy roll fails,
 *       a weighted random among all viable options (score &gt; 0) is used,
 *       with probability proportional to score. This gives lower-PM players
 *       character through suboptimal decisions.</li>
 * </ol>
 *
 * <p>This is the "decision quality" layer, separate from execution quality
 * ({@link ExecutionQuality}) which handles HOW WELL a pass is executed.</p>
 */
public class OptionSelector {

    // --- PM decision accuracy table (interpolated) ---
    // Probability of selecting the highest-scoring visible option.
    private static final double[] PM_THRESHOLDS = {2, 5, 8, 11, 14, 17, 20};
    private static final double[] ACCURACY_VALUES = {0.25, 0.35, 0.50, 0.65, 0.78, 0.88, 0.95};

    private final Random random;

    public OptionSelector(Random random) {
        this.random = random;
    }

    /**
     * Selects one option from the visible options using PM-based accuracy.
     *
     * @param ctx     the decision context (provides playmaking skill)
     * @param visible list of visible options
     * @return the selected {@link DecisionOption}
     */
    public DecisionOption select(DecisionContext ctx, List<DecisionOption> visible) {
        if (visible.isEmpty()) {
            return new DecisionOption(DecisionType.CARRY, 0, "empty visible fallback");
        }

        // Find the best-scoring visible option
        DecisionOption best = visible.stream()
                .max(Comparator.comparingDouble(DecisionOption::getScore))
                .orElse(visible.get(0));

        // All viable alternatives (score > 0)
        List<DecisionOption> viable = visible.stream()
                .filter(o -> o.getScore() > 0)
                .collect(Collectors.toList());

        if (viable.isEmpty()) {
            // Only non-positive options — just pick the best
            best.setReason(best.getReason() + " [CHOSEN: only option with max score]");
            return best;
        }

        // Find best among viable
        DecisionOption bestViable = viable.stream()
                .max(Comparator.comparingDouble(DecisionOption::getScore))
                .orElse(viable.get(0));

        double accuracy = decisionAccuracy(ctx.playmaking());
        double roll = random.nextDouble();

        String selectionInfo = String.format(Locale.ROOT,
                "PM=%2.0f accuracy=%.2f roll=%.3f best=%s(%.1f)",
                ctx.playmaking(), accuracy, roll, bestViable.getType(), bestViable.getScore());

        if (roll < accuracy) {
            // Pick the best
            bestViable.setReason(bestViable.getReason()
                    + " [CHOSEN: accuracy roll " + String.format("%.3f", roll)
                    + " < " + String.format("%.2f", accuracy) + "]");
            return bestViable;
        }

        // Weighted random among viable alternatives (by score)
        double totalWeight = viable.stream()
                .mapToDouble(o -> Math.max(0, o.getScore()))
                .sum();
        if (totalWeight <= 0) {
            bestViable.setReason(bestViable.getReason()
                    + " [CHOSEN: no positive weight — picked max]");
            return bestViable;
        }

        double r = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (DecisionOption o : viable) {
            cumulative += Math.max(0, o.getScore());
            if (r <= cumulative) {
                o.setReason(o.getReason()
                        + " [CHOSEN: sub-optimal pick, roll=" + String.format("%.3f", roll)
                        + " vs acc=" + String.format("%.2f", accuracy) + " wt=" + String.format("%.1f", o.getScore()) + "]");
                return o;
            }
        }
        // Fallback to last viable
        DecisionOption last = viable.get(viable.size() - 1);
        last.setReason(last.getReason()
                + " [CHOSEN: weighted fallback]");
        return last;
    }

    /**
     * Decision accuracy from the PM table, with linear interpolation
     * between thresholds, clamped to [0.25, 0.95].
     */
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
