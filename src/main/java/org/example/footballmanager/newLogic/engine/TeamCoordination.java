package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;
import org.example.footballmanager.newLogic.model.Position;

import java.util.HashMap;
import java.util.Map;

public final class TeamCoordination {

    private final Map<Long, CoordinationAdjustment> adjustments = new HashMap<>();

    public void update(MatchState state, IntentEngine intents, AwarenessEngine awareness) {
        adjustments.clear();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            CoordinationAdjustment adj = computeAdjustment(snap, state, intents, awareness);
            adjustments.put(snap.playerId(), adj);
        }
    }

    public CoordinationAdjustment get(long playerId) {
        return adjustments.getOrDefault(playerId, CoordinationAdjustment.NONE);
    }

    private CoordinationAdjustment computeAdjustment(PlayerSnapshot snap, MatchState state,
                                                       IntentEngine intents, AwarenessEngine awareness) {
        IntentEngine.Intent myIntent = intents.getIntent(snap.playerId());

        if (myIntent == IntentEngine.Intent.PRESS) {
            return computePressCover(snap, state, intents);
        }

        if (state.carrierId != null && !state.carrierTeamSide.equals(snap.teamSide())) {
            if (snap.position() == Position.DEF || snap.position() == Position.MID) {
                return computeDefensiveShift(snap, state);
            }
        }

        if (state.carrierId != null && state.carrierTeamSide.equals(snap.teamSide())) {
            if (snap.position() == Position.MID || snap.position() == Position.ATT) {
                return computeAttackingSpread(snap, state);
            }
        }

        return CoordinationAdjustment.NONE;
    }

    private CoordinationAdjustment computePressCover(PlayerSnapshot presser, MatchState state,
                                                       IntentEngine intents) {
        double coverX = 0, coverY = 0;
        int coverCount = 0;

        for (PlayerSnapshot teammate : state.playerSnapshots) {
            if (!teammate.teamSide().equals(presser.teamSide())) continue;
            if (teammate.playerId() == presser.playerId()) continue;

            IntentEngine.Intent teammateIntent = intents.getIntent(teammate.playerId());
            if (teammateIntent == IntentEngine.Intent.RETURN_TO_SHAPE) {
                double dist = presser.distanceTo(teammate);
                if (dist < 20.0) {
                    double offsetX = presser.teamSide().equals("HOME") ? -3.0 : 3.0;
                    coverX += offsetX;
                    coverCount++;
                }
            }
        }

        if (coverCount > 0) {
            return new CoordinationAdjustment(coverX / coverCount, coverY / coverCount);
        }

        return CoordinationAdjustment.NONE;
    }

    private CoordinationAdjustment computeDefensiveShift(PlayerSnapshot defender, MatchState state) {
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return CoordinationAdjustment.NONE;

        double dx = carrier.x() - defender.x();
        double dy = carrier.y() - defender.y();
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist > 25.0) return CoordinationAdjustment.NONE;

        double shiftX = (dx / dist) * 2.0;
        double shiftY = (dy / dist) * 1.5;

        return new CoordinationAdjustment(shiftX, shiftY);
    }

    private CoordinationAdjustment computeAttackingSpread(PlayerSnapshot attacker, MatchState state) {
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return CoordinationAdjustment.NONE;

        double dist = attacker.distanceTo(carrier);
        if (dist < 10.0) {
            double spreadX = attacker.teamSide().equals("HOME") ? 5.0 : -5.0;
            double spreadY = (attacker.y() - 50.0) * 0.3;
            return new CoordinationAdjustment(spreadX, spreadY);
        }

        return CoordinationAdjustment.NONE;
    }

    public record CoordinationAdjustment(double offsetX, double offsetY) {
        public static final CoordinationAdjustment NONE = new CoordinationAdjustment(0.0, 0.0);
    }
}
