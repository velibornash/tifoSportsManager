package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TacticsPossessionContextTest {

    @Test
    void possessionContextSelectsTheMatchingTacticalRule() {
        Map<String, Map<String, Position>> haveBall = new LinkedHashMap<>();
        Map<String, Map<String, Position>> opponentBall = new LinkedHashMap<>();
        String state = TacticsRules.ballStateKey(new Position(4.5, 4.0));
        haveBall.put("CMR", Map.of(state, new Position(1.5, 1.5)));
        opponentBall.put("CMR", Map.of(state, new Position(6.5, 5.5)));

        TacticsRules tactics = new TacticsRules(haveBall, opponentBall,
                new LinkedHashMap<>());

        assertEquals(new Position(1.5, 1.5),
                tactics.desiredCell("CMR", new Position(4.5, 4.0), "HOME", "HOME"));
        assertEquals(new Position(6.5, 5.5),
                tactics.desiredCell("CMR", new Position(4.5, 4.0), "HOME", "AWAY"));
    }
}
