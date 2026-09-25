package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;

import java.util.HashMap;
import java.util.Map;

public final class MoraleSystem {

    private final Map<Long, Double> moraleMap = new HashMap<>();

    public void update(MatchState state) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            double current = moraleMap.getOrDefault(snap.playerId(), 50.0);

            if (state.carrierId != null && state.carrierId == snap.playerId()) {
                current += 0.5;
            }

            current = Math.max(0.0, Math.min(100.0, current));
            moraleMap.put(snap.playerId(), current);
        }
    }

    public double getMorale(long playerId) {
        return moraleMap.getOrDefault(playerId, 50.0);
    }

    public double getConfidenceModifier(long playerId) {
        double morale = getMorale(playerId);
        return 0.7 + (morale / 100.0) * 0.6;
    }
}
