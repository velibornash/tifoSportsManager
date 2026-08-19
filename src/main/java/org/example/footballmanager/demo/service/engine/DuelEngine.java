package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchRecorder;

import java.util.List;

/**
 * Duel detection, lifecycle, and resolution coordination.
 * Identical logic to demo/DuelEngine but using service model.
 */
public class DuelEngine {

    public static final double DEFAULT_DUEL_RADIUS = 0.5;

    private final MatchState state;
    private final DuelResolver resolver;
    private final MatchRecorder recorder;
    private final double duelRadius;

    private Player activeDuelAttacker;
    private Player activeDuelDefender;
    private DuelType activeDuelType;
    private Position activeDuelPosition;
    private boolean activeDuelResolved;

    public DuelEngine(MatchState state, DuelResolver resolver, MatchRecorder recorder) {
        this(state, resolver, recorder, DEFAULT_DUEL_RADIUS);
    }

    public DuelEngine(MatchState state, DuelResolver resolver, MatchRecorder recorder, double duelRadius) {
        this.state = state;
        this.resolver = resolver;
        this.recorder = recorder;
        this.duelRadius = duelRadius;
    }

    public void update(Action action) {
        if (action == null) {
            closeActiveDuel();
            return;
        }
        if (action.getType() == ActionType.CHASE && state.isAwayRestartPending()) {
            closeActiveDuel();
            return;
        }

        Player attacker = (action.getType() == ActionType.PASS
                || action.getType() == ActionType.CROSS
                || action.getType() == ActionType.CENTER)
                ? action.getTargetPlayer() : action.getActingPlayer();
        if (action.getType() == ActionType.CHASE) {
            attacker = closestActiveChaserToBall();
            if (attacker == null) attacker = action.getActingPlayer();
        }
        if (attacker == null) { closeActiveDuel(); return; }
        if (action.getType() == ActionType.CHASE
                && SimUtils.distance(attacker.getPosition(), state.getBall().getPosition())
                        > ActionEngine.POSSESSION_RADIUS) {
            closeActiveDuel(); return;
        }
        if (state.isBlockedAfterDuel(attacker)) { closeActiveDuel(); return; }

        Player contestTarget = contestTarget(action, attacker);
        if (contestTarget == null) { closeActiveDuel(); return; }

        Position contestPosition = contestPosition(action, attacker, contestTarget);
        Player defender = closestOpponentTo(action, contestTarget, attacker, contestPosition);
        if (defender == null || SimUtils.distance(contestPosition, defender.getPosition()) > duelRadius) {
            closeActiveDuel(); return;
        }

        DuelType type = typeFor(action);
        if (activeDuelAttacker == attacker && activeDuelDefender == defender && activeDuelType == type) {
            return;
        }

        closeActiveDuel();
        activeDuelAttacker = attacker;
        activeDuelDefender = defender;
        activeDuelType = type;
        activeDuelPosition = contestPosition;
        activeDuelResolved = false;
        recorder.appendEvent(state.getSimulationTick(), state.getRound(), action.getActionId(),
                "DUEL_START", type + " " + attacker.getLabel() + " vs " + defender.getLabel());
    }

    /** Resolve the active duel using skill-weighted probability. */
    public DuelResolver.DuelResult resolveActiveDuel(Action action) {
        if (activeDuelAttacker == null || activeDuelResolved) return null;
        activeDuelResolved = true;
        DuelResolver.DuelResult result = resolver.resolve(
                activeDuelAttacker, activeDuelDefender, activeDuelType, action);
        recorder.appendEvent(state.getSimulationTick(), state.getRound(),
                action != null ? action.getActionId() : null,
                "DUEL_RESOLVED",
                activeDuelType + " " + result.winner().getLabel() + " wins"
                        + " (att=" + result.attackerPower() + " def=" + result.defenderPower() + ")");
        return result;
    }

    public void closeAfterResolution() {
        if (activeDuelAttacker != null && activeDuelResolved) closeActiveDuel();
    }

    public Player getActiveDuelAttacker() { return activeDuelAttacker; }
    public Player getActiveDuelDefender() { return activeDuelDefender; }

    private void closeActiveDuel() {
        activeDuelAttacker = null;
        activeDuelDefender = null;
        activeDuelType = null;
        activeDuelPosition = null;
        activeDuelResolved = false;
    }

    private Player contestTarget(Action action, Player attacker) {
        return switch (action.getType()) {
            case PASS, CROSS, CENTER -> action.getTargetPlayer();
            case CHASE, CARRY, SHOT, AERIAL -> attacker;
        };
    }

    private Position contestPosition(Action action, Player attacker, Player target) {
        if (action.getType() == ActionType.CHASE) return state.getBall().getPosition();
        if (action.getType() == ActionType.PASS) {
            if (action.getPassHeight() == PassHeight.AIR) {
                return action.getActualTarget() != null ? action.getActualTarget() : target.getPosition();
            }
            return target.getPosition();
        }
        if (action.getType() == ActionType.CROSS || action.getType() == ActionType.CENTER) {
            return action.getActualTarget() != null ? action.getActualTarget() : target.getPosition();
        }
        if (action.getType() == ActionType.SHOT) {
            return ActionEngine.goalPositionFor(action.getActingPlayer().getTeam());
        }
        return attacker.getPosition();
    }

    private DuelType typeFor(Action action) {
        return switch (action.getType()) {
            case CHASE -> DuelType.CHASE_BALL;
            case CARRY -> DuelType.DRIBBLE;
            case PASS -> action.getPassHeight() == PassHeight.AIR ? DuelType.AERIAL : DuelType.RECEIVE_PASS;
            case CROSS, CENTER -> DuelType.AERIAL;
            case SHOT -> DuelType.SHOT;
            case AERIAL -> DuelType.AERIAL;
        };
    }

    private Player closestOpponentTo(Action action, Player contestTarget,
                                     Player attacker, Position position) {
        List<Player> players = state.getPlayers();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player candidate : players) {
            if (candidate == attacker || candidate.getTeam().equals(contestTarget.getTeam())) continue;
            if (action.getType() == ActionType.CHASE && !state.isActiveChaser(candidate)) continue;
            if (state.isBlockedAfterDuel(candidate)) continue;
            if (action.getType() == ActionType.SHOT && !"GK".equals(candidate.getRole())) continue;
            if (action.getType() == ActionType.AERIAL && "GK".equals(candidate.getRole())) continue;
            double distance = SimUtils.distance(candidate.getPosition(), position);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Player closestActiveChaserToBall() {
        Position ballPos = state.getBall().getPosition();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player chaser : state.getActiveChasers()) {
            if (chaser.isLocked() || state.isBlockedAfterDuel(chaser)) continue;
            double distance = SimUtils.distance(chaser.getPosition(), ballPos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = chaser;
            }
        }
        return best;
    }
}
