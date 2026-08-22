package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.DecisionContext;
import org.example.footballmanager.demo.service.model.DecisionOption;
import org.example.footballmanager.demo.service.model.DecisionType;

import java.util.List;
import java.util.Random;

/**
 * Playmaking vision filter — which action types are visible to the carrier.
 * SHOT is always visible (even poor players can shoot).
 * PM 1-5: basic only (PASS/CARRY/CLEAR + SHOT). PM 6-10: + occasional CROSS/THRU.
 * PM 11+: all visible.
 */
public class VisionFilter {

    private final Random random;

    public VisionFilter(Random random) {
        this.random = random;
    }

    public void applyVisionFilter(DecisionContext ctx, List<DecisionOption> options) {
        double pm = ctx.playmaking();

        for (DecisionOption option : options) {
            DecisionType type = option.getType();
            boolean visible;

            if (type == DecisionType.CLEAR || type == DecisionType.PASS || type == DecisionType.CARRY) {
                visible = true;
            } else if (type == DecisionType.SHOT) {
                visible = true;
            } else if (type == DecisionType.THRU) {
                // THRU requires vision to spot runners behind defense.
                // Low PM players simply cannot see through-ball opportunities.
                if (pm >= 14) {
                    visible = true;
                } else if (pm >= 10) {
                    visible = random.nextDouble() < 0.60;
                } else if (pm >= 6) {
                    visible = random.nextDouble() < 0.25;
                } else {
                    visible = false; // PM < 6: cannot see thru passes at all
                }
            } else if (type == DecisionType.CROSS || type == DecisionType.CENTER) {
                if (pm >= 11) {
                    visible = true;
                } else if (pm >= 6) {
                    visible = random.nextDouble() < 0.40;
                } else {
                    visible = random.nextDouble() < 0.15;
                }
            } else {
                visible = pm >= 11;
            }

            option.setVisible(visible);
        }
    }
}
