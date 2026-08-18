package org.example.footballmanager.demo;

import java.util.List;
import java.util.Random;

/**
 * Odgovornost: PLAYMAKING VISION TIER FILTER.
 *
 * <p>Applies PM-based vision filtering to the list of generated {@link DecisionOption}s.
 * Not all options are visible to every player — higher playmaking means more
 * tactical awareness and the ability to see advanced options like THRU passes,
 * SHOTs, and set-piece deliveries.</p>
 *
 * <p>Vision tiers:</p>
 * <ul>
 *   <li><b>PM 1-5 (Poor)</b>: only CLEAR, PASS, CARRY visible.
 *       Rare 10% chance of seeing THRU.</li>
 *   <li><b>PM 6-10 (Average)</b>: CLEAR, PASS, CARRY always visible.
 *       30% chance of seeing THRU. SHOT/CROSS/CENTER never visible.</li>
 *   <li><b>PM 11+ (Good/Elite)</b>: all options visible (CLEAR, PASS, CARRY,
 *       THRU, SHOT, CROSS, CENTER).</li>
 * </ul>
 *
 * <p>If filtering removes all options, a safety net forces PASS/CARRY visible.</p>
 */
public class VisionFilter {

    private final Random random;

    public VisionFilter(Random random) {
        this.random = random;
    }

    /**
     * Applies vision filtering to the given options, setting the {@code visible}
     * flag on each option based on the carrier's playmaking skill.
     *
     * @param ctx     the decision context (provides playmaking skill)
     * @param options the generated options to filter
     */
    public void applyVisionFilter(DecisionContext ctx, List<DecisionOption> options) {
        double pm = ctx.playmaking();

        for (DecisionOption option : options) {
            DecisionType type = option.getType();
            boolean visible;

            if (type == DecisionType.CLEAR || type == DecisionType.PASS || type == DecisionType.CARRY) {
                // Basic options always visible
                visible = true;
            } else if (type == DecisionType.THRU) {
                if (pm >= 11) {
                    visible = true;
                } else if (pm >= 6) {
                    visible = random.nextDouble() < 0.30; // occasional THRU for average
                } else {
                    visible = random.nextDouble() < 0.10; // rare THRU for poor
                }
            } else {
                // SHOT, CROSS, CENTER — only visible to good+ players
                visible = pm >= 11;
            }

            option.setVisible(visible);
        }
    }
}
