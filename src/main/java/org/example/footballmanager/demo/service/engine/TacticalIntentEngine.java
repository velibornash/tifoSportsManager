package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

/**
 * Tactical intent — assigns movement targets from TacticsRules.
 *
 * The tactical editor is authoritative — the manager decides positioning.
 * We do NOT hard-cap attackers behind the defensive line.
 *
 * Instead, after 2 consecutive offsides, the player enters an offside retreat:
 * they drop back ~2 cells behind the deepest defender until they are clearly onside,
 * then resume normal tactical positioning.
 */
public class TacticalIntentEngine {

    private static final int OFFSIDE_RETREAT_THRESHOLD = 2;
    private static final double RETREAT_BUFFER = 2.0;
    private static final double PRESS_RADIUS = 0.7;

    private final MatchState state;
    private final ActionLogService logger;
    private final GoalkeeperMovementEngine goalkeeperMovement;

    public TacticalIntentEngine(MatchState state, ActionLogService logger) {
        this.state = state;
        this.logger = logger;
        this.goalkeeperMovement = new GoalkeeperMovementEngine(state);
    }

    /** Returns the movement target for a goalkeeper, overriding the tactical editor. */
    private Position goalkeeperTarget(Player p) {
        return goalkeeperMovement.goalkeeperTarget(p);
    }

    public void assignTargets() {
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(TacticsRules.ballStateKey(state.getBall().getPosition()));
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired;
            if ("GK".equals(p.getRole())) {
                // Goalkeeper movement is a dedicated reactive override (user rule).
                desired = goalkeeperTarget(p);
            } else {
                Position tacticalDesired = state.getTacticsRules().desiredCell(
                        p.getRole(), state.getBall().getPosition(), p.getTeam());
                // User rule: defenders must NOT push deep into the opponent's
                // half when there is no tactical reason. When the ball is in our
                // own half, DEF/CB/LB/RB/DM must stay out of the opponent's half
                // (never cross the halfway line). When the ball is in the attack,
                // a defender tracks up but no further than a sensible cap so we
                // don't leave acres of space behind the line.
                tacticalDesired = applyDefensivePositionConstraint(p, tacticalDesired);
                desired = applyOffsideRetreat(p, tacticalDesired);
                desired = applyThreatOverride(p, desired);
            }
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(desired); // Smooth movement to exact desired position
        }
    }

    /**
     * Keep deep defensive players (DEF/CB/LB/RB/DM) at sane depths.
     *
     * corePrinciples §47.8: " defenders track the ball but do NOT rush
     * premasno in the opponent's half." When the ball is in our defensive half
     * the defensive line holds; full-backs may overlap only when the ball is in
     * the final third. CB/DM never cross the halfway line unless the ball is
     * already in the attacking half.
     */
    private Position applyDefensivePositionConstraint(Player p, Position desired) {
        String role = p.getRole();
        boolean home = "HOME".equals(p.getTeam());
        boolean isDefender = role.equals("DEF") || role.equals("CB")
                || role.equals("LB") || role.equals("RB") || role.equals("DM");
        if (!isDefender) return desired;

        double ballRow = state.getBall().getPosition().getRow();
        boolean ballInOwnHalf = home ? ballRow <= 4.0 : ballRow >= 4.0;
        double desiredRow = desired.getRow();
        boolean isCenterBack = role.equals("DEF") || role.equals("CB");
        boolean isDM = role.equals("DM");

        if (ballInOwnHalf) {
            // Ball in own half: strict defense. No crossing halfway line.
            // CB/DM hold very deep.
            double maxForward;
            if (isCenterBack) {
                maxForward = home ? 2.8 : 5.2;
            } else if (isDM) {
                maxForward = home ? 3.3 : 4.7;
            } else { // LB/RB
                maxForward = home ? 3.5 : 4.5;
            }

            double clampedRow;
            if (home) {
                clampedRow = Math.min(desiredRow, maxForward);
                clampedRow = Math.max(1.5, clampedRow); // Keep clear of GK
            } else {
                clampedRow = Math.max(desiredRow, maxForward);
                clampedRow = Math.min(6.5, clampedRow); // Keep clear of GK
            }
            return new Position(clampedRow, desired.getColumn());
        } else {
            // Ball in opponent's half: support but do NOT push too high.
            double maxForward;
            if (isCenterBack) {
                // CBs never go beyond halfway line + 0.3 cells (row 4.3 for HOME / 3.7 for AWAY)
                maxForward = home ? 4.3 : 3.7;
            } else if (isDM) {
                // DM never goes beyond halfway line + 0.6 cells (row 4.6 for HOME / 3.4 for AWAY)
                maxForward = home ? 4.6 : 3.4;
            } else { // LB/RB fullbacks
                // LB/RB can overlap, but cap them at row 5.2 (HOME) / 2.8 (AWAY)
                maxForward = home ? 5.2 : 2.8;
            }

            // Also, defenders should always stay behind the ball!
            // Let's ensure they are at least 0.8 cells behind the ball (except maybe fullbacks who can reach the ball's row)
            double ballBehindLimit;
            if (isCenterBack) {
                ballBehindLimit = home ? (ballRow - 1.2) : (ballRow + 1.2);
            } else if (isDM) {
                ballBehindLimit = home ? (ballRow - 0.8) : (ballRow + 0.8);
            } else {
                ballBehindLimit = home ? (ballRow - 0.4) : (ballRow + 0.4);
            }

            double clampedRow = desiredRow;
            if (home) {
                // Must be <= maxForward AND <= ballBehindLimit
                double limit = Math.min(maxForward, ballBehindLimit);
                clampedRow = Math.min(desiredRow, limit);
                clampedRow = Math.max(4.0, clampedRow); // At least up to halfway line
            } else {
                // Must be >= maxForward AND >= ballBehindLimit
                double limit = Math.max(maxForward, ballBehindLimit);
                clampedRow = Math.max(desiredRow, limit);
                clampedRow = Math.min(4.0, clampedRow); // At least up to halfway line
            }
            return new Position(clampedRow, desired.getColumn());
        }
    }

    public void refreshTargetsIfBallStateChanged() {
        String currentKey = TacticsRules.ballStateKey(state.getBall().getPosition());
        String lastKey = state.getLastTacticalBallStateKey();
        boolean cellChanged = !currentKey.equals(lastKey);
        // Always update the stored key so we can detect the next change
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(currentKey);

        // User rule (per-tick ball-position refresh): every tick every non-carrier
        // re-derives its TACTICAL desired position from the CURRENT ball position.
        // Without this, players cling to a stale cell-boundary target when the ball
        // is in flight (no carrier active) — ball drifts mid-cell while players
        // freeze. Refreshing every tick keeps everyone moving toward the shape for
        // the ball's exact current spot.
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            Position desired;
            if ("GK".equals(p.getRole())) {
                // Goalkeeper movement is a dedicated reactive override (user rule).
                desired = goalkeeperTarget(p);
            } else {
                desired = state.getTacticsRules().desiredCell(
                        p.getRole(), state.getBall().getPosition(), p.getTeam());
                desired = applyDefensivePositionConstraint(p, desired);
                desired = applyOffsideRetreat(p, desired);
                desired = applyThreatOverride(p, desired);
            }
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(desired); // Smooth movement to exact desired position
        }
    }

    /**
     * GK anchor — goalkeeper stays within a narrow band around their goal line.
     * Only leaves the anchor if they are the closest team-mate to the ball
     * (unambiguously, no other teammate within 0.5 cells of the GK-to-ball distance).
     */
    /**
     * Threat override — defensive override layer (corePrinciples §6).
     *
     * Runs after normal tactical targeting and may override the desired position
     * when an opponent presents a local threat.
     *
     * Priority order:
     * 1. OFFSIDE SAFETY — already handled by applyOffsideRetreat
     * 2. TYPE A — defensive-third isolated opponent (≤ 1.5 cells)
     * 3. TYPE B — isolated ball carrier (≤ 1.0 cell)
     * 4. TYPE C — local opponent proximity (≤ 1.0 cell)
     *
     * "One defender per threat" — closest eligible defender claims the threat
     * (§4 in threat_override_spec.md).
     */
    private Position applyThreatOverride(Player player, Position desired) {
        // Threat override is defensive movement only. Never override the carrier,
        // a locked player, a sent-off/injured player, or the goalkeeper.
        if ("GK".equals(player.getRole())) return desired;
        if (player.isSentOff() || player.isInjured() || player.isLocked()) return desired;

        // If our team has possession, this player is not a defender for this layer.
        if (state.getCarrier() != null
                && player.getTeam().equals(state.getCarrier().getTeam())) {
            return desired;
        }

        boolean home = "HOME".equals(player.getTeam());
        Player bestThreat = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        for (Player opponent : state.getPlayers()) {
            if (player.getTeam().equals(opponent.getTeam())) continue;
            if (opponent.isSentOff() || opponent.isInjured()) continue;

            double distance = SimUtils.distance(player.getPosition(), opponent.getPosition());

            // TYPE A: opponent in our defensive third, isolated from OUR OTHER
            // players within 1 cell, and close enough for the defender to press.
            boolean typeA = isDefensiveThird(opponent.getPosition().getRow(), home)
                    && isIsolated(opponent, player.getTeam(), 1.0, player)
                    && distance <= 1.5;

            // TYPE B: isolated opponent ball carrier anywhere, within 1 cell.
            boolean typeB = state.getBall().getCarrier() == opponent
                    && isIsolated(opponent, player.getTeam(), 1.0, player)
                    && distance <= 1.0;

            // TYPE C: local correction — an opponent is already within 1 cell.
            boolean typeC = distance <= 1.0;

            int priority = typeA ? 1 : (typeB ? 2 : (typeC ? 3 : Integer.MAX_VALUE));
            if (priority == Integer.MAX_VALUE) continue;

            if (priority < bestPriority
                    || (priority == bestPriority && distance < bestDistance)) {
                bestThreat = opponent;
                bestPriority = priority;
                bestDistance = distance;
            }
        }

        if (bestThreat == null) return desired;

        // One opponent threat is handled by one defender only: the closest
        // eligible defender wins the assignment.
        if (!isClosestEligibleDefender(bestThreat, player)) return desired;

        return bestThreat.getPosition();
    }

    /** Check if opponent is in our defensive third. */
    private boolean isDefensiveThird(double row, boolean homeAttacking) {
        if (homeAttacking) {
            // HOME attacks row 7, defensive third = rows 1-3
            return row <= 3.0;
        } else {
            // AWAY attacks row 1, defensive third = rows 5-7
            return row >= 5.0;
        }
    }

    /** Check if an opponent is isolated from our OTHER teammates within radius. */
    private boolean isIsolated(Player opponent, String ourTeam, double radius, Player excluding) {
        for (Player p : state.getPlayers()) {
            if (!ourTeam.equals(p.getTeam())) continue;
            if (p == excluding || p.isSentOff() || p.isInjured()) continue;
            double dist = SimUtils.distance(opponent.getPosition(), p.getPosition());
            if (dist <= radius) return false;
        }
        return true;
    }

    /** Return true only for the closest eligible defender for this threat. */
    private boolean isClosestEligibleDefender(Player threat, Player candidate) {
        double candidateDistance = SimUtils.distance(candidate.getPosition(), threat.getPosition());
        for (Player teammate : state.getPlayers()) {
            if (teammate == candidate) continue;
            if (!candidate.getTeam().equals(teammate.getTeam())) continue;
            if ("GK".equals(teammate.getRole())) continue;
            if (teammate.isSentOff() || teammate.isInjured() || teammate.isLocked()) continue;
            if (teammate == state.getCarrier()) continue;
            if (state.isActiveChaser(teammate)) continue;
            // Only OTHER defenders contest this assignment — a forward/midfielder
            // being geometrically closer must not stop the nearest defender from
            // pressing the dangerous attacker (user rule).
            if (!isDefender(teammate.getRole())) continue;

            double otherDistance = SimUtils.distance(teammate.getPosition(), threat.getPosition());
            if (otherDistance + 1e-9 < candidateDistance) return false;
        }
        return true;
    }

    private boolean isDefender(String role) {
        return role.equals("DEF") || role.equals("CB")
                || role.equals("LB") || role.equals("RB") || role.equals("DM");
    }

    private String oppositeTeam(boolean homeAttacking) {
        return homeAttacking ? "AWAY" : "HOME";
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

        // Find the most advanced outfield defender (the offside line).
        double offsideLineRow = findDeepestDefenderRow(defendingTeam, home);
        if (offsideLineRow == Double.MAX_VALUE) return desired;

        // Retreat position: pull back RETREAT_BUFFER cells behind the offside line
        double retreatRow;
        if (home) {
            // HOME attacks toward row 7. Retreat = go LOWER (toward own goal)
            retreatRow = offsideLineRow - RETREAT_BUFFER;
            retreatRow = Math.max(1.0, retreatRow);
        } else {
            // AWAY attacks toward row 1. Retreat = go HIGHER (toward own goal)
            retreatRow = offsideLineRow + RETREAT_BUFFER;
            retreatRow = Math.min(7.0, retreatRow);
        }

        // Check if player is already clearly onside (at least one non-GK defender goal-side)
        boolean isOnside = isClearlyOnside(player, defendingTeam, home);
        if (isOnside) {
            // Retreat successful — reset count and resume normal tactics
            if (player.getConsecutiveOffsideCount() > 0) {
                logger.logInfo(state, "OFFSIDE RETREAT END: " + player.getLabel()
                        + " back onside at row " + String.format("%.2f", player.getPosition().getRow())
                        + " — resuming normal positioning",
                        "INFO", player);
            }
            player.resetConsecutiveOffside();
            return desired;
        }

        // Still offside — continue retreating
        logger.logInfo(state, "OFFSIDE RETREAT: " + player.getLabel()
                + " dropping to row " + String.format("%.2f", retreatRow)
                + " (offside line " + String.format("%.2f", offsideLineRow)
                + ", count=" + player.getConsecutiveOffsideCount() + ")",
                "INFO", player);
        return new Position(retreatRow, desired.getColumn());
    }

    /**
     * Check if a player is clearly onside: at least TWO opponents (including
     * the goalkeeper) are closer to the goal they're attacking. Per the user
     * requirement, the retreat ends only when the player has ≥2 opponents
     * goal-side (FIFA offside line), then normal tactics resume.
     * Uses exact decimal positions for precision.
     */
    private boolean isClearlyOnside(Player player, String defendingTeam, boolean home) {
        double playerRow = player.getPosition().getRow();
        int opponentsGoalSide = 0;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double defRow = p.getPosition().getRow();
            boolean defenderIsGoalSide = home
                    ? defRow > playerRow   // defender is closer to row 7 than the attacker
                    : defRow < playerRow;  // defender is closer to row 1 than the attacker
            if (defenderIsGoalSide) opponentsGoalSide++;
            if (opponentsGoalSide >= 2) return true;
        }
        return false;
    }

    private double findDeepestDefenderRow(String defendingTeam, boolean homeAttacking) {
        // "Deepest" defender = most advanced defender, i.e. the one setting the offside line.
        double deepest = homeAttacking ? -Double.MAX_VALUE : Double.MAX_VALUE;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double row = p.getPosition().getRow();
            if (homeAttacking) {
                // HOME attacks row 7. Most advanced AWAY defender = HIGHEST row.
                if (row > deepest) {
                    deepest = row;
                }
            } else {
                // AWAY attacks row 1. Most advanced HOME defender = LOWEST row.
                if (row < deepest) {
                    deepest = row;
                }
            }
        }
        return deepest;
    }

    private boolean isActiveChase(Player player) {
        return state.isActiveChaser(player);
    }
}
