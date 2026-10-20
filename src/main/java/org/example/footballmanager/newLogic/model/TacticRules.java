package org.example.footballmanager.newLogic.model;

import java.util.*;

public class TacticRules {

    public record Rule(String targetCellKey) {}

    private final Map<String, Map<String, Rule>> weHaveBallRules;
    private final Map<String, Map<String, Rule>> opponentHasBallRules;
    private final List<String> slotOrder;

    public TacticRules(List<String> slotOrder,
                       Map<String, Map<String, Rule>> weHaveBallRules,
                       Map<String, Map<String, Rule>> opponentHasBallRules) {
        this.slotOrder = List.copyOf(slotOrder);
        this.weHaveBallRules = deepCopy(weHaveBallRules);
        this.opponentHasBallRules = deepCopy(opponentHasBallRules);
    }

    public List<String> slotOrder() { return slotOrder; }

    public Rule getRule(String slotKey, String ballCellKey, boolean inPossession) {
        var map = inPossession ? weHaveBallRules : opponentHasBallRules;
        var slotRules = map.get(slotKey);
        if (slotRules == null) return null;
        return slotRules.get(ballCellKey);
    }

    public static TacticRules createDefault(List<String> slotOrder) {
        Map<String, Map<String, Rule>> weHave = new HashMap<>();
        Map<String, Map<String, Rule>> opponentHas = new HashMap<>();

        for (String slotKey : slotOrder) {
            weHave.put(slotKey, new HashMap<>());
            opponentHas.put(slotKey, new HashMap<>());
        }

        return new TacticRules(slotOrder, weHave, opponentHas);
    }

    public void setRule(String slotKey, String ballCellKey, boolean inPossession, String targetCellKey) {
        var map = inPossession ? weHaveBallRules : opponentHasBallRules;
        map.computeIfAbsent(slotKey, k -> new HashMap<>()).put(ballCellKey, new Rule(targetCellKey));
    }

    private static Map<String, Map<String, Rule>> deepCopy(Map<String, Map<String, Rule>> original) {
        Map<String, Map<String, Rule>> copy = new LinkedHashMap<>();
        for (var entry : original.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }
}
