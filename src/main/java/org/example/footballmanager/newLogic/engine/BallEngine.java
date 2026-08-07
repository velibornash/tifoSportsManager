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

    private static final java.util.Random RNG = new java.util.Random();

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

        // Check for interception along flight: nearby opponent may take the ball
        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(state.pendingPassTeam)) continue;
            double dist = snap.distanceToPoint(x, y);
            if (dist < 2.0) {
                double defendFactor = (snap.defending() + snap.pace()) / 40.0;
                double chance = 0.3 + 0.3 * defendFactor; // 30-60% depending on defender
                if (RNG.nextDouble() < chance) {
                    // Interception occurs
                    state.ballInTransit = false;
                    state.ball = BallState.at(snap.x(), snap.y());
                    state.carrierId = snap.playerId();
                    state.carrierTeamSide = snap.teamSide();
                    state.clearPendingPass();

                    // Add event
                    long passerId = state.lastPasserId != null ? state.lastPasserId : -1L;
                    String passerName = "";
                    if (passerId != -1L) {
                        PlayerSnapshot passer = state.snapshotById(passerId);
                        if (passer != null) passerName = passer.name();
                    }

                    state.addEvent(new org.example.footballmanager.newLogic.model.event.PassInterceptedEvent(
                        state.minute, state.tick, state.possessionChainId,
                        passerId, passerName,
                        snap.playerId(), snap.name(), snap.teamSide(),
                        "intercepted_in_flight", snap.x(), snap.y()
                    ));
                    
                    if (state.simulatorMetrics != null) state.simulatorMetrics.onInterception();

                    // reset per-possession backward pass counter
                    state.backwardPassCount = 0;
                    state.lastBallAction = "PASS";
                    state.lastBallActionStreak = 1;
                    return;
                }
            }
        }

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
        // Clear previous carrier's hasBall flag
        if (state.carrierId != null) {
            PlayerSnapshot prev = state.snapshotById(state.carrierId);
            if (prev != null) prev.setHasBall(false);
        }

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
        // Clear previous carrier hasBall
        if (state.carrierId != null && state.carrierId != playerId) {
            PlayerSnapshot prev = state.snapshotById(state.carrierId);
            if (prev != null) prev.setHasBall(false);
        }

        state.carrierId = playerId;
        state.carrierTeamSide = teamSide;
        state.ballInTransit = false;
        state.clearPendingPass();

        if (playerId != null) {
            PlayerSnapshot snap = state.snapshotById(playerId);
            if (snap != null) {
                state.ball = BallState.at(snap.x(), snap.y());
                snap.setHasBall(true);
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
