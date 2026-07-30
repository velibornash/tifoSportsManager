package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

public final class BallEngine {

    public BallEngine() {}

    public void updateBall(MatchState state) {
        if (state.ballInTransit) {
            updateTransit(state);
        } else if (state.carrierId != null) {
            updateWithCarrier(state);
        }
    }

    private void updateTransit(MatchState state) {
        state.transitTicks++;

        if (state.transitTicks >= state.transitMaxTicks) {
            state.ballInTransit = false;
            state.ball = BallState.at(state.transitTargetX, state.transitTargetY);
            state.carrierId = state.pendingReceiverId;
            state.carrierTeamSide = state.pendingPassTeam;
            state.clearPendingPass();
            return;
        }

        double progress = (double) state.transitTicks / state.transitMaxTicks;
        double x = state.transitStartX + (state.transitTargetX - state.transitStartX) * progress;
        double y = state.transitStartY + (state.transitTargetY - state.transitStartY) * progress;

        state.ball = BallState.at(x, y);
    }

    private void updateWithCarrier(MatchState state) {
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) {
            state.carrierId = null;
            state.carrierTeamSide = null;
            return;
        }

        state.ball = BallState.at(carrier.x(), carrier.y());
    }

    public void startTransit(MatchState state, double targetX, double targetY,
                             int durationTicks, Long receiverId, String passTeam) {
        state.ballInTransit = true;
        state.transitStartX = state.ball.x();
        state.transitStartY = state.ball.y();
        state.transitTargetX = targetX;
        state.transitTargetY = targetY;
        state.transitTicks = 0;
        state.transitMaxTicks = durationTicks;
        state.pendingReceiverId = receiverId;
        state.pendingPassTeam = passTeam;
        state.carrierId = null;
        state.carrierTeamSide = null;
    }

    public void setCarrier(MatchState state, Long playerId, String teamSide) {
        state.carrierId = playerId;
        state.carrierTeamSide = teamSide;
        state.ballInTransit = false;
        state.clearPendingPass();

        if (playerId != null) {
            PlayerSnapshot snap = state.snapshotById(playerId);
            if (snap != null) {
                state.ball = BallState.at(snap.x(), snap.y());
            }
        }
    }

    public void setLoose(MatchState state, double x, double y) {
        state.carrierId = null;
        state.carrierTeamSide = null;
        state.ballInTransit = false;
        state.ball = BallState.at(x, y);
        state.clearPendingPass();
    }
}
