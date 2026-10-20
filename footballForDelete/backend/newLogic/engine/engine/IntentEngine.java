package org.example.footballmanager.newLogic.engine;

import org.example.footballmanager.newLogic.model.*;

import java.util.*;

public final class IntentEngine {

    private static final double CHASE_BALL_RADIUS = 20.0;
    private static final double PRESS_RADIUS = 12.0;
    private static final double DANGER_ZONE_X = 25.0;
    private static final Random RNG = new Random();

    private final Map<Long, PlayerSnapshot.Intent> intentMap = new HashMap<>();

    public void update(MatchState state, AwarenessEngine awareness) {
        for (PlayerSnapshot snap : state.playerSnapshots) {
            PlayerSnapshot.Intent current = intentMap.getOrDefault(snap.playerId(), PlayerSnapshot.Intent.RETURN_TO_SHAPE);
            PlayerSnapshot.Intent next = decideIntent(snap, state, awareness.get(snap.playerId()), current);
            intentMap.put(snap.playerId(), next);
        }
    }

    public PlayerSnapshot.Intent getIntent(long playerId) {
        return intentMap.getOrDefault(playerId, PlayerSnapshot.Intent.RETURN_TO_SHAPE);
    }

    /**
     * Override a player's intent for the current tick. Used by clear-situation
     * overrides (attacker pass reaction, offside retreat) so the movement
     * engine consumes the override target instead of a computed run/press.
     */
    public void forceIntent(long playerId, PlayerSnapshot.Intent intent) {
        intentMap.put(playerId, intent);
    }

    public double[] getTarget(long playerId, PlayerSnapshot snap, MatchState state,
                              AwarenessEngine awareness, ZonePositionCalculator zones) {
        PlayerSnapshot.Intent intent = getIntent(playerId);
        String slotKey = state.playerSlotKeys.get(playerId);

        return switch (intent) {
            case RETURN_TO_SHAPE -> snap.desiredPosition(); // Use desiredPosition from TacticalIntentEngine
            case SUPPORT -> snap.desiredPosition();
            case MARK -> markTarget(snap, state, awareness, playerId);
            case CARRY_BALL -> carryBallTarget(snap, state, zones, playerId, slotKey);
            case PRESS -> blendWithCarrier(snap, state, zones, playerId, slotKey);
            case CHASE_BALL -> new double[]{state.ball.x(), state.ball.y()};
            case HOLD_POSITION -> new double[]{snap.x(), snap.y()};
            case GK_TRACK -> findGkTarget(snap, state);
            case MAKE_RUN -> makeRunTarget(snap, state, zones, playerId, slotKey);
            case INTERCEPT, OVERLAP, UNDERLAP -> snap.desiredPosition();
        };
    }

    private double[] markTarget(PlayerSnapshot snap, MatchState state, AwarenessEngine awareness, long playerId) {
        AwarenessEngine.PlayerAwareness aw = awareness.get(playerId);
        Long targetId = aw != null ? aw.markingTargetId() : null;
        if (targetId == null) return snap.desiredPosition();
        PlayerSnapshot target = state.snapshotById(targetId);
        if (target == null) return snap.desiredPosition();
        // Stand between the marking target and our own goal, a couple of metres
        // closer to the target than to the goal line — classic centre-back mark.
        boolean home = snap.teamSide().equals("HOME");
        double goalX = home ? 0.0 : 100.0;
        double goalY = 50.0;
        double blend = 0.6; // bias towards target
        double markX = goalX + (target.x() - goalX) * blend;
        double markY = goalY + (target.y() - goalY) * blend;
        return new double[]{markX, markY};
    }

    private double[] carryBallTarget(PlayerSnapshot snap, MatchState state,
                                      ZonePositionCalculator zones, long playerId, String slotKey) {
        double[] tactical = zones.getTarget(playerId, slotKey);
        boolean home = snap.teamSide().equals("HOME");
        double goalX = home ? 96.0 : 4.0;
        double goalY = 50.0;

        double blend = 0.35;
        double targetX = tactical[0] + (goalX - tactical[0]) * blend;
        double targetY = tactical[1] + (goalY - tactical[1]) * blend * 0.3;

        return new double[]{targetX, targetY};
    }

    private double[] blendWithCarrier(PlayerSnapshot snap, MatchState state,
                                       ZonePositionCalculator zones, long playerId, String slotKey) {
        double[] tactical = zones.getTarget(playerId, slotKey);
        if (state.carrierId == null) return tactical;

        PlayerSnapshot carrier = state.snapshotById(state.carrierId);
        if (carrier == null) return tactical;

        double blend = 0.20;
        double targetX = tactical[0] + (carrier.x() - tactical[0]) * blend;
        double targetY = tactical[1] + (carrier.y() - tactical[1]) * blend;
        return new double[]{targetX, targetY};
    }

    private PlayerSnapshot.Intent decideIntent(PlayerSnapshot snap, MatchState state,
                                AwarenessEngine.PlayerAwareness aw, PlayerSnapshot.Intent current) {
        if (snap.position() == Position.GK) {
            return PlayerSnapshot.Intent.GK_TRACK;
        }

        // Ball carrier — carry ball forward towards goal
        if (aw.isBallCarrier()) {
            return PlayerSnapshot.Intent.CARRY_BALL;
        }

        if (aw.ballFree() && aw.distToBall() < CHASE_BALL_RADIUS) {
            return PlayerSnapshot.Intent.CHASE_BALL;
        }

        // Ball at center creates central run incentive for attackers and midfielders
        if (state.ball.x() > 45.0 && state.ball.x() < 55.0) {
            if (snap.position() == Position.ATT || snap.position() == Position.MID) {
                return PlayerSnapshot.Intent.MAKE_RUN;
            }
        }

        if (state.carrierId != null && !state.carrierTeamSide.equals(snap.teamSide())) {
            PlayerSnapshot carrier = state.snapshotById(state.carrierId);
            if (carrier != null) {
                double distToCarrier = snap.distanceTo(carrier);
                // Any player within PRESS_RADIUS presses the ball carrier (not just "nearest defender")
                if (distToCarrier < PRESS_RADIUS) {
                    return PlayerSnapshot.Intent.PRESS;
                }
            }
        }

        // MARK: defenders/midfielders pick up the nearest opponent attacker
        // running through their zone.
        boolean defenderOrMid = snap.position() == Position.DEF || snap.position() == Position.MID;
        if (defenderOrMid && aw.markingTargetId() != null) {
            PlayerSnapshot markerTarget = state.snapshotById(aw.markingTargetId());
            if (markerTarget != null && snap.distanceTo(markerTarget) < 9.0) {
                return PlayerSnapshot.Intent.MARK;
            }
        }

        // Attackers make forward runs only in the opponent's half to create
        // space and receiving options.
        boolean attacker = snap.position() == Position.ATT || snap.position() == Position.WNG;
        if (attacker) {
            boolean inOpponentHalf = snap.teamSide().equals("HOME") ? snap.x() > 50 : snap.x() < 50;
            if (inOpponentHalf) {
                return PlayerSnapshot.Intent.MAKE_RUN;
            }
        }

        return PlayerSnapshot.Intent.RETURN_TO_SHAPE;
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

    private double[] findGkTarget(PlayerSnapshot snap, MatchState state) {
        boolean home = snap.teamSide().equals("HOME");
        double goalX = home ? 6.0 : 94.0;
        double goalY = 50.0;

        double ballX = state.ball.x();
        double ballY = state.ball.y();

        boolean carrierExists = state.carrierId != null;
        double distToBall = Math.hypot(snap.x() - ballX, snap.y() - ballY);

        if (!carrierExists && distToBall < 15.0) {
            // GK may come off the line for a loose ball, but never beyond the
            // halfway line — a keeper that chases a loose ball across midfield
            // would leave the goal exposed and break the shape invariant.
            double chaseX = home ? Math.min(ballX, 45.0) : Math.max(ballX, 55.0);
            return new double[]{chaseX, ballY};
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
            targetX = Math.max(goalX - 1.5, Math.min(goalX + 6.0, targetX));
        } else {
            if (ballX < 30) {
                targetX = goalX - Math.min(10.0, (goalX - ballX) * 0.15);
            } else if (ballX > 70) {
                targetX = goalX - 2.0;
            } else {
                targetX = goalX - 3.0;
            }
            targetX = Math.min(goalX + 1.5, Math.max(goalX - 6.0, targetX));
        }

        return new double[]{targetX, targetY};
    }

    private double[] makeRunTarget(PlayerSnapshot snap, MatchState state,
                                       ZonePositionCalculator zones, long playerId, String slotKey) {
        double[] tactical = zones.getTarget(playerId, slotKey);
        boolean home = snap.teamSide().equals("HOME");
        double goalX = home ? 96.0 : 4.0;

        // Attackers make forward runs into the box or space between defense and midfield
        double blend = 0.55;
        double targetX = tactical[0] + (goalX - tactical[0]) * blend;
        // Stagger runs across the pitch width to stretch defense
        double targetY = tactical[1] + (RNG.nextDouble() - 0.5) * 16.0;
        targetY = Math.max(MatchState.MIN_Y + 5, Math.min(MatchState.MAX_Y - 5, targetY));

        return new double[]{targetX, targetY};
    }

    private double calculateOffsideLine(MatchState state, String defendingTeam) {
        List<Double> defenderXPositions = new ArrayList<>();

        for (PlayerSnapshot snap : state.playerSnapshots) {
            if (!snap.teamSide().equals(defendingTeam)) continue;
            if (snap.position() == Position.GK) continue;
            defenderXPositions.add(snap.x());
        }

        if (defenderXPositions.isEmpty()) return 50.0;

        // Second-to-last opponent measured from the attacker's goal.
        // HOME defends the x=0 goal (2nd-smallest x), AWAY the x=100 goal (2nd-largest).
        if ("HOME".equals(defendingTeam)) {
            defenderXPositions.sort(java.util.Comparator.naturalOrder());
        } else {
            defenderXPositions.sort(java.util.Comparator.reverseOrder());
        }

        if (defenderXPositions.size() >= 2) {
            return defenderXPositions.get(1);
        }
        return defenderXPositions.get(0);
    }
}