package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.MatchState;
import org.example.footballmanager.newLogic.model.PlayerSnapshot;

import java.util.HashMap;
import java.util.Map;

public final class PredictionSystem {

    private static final int PREDICTION_TICKS = 30;

    private final Map<Long, double[]> predictedPositions = new HashMap<>();
    private double[] predictedBallPosition = new double[]{50.0, 50.0};

    public void update(MatchState state) {
        predictedPositions.clear();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            double[] predicted = predictPlayerPosition(snap, state);
            predictedPositions.put(snap.playerId(), predicted);
        }

        predictedBallPosition = predictBallPosition(state);
    }

    public double[] getPredictedPosition(long playerId) {
        return predictedPositions.getOrDefault(playerId, new double[]{50.0, 50.0});
    }

    public double[] getPredictedBallPosition() {
        return predictedBallPosition;
    }

    private double[] predictPlayerPosition(PlayerSnapshot snap, MatchState state) {
        double predX = snap.x();
        double predY = snap.y();

        if (state.carrierId != null && state.carrierId == snap.playerId()) {
            double goalX = snap.teamSide().equals("HOME") ? 96.0 : 4.0;
            double dx = goalX - snap.x();
            double dist = Math.abs(dx);
            if (dist > 0.1) {
                predX = snap.x() + (dx / dist) * 5.0;
            }
        } else if (state.carrierId != null) {
            PlayerSnapshot carrier = state.snapshotById(state.carrierId);
            if (carrier != null && carrier.teamSide().equals(snap.teamSide())) {
                double offsetX = snap.teamSide().equals("HOME") ? 8.0 : -8.0;
                predX = carrier.x() + offsetX;
                predY = snap.y();
            }
        }

        predX = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, predX));
        predY = Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, predY));

        return new double[]{predX, predY};
    }

    private double[] predictBallPosition(MatchState state) {
        if (state.ballInTransit) {
            return new double[]{state.transitTargetX, state.transitTargetY};
        }

        if (state.carrierId != null) {
            PlayerSnapshot carrier = state.snapshotById(state.carrierId);
            if (carrier != null) {
                double goalX = carrier.teamSide().equals("HOME") ? 96.0 : 4.0;
                double dx = goalX - carrier.x();
                double dist = Math.abs(dx);
                if (dist > 0.1) {
                    double predX = carrier.x() + (dx / dist) * 8.0;
                    predX = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, predX));
                    return new double[]{predX, carrier.y()};
                }
                return new double[]{carrier.x(), carrier.y()};
            }
        }

        return new double[]{state.ball.x(), state.ball.y()};
    }
}
