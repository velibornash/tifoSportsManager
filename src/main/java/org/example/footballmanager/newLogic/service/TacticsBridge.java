package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.TacticRules;

import java.util.List;
import java.util.Map;

public final class TacticsBridge {

    private TacticsBridge() {}

    public static final String WE_HAVE_BALL = "WE_HAVE_BALL";
    public static final String OPPONENT_HAS_BALL = "OPPONENT_HAS_BALL";

    /**
     * Convert a runtime rule map from TeamTacticsService.getRuntimeRuleMap()
     * into newLogic TacticRules.
     *
     * The runtime map key format is "slotKey|ballStateKey|possessionContext".
     */
    public static TacticRules fromRuntimeMap(Map<String, String> runtimeRules, List<String> slotKeys) {
        TacticRules rules = TacticRules.createDefault(slotKeys);
        if (runtimeRules == null) return rules;

        for (Map.Entry<String, String> entry : runtimeRules.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            if (parts.length != 3) continue;
            String slotKey = parts[0];
            String ballStateKey = parts[1];
            String possessionCtx = parts[2];
            boolean inPossession = WE_HAVE_BALL.equals(possessionCtx);
            rules.setRule(slotKey, ballStateKey, inPossession, entry.getValue());
        }
        return rules;
    }
}
