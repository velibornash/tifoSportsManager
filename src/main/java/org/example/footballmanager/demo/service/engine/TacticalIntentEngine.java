package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

/**
 * Tactical intent — assigns movement targets from TacticsRules.
 *
 * The tactical editor is authoritative — the manager decides positioning.
 * We do NOT hard-cap attackers behind the defensive line.
 *
 * Instead, after 3 consecutive offsides, the player enters an offside retreat:
 * they drop back ~2 cells behind the deepest defender until they are clearly onside,
 * then resume normal tactical positioning.
 */
public class TacticalIntentEngine {

    private static final int OFFSIDE_RETREAT_THRESHOLD = 3;
    private static final double RETREAT_BUFFER = 2.0;
    private static final double PRESS_RADIUS = 0.7;
    private static final double GK_HOME_ROW_MIN = 0.5;
    private static final double GK_HOME_ROW_MAX = 2.0;
    private static final double GK_AWAY_ROW_MIN = 6.0;
    private static final double GK_AWAY_ROW_MAX = 7.5;

    private final MatchState state;

    public TacticalIntentEngine(MatchState state) {
        this.state = state;
    }

    public void assignTargets() {
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(TacticsRules.ballStateKey(state.getBall().getPosition()));
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired = state.getTacticsRules().desiredCell(
                    p.getRole(), state.getBall().getPosition(), p.getTeam());
            desired = applyGKAnchor(p, desired);
            desired = applyOffsideRetreat(p, desired);
            desired = applyThreatOverride(p, desired);
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(SimUtils.oneCellToward(p.getPosition(), desired));
        }
    }

    public void refreshTargetsIfBallStateChanged() {
        String currentKey = TacticsRules.ballStateKey(state.getBall().getPosition());
        String lastKey = state.getLastTacticalBallStateKey();
        boolean cellChanged = !currentKey.equals(lastKey);
        if (!cellChanged) return;

        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(currentKey);
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired = state.getTacticsRules().desiredCell(
                    p.getRole(), state.getBall().getPosition(), p.getTeam());
            desired = applyGKAnchor(p, desired);
            desired = applyOffsideRetreat(p, desired);
            desired = applyThreatOverride(p, desired);
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(SimUtils.oneCellToward(p.getPosition(), desired));
        }
    }

    /**
     * GK anchor — goalkeeper stays within a narrow band around their goal line.
     * Only leaves the anchor if they are the closest team-mate to the ball
     * (unambiguously, no other teammate within 0.5 cells of the GK-to-ball distance).
     */
    private Position applyGKAnchor(Player player, Position desired) {
        if (!"GK".equals(player.getRole())) return desired;

        boolean home = "HOME".equals(player.getTeam());
        double ballRow = state.getBall().getPosition().getRow();
        double playerRow = player.getPosition().getRow();

        // Check if GK is the closest team-mate to the ball
        double ballDist = SimUtils.distance(player.getPosition(), state.getBall().getPosition());
        boolean isClosestTeammate = true;
        for (Player p : state.getPlayers()) {
            if (p == player || !p.getTeam().equals(player.getTeam())) continue;
            if (p.isSentOff() || p.isInjured()) continue;
            double d = SimUtils.distance(p.getPosition(), state.getBall().getPosition());
            if (d < ballDist - 0.5) { // another teammate is unambiguously closer
                isClosestTeammate = false;
                break;
            }
        }

        // If GK is closest to ball, allow them to come out
        if (isClosestTeammate && ballDist < 3.0) {
            return desired; // allow tactical movement toward ball
        }

        // Otherwise anchor to goal area
        if (home) {
            double clampedRow = SimUtils.clamp(desired.getRow(), GK_HOME_ROW_MIN, GK_HOME_ROW_MAX);
            return new Position(clampedRow, desired.getColumn());
        } else {
            double clampedRow = SimUtils.clamp(desired.getRow(), GK_AWAY_ROW_MIN, GK_AWAY_ROW_MAX);
            return new Position(clampedRow, desired.getColumn());
        }
    }

    /**
     * Threat override — currently disabled.
     * Tactical positions from TacticsRules already react to ball position.
     * Press override was causing 500+ interceptions/match and zero clearances.
     */
    private Position applyThreatOverride(Player player, Position desired) {
        return desired;
    }

    /**
     * Offside retreat mechanism.
     *
     * After 3 consecutive offsides, the player enters a retreat phase:
     * - Their tactical target is overridden to pull them back ~2 cells behind the deepest defender
     * - Once they are clearly onside (no defenders ahead), the retreat ends and normal tactics resume
     * - This simulates a smart player who learns to time their runs better
     *
     * This respects the manager's tactical intent — the player only retreats when they
     * keep getting caught offside. Once they adjust, they go back to their assigned position.
     */
    private Position applyOffsideRetreat(Player player, Position desired) {
        if (player.getConsecutiveOffsideCount() < OFFSIDE_RETREAT_THRESHOLD) return desired;

        boolean home = "HOME".equals(player.getTeam());
        String defendingTeam = home ? "AWAY" : "HOME";

        // Find the deepest outfield defender
        double deepestDefenderRow = findDeepestDefenderRow(defendingTeam, home);
        if (deepestDefenderRow == Double.MAX_VALUE) return desired;

        // Retreat position: pull back RETREAT_BUFFER cells behind the deepest defender
        double retreatRow;
        if (home) {
            // HOME attacks toward row 7. Retreat = go LOWER (toward own goal)
            retreatRow = deepestDefenderRow - RETREAT_BUFFER;
            retreatRow = Math.max(1.0, retreatRow);
        } else {
            // AWAY attacks toward row 1. Retreat = go HIGHER (toward own goal)
            retreatRow = deepestDefenderRow + RETREAT_BUFFER;
            retreatRow = Math.min(7.0, retreatRow);
        }

        // Check if player is already clearly onside (no defenders ahead at all)
        boolean isOnside = isClearlyOnside(player, defendingTeam, home);
        if (isOnside) {
            // Retreat successful — reset count and resume normal tactics
            player.resetConsecutiveOffside();
            return desired;
        }

        // Still offside — continue retreating
        return new Position(retreatRow, desired.getColumn());
    }

    /**
     * Check if a player is clearly onside: at least one non-GK defender
     * is between them and the goal they're attacking.
     * Uses exact decimal positions for precision.
     */
    private boolean isClearlyOnside(Player player, String defendingTeam, boolean home) {
        double playerRow = player.getPosition().getRow();
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double defRow = p.getPosition().getRow();
            boolean defenderIsGoalSide = home
                    ? defRow > playerRow   // defender is closer to row 7 than the attacker
                    : defRow < playerRow;  // defender is closer to row 1 than the attacker
            if (defenderIsGoalSide) return true;
        }
        return false;
    }

    private double findDeepestDefenderRow(String defendingTeam, boolean homeAttacking) {
        double deepest = Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (homeAttacking) {
                // Attacking toward row 7. Deepest AWAY defender = LOWEST row.
                if (p.getPosition().getRow() < deepest) {
                    deepest = p.getPosition().getRow();
                }
            } else {
                // Attacking toward row 1. Deepest HOME defender = HIGHEST row.
                if (p.getPosition().getRow() > deepest) {
                    deepest = p.getPosition().getRow();
                }
            }
        }
        return deepest;
    }

    private boolean isActiveChase(Player player) {
        return state.isActiveChaser(player);
    }
}
