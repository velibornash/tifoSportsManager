package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.tactics.TacticsRules;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    private static final int OFFSIDE_RETREAT_THRESHOLD = 3;
    private static final double RETREAT_BUFFER = 2.0;
    private static final double PRESS_RADIUS = 0.7;

    private final MatchState state;
    private final ActionLogService logger;
    private final GoalkeeperMovementEngine goalkeeperMovement;
    private final CornerArrangementEngine cornerArrangement = new CornerArrangementEngine();
    // Threat-log throttle: keyed by defender id → signature of the currently
    // logged assignment ("TYPE_A:H-12:0.83"). A new line is emitted only when
    // the defender claims a DIFFERENT threat, different type, or the gap to it
    // changed by > 0.25 cells — keeps the app log readable instead of spamming
    // one line per defender per tick.
    private final Map<String, String> lastThreatLog = new HashMap<>();

    public TacticalIntentEngine(MatchState state, ActionLogService logger) {
        this.state = state;
        this.logger = logger;
        this.goalkeeperMovement = new GoalkeeperMovementEngine(state);
    }

    /** Corner arrangement — replaces tactical targets while a corner is being taken. */
    public boolean applyCornerArrangement(MatchState state) {
        return cornerArrangement.applyCornerTargets(state);
    }

    /** Marks the moment the corner is delivered so the arrangement survives the flight. */
    public void markCornerDelivered(MatchState state) {
        if (cornerArrangement.isCornerArrangementActive(state)) {
            cornerArrangement.markCornerDelivered(state);
        }
    }

    /** Returns the movement target for a goalkeeper, overriding the tactical editor. */
    private Position goalkeeperTarget(Player p) {
        return goalkeeperMovement.goalkeeperTarget(p);
    }

    public void assignTargets() {
        state.setTacticalBallPosition(state.getBall().getPosition());
        state.setLastTacticalBallStateKey(TacticsRules.ballStateKey(state.getBall().getPosition()));
        // Corner set-piece arrangement overrides normal tactical targets for
        // every player while a corner is being walked/taken.
        if (cornerArrangement.isCornerArrangementActive(state)) {
            cornerArrangement.applyCornerTargets(state);
            return;
        }
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            p.setThreatOverrideActive(false); // clear every round — re-set below if applicable
            Position desired;
            if ("GK".equals(p.getRole())) {
                // Goalkeeper movement is a dedicated reactive override (user rule).
                desired = goalkeeperTarget(p);
            } else {
                desired = applyOutfieldTargeting(p);
            }
            state.setTacticalDesiredPosition(p, desired);
            p.setTarget(desired); // Smooth movement to exact desired position
        }
    }

    /**
     * Full outfield targeting: defensive-position constraint → offside retreat →
     * threat override, and marks {@code threatOverrideActive} when the threat
     * layer actually changed the target (MovementEngine uses the flag to apply
     * the 1.6x press-speed boost). Shared by {@link #assignTargets()} and
     * {@link #refreshTargetsIfBallStateChanged()} so the flag is consistent on
     * BOTH per-tick paths (tactical-refresh runs every tick; assignTargets only
     * runs at the start of a decision cycle).
     */
    private Position applyOutfieldTargeting(Player p) {
        Position desired = state.getTacticsRules().desiredCell(
                p.getRole(), state.getBall().getPosition(), p.getTeam());
        desired = applyDefensivePositionConstraint(p, desired);
        Position retreat = applyOffsideRetreat(p, desired);
        Position beforeThreat = retreat;
        desired = applyThreatOverride(p, retreat);
        if (desired.getRow() != beforeThreat.getRow()
                || desired.getColumn() != beforeThreat.getColumn()) {
            p.setThreatOverrideActive(true);
        }
        return desired;
    }

    /**
     * Keep deep defensive players (DEF/CB/LB/RB/DM) at sane depths.
     *
     * corePrinciples §47.8: " defenders track the ball but do NOT rush
     * premasno in the opponent's half." When the ball is in our defensive half
     * the defensive line holds; full-backs may overlap only when the ball is in
     * the final third. CB/DM never cross the halfway line unless the ball is
     * already in the attacking half.
     *
     * User rule: stoppers (DCL/DCR) must stay CENTRAL and approach the ball
     * carrier when the ball is in central columns (2-5). Their anchor cells are
     * wide (col 1 and col 6), but they must shift toward the ball's column so
     * they can press the carrier. The shift is limited to 1.5 cells per tick
     * (the same as normal movement) so they don't teleport.
     *
     * REACTIVE DEFENSE (user rule): when the ball is in our own half and a
     * CARRY is in progress, every defender in the team reacts to the ball's
     * current position by shifting their column toward the ball — not toward
     * their static cell centre. The shift is capped at 0.5 cells per tick so
     * the line doesn't break in one frame. This way, an attacker dribbling
     * through the middle finds defenders actually moving to intercept instead
     * of standing at their anchor cells.
     */
    private Position applyDefensivePositionConstraint(Player p, Position desired) {
        String role = p.getRole();
        boolean home = "HOME".equals(p.getTeam());
        if (!isDefender(role)) return desired;

        double ballRow = state.getBall().getPosition().getRow();
        double ballCol = state.getBall().getPosition().getColumn();
        boolean ballInOwnHalf = home ? ballRow <= 4.0 : ballRow >= 4.0;
        double desiredRow = desired.getRow();
        double desiredCol = desired.getColumn();
        // Center backs: DEF/CB/DCL/DCR. Fullbacks: LB/RB/DL/DR. DM is its own class.
        boolean isCenterBack = role.equals("DEF") || role.equals("CB")
                || role.equals("DCL") || role.equals("DCR");
        boolean isFullback = role.equals("LB") || role.equals("RB")
                || role.equals("DL") || role.equals("DR");
        boolean isDM = role.equals("DM");
        boolean isStopper = role.equals("DCL") || role.equals("DCR");

        // --- Stopper column shift toward ball when ball is in central columns ---
        // DCL/DCR anchors are at col 1 and col 6 (wide). When the ball is in
        // central columns (2-5), stoppers must shift toward the ball's column
        // so they can press the carrier in the middle of the pitch.
        if (isStopper && ballCol >= 2.0 && ballCol <= 5.0) {
            double colShift = ballCol - desiredCol;
            desiredCol = desiredCol + colShift;
            // Keep within field bounds
            desiredCol = Math.max(1.0, Math.min(6.9, desiredCol));
        }

        // --- Fullback column shift toward ball when ball is in central columns ---
        // LB/RB (cols 1 and 6) should be able to cut inside when the ball is
        // in central columns (2-5). They shift toward the ball's column so they
        // can cover the middle of the pitch instead of staying wide.
        if (isFullback && ballCol >= 2.0 && ballCol <= 5.0) {
            double colShift = (ballCol - desiredCol) * 0.8;
            if (Math.abs(colShift) > 1.0) {
                colShift = Math.signum(colShift) * 1.0;
            }
            desiredCol = desiredCol + colShift;
            desiredCol = Math.max(1.0, Math.min(6.9, desiredCol));
        }

        // --- REACTIVE DEFENSE — own half + CARRY → shift toward ball column ---
        // When the ball is in our half and an opponent is carrying it, every
        // defender shifts their column toward the ball's current column. The
        // shift is capped at 0.5 cells per tick (less than the 1-cell speed cap)
        // so the line doesn't collapse instantly — the defenders close down
        // the carrier gradually, just like real CBs slide across to cover.
        if (ballInOwnHalf && state.hasActiveAction()
                && state.getAction().getType() == org.example.footballmanager.demo.service.model.ActionType.CARRY
                && state.getBall().getCarrier() != null
                && !state.getBall().getCarrier().getTeam().equals(p.getTeam())) {
            double colShift = (ballCol - desiredCol) * 0.5;
            if (Math.abs(colShift) > 0.5) {
                colShift = Math.signum(colShift) * 0.5;
            }
            desiredCol = desiredCol + colShift;
            desiredCol = Math.max(1.0, Math.min(6.9, desiredCol));
        }

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
        if (cornerArrangement.isCornerArrangementActive(state)) {
            cornerArrangement.applyCornerTargets(state);
            return;
        }
        for (Player p : state.getPlayers()) {
            if (p == state.getCarrier() || p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            if (p == state.getReturningPlayer() || isActiveChase(p)) continue;
            p.setThreatOverrideActive(false); // clear every tick — re-set by the threat layer if pressing
            Position desired;
            if ("GK".equals(p.getRole())) {
                // Goalkeeper movement is a dedicated reactive override (user rule).
                desired = goalkeeperTarget(p);
            } else {
                desired = applyOutfieldTargeting(p);
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
     * 1. TYPE A — ball carrier anywhere on the pitch (≤ 1.0 cell). Approach them
     *    even if they're in the attacking third — a defender who sees the
     *    carrier at 1.0 cell (~14 m) closes the gap so a duel can fire as soon
     *    as the defender gets within DRIBBLE_DUEL_RADIUS (0.15 cells ≈ 2 m).
     *    Was 0.2 cells — too tight, defenders couldn't engage a carrier who was
     *    already carrying (the carrier slipped past before the defender could
     *    close the gap).
     * 2. TYPE B — opponent isolated in our FINAL 2.5 ROWS (no teammate within
     *    0.5 cells of the attacker). Press them within 1.5 cells (spec) — close
     *    enough to contest the next pass/shot, but not so far that midfielders
     *    abandon their shape to chase a runner across the field.
     *
     * "One defender per threat" — closest eligible defender claims the threat
     * (§4 in threat_override_spec.md). Resolver prevents swarming: even when
     * 3 defenders are near, only the closest presses.
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

        // Only defenders AND midfielders contest threats — non-pressable
        // outfield players keep their tactical position (prevents 5 players
        // from swarming the threat). User rule: midfielders must also close
        // down an opponent who is isolated/alone in the danger zone.
        if (!isPressingEligible(player.getRole())) return desired;

        boolean home = "HOME".equals(player.getTeam());
        Player bestThreat = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        for (Player opponent : state.getPlayers()) {
            if (player.getTeam().equals(opponent.getTeam())) continue;
            if (opponent.isSentOff() || opponent.isInjured()) continue;

            double distance = SimUtils.distance(player.getPosition(), opponent.getPosition());

            // TYPE A: ball carrier anywhere on the pitch — press them
            // whenever they are within 1.0 cell (~14 m). The defender moves
            // toward the carrier so a DRIBBLE duel can fire as soon as the
            // gap closes below DRIBBLE_DUEL_RADIUS (0.15 cells).
            boolean typeA = state.getBall().getCarrier() == opponent
                    && distance <= 1.0;

            // TYPE B: opponent isolated in our FINAL 2.5 ROWS (the dangerous attacking
        // third closest to our goal). User rule: when an attacker has broken
        // past the midfield into our final 2.5 rows with 0.5 cells of clear
        // space around them, a defender MUST press them all the way (close
        // to duel range) so the next pass / shot can be contested. Without
        // this, lone attackers roam free behind the midfield line.
        // Distance threshold 1.5 (spec) — within defender's reach, prevents
        // midfielders from abandoning shape across the whole field.
        boolean typeB = isInFinalQuarter(opponent.getPosition().getRow(), home)
                && isIsolated(opponent, player.getTeam(), 0.5, player)
                && distance <= 1.5;

            int priority = typeA ? 1 : (typeB ? 2 : Integer.MAX_VALUE);
            if (priority == Integer.MAX_VALUE) continue;

            if (priority < bestPriority
                    || (priority == bestPriority && distance < bestDistance)) {
                bestThreat = opponent;
                bestPriority = priority;
                bestDistance = distance;
            }
        }

        if (bestThreat == null) return desired;

        // One opponent threat is handled by one presser only: the closest
        // eligible presser (defender OR midfielder) wins the assignment
        // (resolver prevents swarming).
        if (!isClosestEligiblePresser(bestThreat, player)) return desired;

        // --- THREAT OVERRIDE LOG (app log, channel THREAT) ---
        // Emitted once per assignment change so a QA trace can count how often
        // each type fires and which defender claimed which threat. Throttled by
        // the signature below (same defender + same threat + same type + same
        // gap → no repeat line).
        if (logger != null) {
            String threatType = bestPriority == 1 ? "TYPE_A" : "TYPE_B";
            String signature = threatType + ":" + bestThreat.getId() + ":"
                    + String.format(Locale.US, "%.2f", bestDistance);
            String prev = lastThreatLog.get(player.getId());
            if (!signature.equals(prev)) {
                lastThreatLog.put(player.getId(), signature);
                logger.logInfo(state, "THREAT " + threatType + ": " + player.getLabel()
                        + " presses " + bestThreat.getLabel()
                        + " | gap=" + String.format(Locale.US, "%.2f", bestDistance) + " cells"
                        + (bestPriority == 1 && state.getBall().getCarrier() != null
                            ? " (carrier " + state.getBall().getCarrier().getLabel() + ")"
                            : ""),
                        "THREAT", player);
            }
        }

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

    /**
     * Check if opponent is in the final 2.5 rows of our goal (the dangerous
     * attacking third closest to our goal mouth). For HOME defending this is
     * rows 1-2.5 (where AWAY's final attacking third lives); for AWAY defending
     * this is rows 5.5-7 (where HOME's final attacking third lives). Used by
     * the threat override TYPE B to make defenders press isolated attackers
     * who have broken into the danger zone.
     */
    private boolean isInFinalQuarter(double row, boolean homeAttacking) {
        if (homeAttacking) {
            // HOME defends rows 1-2.5 — AWAY attacker is in the final 2.5 rows
            return row <= 2.5;
        } else {
            // AWAY defends rows 5.5-7 — HOME attacker is in the final 2.5 rows
            return row >= 5.5;
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

    /** Return true only for the closest eligible presser for this threat. */
    private boolean isClosestEligiblePresser(Player threat, Player candidate) {
        double candidateDistance = SimUtils.distance(candidate.getPosition(), threat.getPosition());
        for (Player teammate : state.getPlayers()) {
            if (teammate == candidate) continue;
            if (!candidate.getTeam().equals(teammate.getTeam())) continue;
            if ("GK".equals(teammate.getRole())) continue;
            if (teammate.isSentOff() || teammate.isInjured() || teammate.isLocked()) continue;
            if (teammate == state.getCarrier()) continue;
            if (state.isActiveChaser(teammate)) continue;
            // Only OTHER press-eligible players (defenders AND midfielders)
            // contest this assignment — a defender and a midfielder both see
            // the threat, but only the closest one claims it (one presser per
            // threat, no swarming). Forwards keep their tactical position.
            if (!isPressingEligible(teammate.getRole())) continue;

            double otherDistance = SimUtils.distance(teammate.getPosition(), threat.getPosition());
            if (otherDistance + 1e-9 < candidateDistance) return false;
        }
        return true;
    }

    private boolean isDefender(String role) {
        return role.equals("DEF") || role.equals("CB")
                || role.equals("LB") || role.equals("RB") || role.equals("DM")
                || role.equals("DL") || role.equals("DCL")
                || role.equals("DCR") || role.equals("DR");
    }

    /**
     * Which roles are allowed to claim the threat-override press. Defenders
     * (DEF/CB/LB/RB/DM/DL/DCL/DCR/DR) plus the formation's midfielders
     * (ML/CML/CMR/MR and generic MID/CM/AM/WNG). User rule: midfielders must
     * also close down an opponent who is isolated/alone in the danger zone.
     */
    private boolean isPressingEligible(String role) {
        return isDefender(role)
                || role.equals("ML") || role.equals("CML")
                || role.equals("CMR") || role.equals("MR")
                || role.equals("MID") || role.equals("CM")
                || role.equals("AM") || role.equals("WNG");
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
            // HOME attacks toward row 8. Retreat = go LOWER (toward own goal at row 1).
            retreatRow = offsideLineRow - RETREAT_BUFFER;
            retreatRow = Math.max(1.0, retreatRow);
        } else {
            // AWAY attacks toward row 1. Retreat = go HIGHER (toward own goal at row 8).
            retreatRow = offsideLineRow + RETREAT_BUFFER;
            retreatRow = Math.min(7.9, retreatRow);
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
                // HOME attacks row 8 (AWAY goal). Most advanced AWAY defender = HIGHEST row.
                if (row > deepest) {
                    deepest = row;
                }
            } else {
                // AWAY attacks row 1 (HOME goal). Most advanced HOME defender = LOWEST row.
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
