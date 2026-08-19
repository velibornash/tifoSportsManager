package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.DecisionContext;
import org.example.footballmanager.demo.service.model.DecisionOption;
import org.example.footballmanager.demo.service.model.DecisionType;

import java.util.List;
import java.util.Random;

/**
 * Playmaking vision filter — which action types are visible to the carrier.
 * PM 1-5: basic only. PM 6-10: basic + occasional THRU. PM 11+: all visible.
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
            } else if (type == DecisionType.THRU) {
                if (pm >= 11) {
                    visible = true;
                } else if (pm >= 6) {
                    visible = random.nextDouble() < 0.30;
                } else {
                    visible = random.nextDouble() < 0.10;
                }
            } else {
                visible = pm >= 11;
            }

            option.setVisible(visible);
        }
    }
}
