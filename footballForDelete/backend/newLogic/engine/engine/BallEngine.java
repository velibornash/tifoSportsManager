package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

/**
 * Ball physics in the style of the reference demos (BallTrackingDemo /
 * PlayerTrackingDemo / PlayerDecisionDemo):
 *
 * The ball never teleports. It moves at a constant speed per tick toward its
 * target, and when a pass is aimed at a player the ball chases that player's
 * live position so it always lands on a teammate — never on a stale spot.
 * Control transfers smoothly once the ball is within a small control radius;
 * there is no "snap the ball onto the receiver" jump. Passes always start from
 * the passer's feet (never from an empty patch of grass).
 */
public final class BallEngine {

    private static final java.util.Random RNG = new java.util.Random();

    /** Ball speed in units/tick (~6 units/s at 120 ticks/min, ~5x player jog speed). */
    private static final double BALL_SPEED = 3.0;
    /** Distance within which a receiver takes control (no snap needed). */
    private static final double CONTROL_RADIUS = 1.2;
    /** Interception probe radius around the ball. */
    private static final double INTERCEPT_RADIUS = 2.0;
    /** Safety cap: force arrival if the chase cannot complete. */
    private static final int MAX_TRANSIT_TICKS = 60;

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

        double targetX = state.transitTargetX;
        double targetY = state.transitTargetY;
        Long receiverId = state.pendingReceiverId;

        if (receiverId != null) {
            PlayerSnapshot receiver = state.snapshotById(receiverId);
            if (receiver != null) {
                // Chase the receiver's live position so the ball always lands on the player.
                targetX = receiver.x();
                targetY = receiver.y();
            }
        }

        double dx = targetX - state.ball.x();
        double dy = targetY - state.ball.y();
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist <= CONTROL_RADIUS) {
            completeTransit(state, receiverId, targetX, targetY);
            return;
        }

        // Interception probe: only defenders the ball passes closely get a roll.
        checkInterception(state, targetX, targetY, dist);
        if (!state.ballInTransit) return; // intercepted

        double step = Math.min(BALL_SPEED, dist - CONTROL_RADIUS);
        double nx = state.ball.x() + (dx / dist) * step;
        double ny = state.ball.y() + (dy / dist) * step;

        // Height arc so long balls visibly rise and drop back down.
        double travelled = Math.hypot(nx - state.transitStartX, ny - state.transitStartY);
        double total = Math.max(1.0, Math.hypot(targetX - state.transitStartX, targetY - state.transitStartY));
        double progress = Math.min(1.0, travelled / total);
        double z = 3.0 * Math.sin(progress * Math.PI);

        state.ball = BallState.at(nx, ny, z);

        if (state.transitTicks >= MAX_TRANSIT_TICKS) {
            completeTransit(state, receiverId, targetX, targetY);
        }
    }

    private void checkInterception(MatchState state, double targetX, double targetY, double distToTarget) {
        if (distToTarget <= CONTROL_RADIUS) return;
        if (state.pendingPassTeam == null) return;

        double bx = state.ball.x();
        double by = state.ball.y();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (snap.teamSide().equals(state.pendingPassTeam)) continue;

            // Point probe near the current ball position (kept per-tick so the
            // interception rate stays similar to the old duration-based model).
            double dist = snap.distanceToPoint(bx, by);
            if (dist < INTERCEPT_RADIUS) {
                double defendFactor = (snap.defending() + snap.pace()) / 40.0;
                double chance = 0.3 + 0.3 * defendFactor; // 30-60%
                if (RNG.nextDouble() < chance) {
                    setCarrier(state, snap.playerId(), snap.teamSide());
                    state.ballInTransit = false;
                    state.transitTicks = 0;
                    state.ball = BallState.at(snap.x(), snap.y(), 0);

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

                    state.backwardPassCount = 0;
                    state.lastBallAction = "PASS";
                    state.lastBallActionStreak = 1;
                    return;
                }
            }
        }
    }

    private void completeTransit(MatchState state, Long receiverId, double x, double y) {
        state.ballInTransit = false;
        state.ball = BallState.at(x, y, 0);
        state.transitTicks = 0;
        state.transitPossessionTeam = null;

        if (receiverId != null) {
            clearAllBallFlags(state);
            state.carrierId = receiverId;
            state.carrierTeamSide = state.pendingPassTeam;
            PlayerSnapshot receiver = state.snapshotById(receiverId);
            if (receiver != null) receiver.setHasBall(true);
        } else {
            // Ball arrives at a spot and becomes loose (cross landing zone,
            // misplaced pass, out-of-bounds transit). updateLooseBallPickup or
            // checkBallOutOfBounds takes over.
            clearAllBallFlags(state);
            state.carrierId = null;
            state.carrierTeamSide = null;
        }
        state.clearPendingPass();
    }

    private void updateWithCarrier(MatchState state) {
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) {
            state.carrierId = null;
            state.carrierTeamSide = null;
            return;
        }

        state.ball = BallState.at(carrier.x(), carrier.y(), 0);
    }

    public void startTransit(MatchState state, double targetX, double targetY,
                             int durationTicks, Long receiverId, String passTeam) {
        // A pass is struck from the passer's feet. This also prevents passes
        // starting from a stale empty spot right after a previous transit.
        if (state.carrierId != null) {
            PlayerSnapshot carrier = state.snapshotById(state.carrierId);
            if (carrier != null) {
                state.ball = BallState.at(carrier.x(), carrier.y(), 0);
            }
        }
        clearAllBallFlags(state);

        state.ballInTransit = true;
        state.transitStartX = state.ball.x();
        state.transitStartY = state.ball.y();
        state.transitTargetX = targetX;
        state.transitTargetY = targetY;
        state.transitTicks = 0;
        state.transitMaxTicks = Math.max(durationTicks, 1);
        state.pendingReceiverId = receiverId;
        state.pendingPassTeam = passTeam;
        state.carrierId = null;
        state.carrierTeamSide = null;
        state.transitPossessionTeam = passTeam;
    }

    public void setCarrier(MatchState state, Long playerId, String teamSide) {
        clearAllBallFlags(state);

        state.carrierId = playerId;
        state.carrierTeamSide = teamSide;
        state.ballInTransit = false;
        state.transitTicks = 0;
        state.transitPossessionTeam = null;
        state.clearPendingPass();

        if (playerId != null) {
            PlayerSnapshot snap = state.snapshotById(playerId);
            if (snap != null) {
                state.ball = BallState.at(snap.x(), snap.y(), 0);
                snap.setHasBall(true);
            }
        }
    }

    public void setLoose(MatchState state, double x, double y) {
        clearAllBallFlags(state);
        state.carrierId = null;
        state.carrierTeamSide = null;
        state.ballInTransit = false;
        state.transitTicks = 0;
        state.transitPossessionTeam = null;
        state.ball = BallState.at(x, y, 0);
        state.clearPendingPass();
    }

    private void clearAllBallFlags(MatchState state) {
        for (PlayerSnapshot s : state.playerSnapshots) {
            s.setHasBall(false);
        }
    }
}
