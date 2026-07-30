package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.*;

public final class IntentEngine {

    private static final double CHASE_BALL_RADIUS = 12.0;
    private static final double PRESS_RADIUS = 10.0;

    private final Map<Long, Intent> intentMap = new HashMap<>();

    public void update(MatchState state, AwarenessEngine awareness) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            Intent current = intentMap.getOrDefault(snap.playerId(), Intent.RETURN_TO_SHAPE);
            Intent next = decideIntent(snap, state, awareness.get(snap.playerId()), current);
            intentMap.put(snap.playerId(), next);
        }
    }

    public Intent getIntent(long playerId) {
        return intentMap.getOrDefault(playerId, Intent.RETURN_TO_SHAPE);
    }

    public double[] getTarget(long playerId, PlayerSnapshot snap, MatchState state,
                              AwarenessEngine awareness, ZonePositionCalculator zones) {
        Intent intent = getIntent(playerId);
        AwarenessEngine.PlayerAwareness aw = awareness.get(playerId);
        String slotKey = state.playerSlotKeys.get(playerId);

        return switch (intent) {
            case RETURN_TO_SHAPE -> zones.getTarget(playerId, slotKey);
            case PRESS -> findPressTarget(snap, state);
            case CHASE_BALL -> new double[]{state.ball.x(), state.ball.y()};
            case MARK -> findMarkTarget(snap, state, aw);
            case SUPPORT -> findSupportTarget(snap, state);
            case HOLD_POSITION -> new double[]{snap.x(), snap.y()};
            case GK_TRACK -> findGkTarget(snap, state);
        };
    }

    private Intent decideIntent(PlayerSnapshot snap, MatchState state,
                                AwarenessEngine.PlayerAwareness aw, Intent current) {
        if (snap.position() == Position.GK) {
            return Intent.GK_TRACK;
        }

        if (aw.isBallCarrier()) {
            return Intent.SUPPORT;
        }

        if (aw.ballFree() && aw.distToBall() < CHASE_BALL_RADIUS) {
            return Intent.CHASE_BALL;
        }

        if (state.carrierId != null && !state.carrierTeamSide.equals(snap.teamSide())) {
            PlayerSnapshot carrier = state.snapshotById(state.carrierId);
            if (carrier != null) {
                double distToCarrier = snap.distanceTo(carrier);
                if (distToCarrier < PRESS_RADIUS && isNearestDefender(snap, carrier, state)) {
                    return Intent.PRESS;
                }
            }
        }

        if (aw.markingTargetId() != null && (snap.position() == Position.DEF || snap.position() == Position.MID)) {
            PlayerSnapshot markTarget = state.snapshotById(aw.markingTargetId());
            if (markTarget != null) {
                double distToMark = snap.distanceTo(markTarget);
                if (distToMark < 10.0 && aw.distToBall() > 15.0) {
                    return Intent.MARK;
                }
            }
        }

        if (state.carrierId != null && state.carrierTeamSide.equals(snap.teamSide())) {
            if (snap.position() == Position.MID || snap.position() == Position.ATT || snap.position() == Position.WNG) {
                return Intent.SUPPORT;
            }
        }

        return Intent.RETURN_TO_SHAPE;
    }

    private boolean isNearestDefender(PlayerSnapshot snap, PlayerSnapshot carrier, MatchState state) {
        double myDist = snap.distanceTo(carrier);
        for (PlayerSnapshot other : state.playerSnapshots) {
            if (other.teamSide().equals(snap.teamSide()) && other.playerId() != snap.playerId()) {
                if (other.position() == Position.GK) continue;
                double otherDist = other.distanceTo(carrier);
                if (otherDist < myDist) return false;
            }
        }
        return true;
    }

    private double[] findPressTarget(PlayerSnapshot snap, MatchState state) {
        if (state.carrierId == null) return new double[]{snap.x(), snap.y()};
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return new double[]{snap.x(), snap.y()};
        return new double[]{carrier.x(), carrier.y()};
    }

    private double[] findMarkTarget(PlayerSnapshot snap, MatchState state, AwarenessEngine.PlayerAwareness aw) {
        if (aw.markingTargetId() == null) return new double[]{snap.x(), snap.y()};
        PlayerSnapshot target = state.snapshotById(aw.markingTargetId());
        if (target == null) return new double[]{snap.x(), snap.y()};
        return new double[]{target.x(), target.y()};
    }

    private double[] findSupportTarget(PlayerSnapshot snap, MatchState state) {
        if (state.carrierId == null) return new double[]{snap.x(), snap.y()};
        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return new double[]{snap.x(), snap.y()};

        boolean home = snap.teamSide().equals("HOME");
        double goalX = home ? 96.0 : 4.0;
        double carrierDistToGoal = Math.abs(carrier.x() - goalX);
        double snapDistToGoal = Math.abs(snap.x() - goalX);

        double targetX, targetY;
        if (snap.position() == Position.GK) {
            return findGkTarget(snap, state);
        } else if (snap.position() == Position.DEF) {
            double defBaseX = home ? 25.0 : 75.0;
            targetX = Math.max(defBaseX, Math.min(carrier.x() + (home ? 5.0 : -5.0), home ? 55.0 : 45.0));
            targetY = snap.y() + (snap.y() - 50.0) * 0.1;
        } else if (snap.position() == Position.MID) {
            targetX = carrier.x() + (home ? 8.0 : -8.0);
            targetX = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, targetX));
            targetY = snap.y();
        } else {
            double attBaseX = home ? 65.0 : 35.0;
            if (snapDistToGoal < carrierDistToGoal) {
                targetX = snap.x();
            } else {
                targetX = attBaseX + (home ? Math.max(0, carrier.x() - 40) : -Math.max(0, 60 - carrier.x()));
            }
            targetY = snap.y() + (carrier.y() - snap.y()) * 0.3;
        }

        targetX = Math.max(MatchState.MIN_X, Math.min(MatchState.MAX_X, targetX));
        targetY = Math.max(MatchState.MIN_Y, Math.min(MatchState.MAX_Y, targetY));

        return new double[]{targetX, targetY};
    }

    private double[] findGkTarget(PlayerSnapshot snap, MatchState state) {
        boolean home = snap.teamSide().equals("HOME");
        double goalX = home ? 6.0 : 94.0;
        double goalY = 50.0;

        double ballX = state.ball.x();
        double ballY = state.ball.y();

        boolean carrierExists = state.carrierId != null;
        double distToBall = Math.hypot(snap.x() - ballX, snap.y() - ballY);

        if (!carrierExists && distToBall < 15.0) {
            return new double[]{ballX, ballY};
        }

        double targetY = goalY + (ballY - goalY) * 0.35;
        targetY = Math.max(MatchState.MIN_Y + 8, Math.min(MatchState.MAX_Y - 8, targetY));

        double targetX;
        if (home) {
            if (ballX > 70) {
                targetX = goalX + Math.min(10.0, (ballX - goalX) * 0.15);
            } else if (ballX < 30) {
                targetX = goalX + 2.0;
            } else {
                targetX = goalX + 3.0;
            }
            targetX = Math.max(goalX - 1.5, Math.min(goalX + 12.0, targetX));
        } else {
            if (ballX < 30) {
                targetX = goalX - Math.min(10.0, (goalX - ballX) * 0.15);
            } else if (ballX > 70) {
                targetX = goalX - 2.0;
            } else {
                targetX = goalX - 3.0;
            }
            targetX = Math.min(goalX + 1.5, Math.max(goalX - 12.0, targetX));
        }

        return new double[]{targetX, targetY};
    }

    public enum Intent {
        RETURN_TO_SHAPE,
        PRESS,
        CHASE_BALL,
        MARK,
        SUPPORT,
        HOLD_POSITION,
        GK_TRACK
    }
}
