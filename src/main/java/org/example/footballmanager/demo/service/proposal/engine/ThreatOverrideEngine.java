package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;
import java.util.Comparator;

/**
 * Threat override engine — modifies tactical targets when a threat is present.
 *
 * Three behaviors (user requirement 2026-09-14):
 *
 * <h3>TYPE A — Press carrier with ball</h3>
 * <p>When the ball carrier is within RANGE_A of a defender, the defender's
 * target switches from their tactical position to the carrier's position
 * (goal-side press). The defender enters a duel as soon as they are close
 * enough (DuelEngine handles the actual trigger).  This applies EVERYWHERE
 * on the pitch but is especially important in the defender's own half
 * where losing a duel means conceding a shot.</p>
 *
 * <h3>TYPE B — Press dangerous opponent without ball (anticipate)</h3>
 * <p>When an opponent WITHOUT the ball is in the defender's own defensive
 * third and no teammate is within RANGE_B of them, the defender's target
 * switches to a position that closes the opponent down — idea is to be
 * ready if the ball is passed to that opponent.</p>
 *
 * <h3>TYPE C — Offside retreat</h3>
 * <p>An attacker who has been in offside position for OFFSIDE_RETREAT_THRESHOLD
 * consecutive ticks pulls back toward their own goal until clearly onside.
 * Once onside, the counter resets and the player resumes normal tactical
 * positioning.  This simulates a smart attacker learning to time runs better.</p>
 *
 * <p><b>Critical rule:</b> overrides change the TARGET, never the speed.
 * All players are pace-capped at all times — no sprint boosts for pressing.</p>
 *
 * <p>Wired into MatchOrchestrator step 7b (after tactical targets, before
 * movement). TYPE A pressers are closest-eligible-only and pressing-eligible-role
 * only; the press park point lands inside PRESS_DRIB_DUEL_RADIUS (0.50), so a
 * press properly ends in a DRIBBLE duel. TYPE C reads consecutiveOffsideCount
 * accumulated by OffsideService.trackOffsidePositions every tick.</p>
 */
public class ThreatOverrideEngine implements EngineInterfaces.ThreatOverrideEngine {

    // --- Tunable constants ---

    /** TYPE A: max distance (cells) from carrier to activate press. */
    public static final double RANGE_A = 1.5;

    /** TYPE B: max distance (cells) from opponent to activate anticipation press. */
    public static final double RANGE_B = 1.5;

    /** TYPE C: ticks in offside before retreat begins. */
    public static final int OFFSIDE_RETREAT_THRESHOLD = 3;

    /** TYPE C: how far behind offside line to retreat (cells). */
    public static final double RETREAT_BUFFER = 2.0;

    public ThreatOverrideEngine() {}

    @Override
    public void evaluate(MatchState state) {
        // 1. Clear all threatOverrideActive flags
        for (Player p : state.getPlayers()) {
            p.setThreatOverrideActive(false);
        }

        Player carrier = state.getCarrier();

        // 2. For each non-carrier outfield player (both teams)
        for (Player player : state.getPlayers()) {
            if (player == carrier) continue;
            if (player.isSentOff() || player.isInjured() || player.isLocked()) continue;
            if ("GK".equals(player.getRole())) continue;
            // The designated restart taker keeps his walk-to-ball target — a
            // threat override here would re-route him away from the restart spot
            // and freeze the match on a dead ball (DEAD-WATCH regression).
            if (player == state.getRestartTaker()) continue;

            Position currentTarget = player.getTarget();
            if (currentTarget == null) currentTarget = player.getPosition();
            Position newTarget = currentTarget;

            // a. Try TYPE A: press carrier if within RANGE_A
            Position typeA = pressCarrier(player, state);
            Position typeB = null;
            Position typeC = null;
            if (typeA != null) {
                newTarget = typeA;
            } else {
                // b. Try TYPE B: press isolated opponent in own defensive third
                typeB = pressIsolatedOpponent(player, state);
                if (typeB != null) {
                    newTarget = typeB;
                } else {
                    // c. Try TYPE C: offside retreat if consecutiveOffsideCount >= threshold
                    typeC = offsideRetreat(player, state, currentTarget);
                    if (typeC != null) {
                        newTarget = typeC;
                    }
                }
            }

            // 3. Set threatOverrideActive = true for any player whose target was changed
            if (newTarget != currentTarget
                    && (Math.abs(newTarget.getRow() - currentTarget.getRow()) > 1e-9
                    || Math.abs(newTarget.getColumn() - currentTarget.getColumn()) > 1e-9)) {
                player.setTarget(newTarget);
                player.setThreatOverrideActive(true);

                // Shared action logger — every threat override that re-routes a
                // defender writes a trace line (which player pressed who, and why).
                // This is the press→duel path: THR should be followed by a DRIBBLE
                // duel at the press point, never by a silent stuck corridor.
                if (state.getActionLogger() != null) {
                    String which = typeA != null ? "TYPE_A" : typeB != null ? "TYPE_B" : "TYPE_C";
                    String what = state.getCarrier() != null && typeA != null
                            ? state.getCarrier().getLabel() + "(" + state.getCarrier().getRole() + ")"
                            : (typeB != null ? "isolated-opponent-in-final-quarter" : "offside-retreat");
                    state.getActionLogger().log("THR",
                            which + " " + player.getLabel() + "(" + player.getRole() + ")"
                                    + " presses " + what
                                    + " at " + state.getActionLogger().p(newTarget)
                                    + " from " + state.getActionLogger().p(currentTarget));
                }
            }
        }
    }

    // --- TYPE A: Press carrier ---

    /**
     * Check if this defender should press the ball carrier.
     * Returns the press target position if so, null otherwise.
     */
    private Position pressCarrier(Player defender, MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) return null;
        if (defender.getTeam().equals(carrier.getTeam())) return null; // don't press own carrier
        // Only defenders AND midfielders contest the carrier — a striker marking
        // the ball looks absurd and every outfield player would swarm the ball.
        if (!isPressingEligible(defender.getRole())) return null;

        double dist = SimUtils.distance(defender.getPosition(), carrier.getPosition());
        if (dist > RANGE_A) return null;

        // "One defender per threat" — only the closest eligible presser claims
        // the carrier; everyone else holds their tactical shape (no swarm).
        if (!isClosestEligiblePresser(carrier, defender, state)) return null;

        // Return the carrier's position — MovementEngine's wall collision
        // (MIN_PLAYER_DISTANCE = 0.35) parks the presser ~0.35-0.4 cells apart,
        // which is inside the presser duel radius (0.50) so the tackle fires.
        return new Position(carrier.getPosition().getRow(), carrier.getPosition().getColumn());
    }

    // --- TYPE B: Press isolated opponent in defensive third ---

    /**
     * Check if this defender should press a dangerous opponent without ball.
     * Only in own defensive third, only if no teammate is already covering.
     */
    private Position pressIsolatedOpponent(Player defender, MatchState state) {
        boolean home = "HOME".equals(defender.getTeam());

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(defender.getTeam())) continue;
            if (opponent == state.getCarrier()) continue; // carrier is TYPE A
            if (opponent.isSentOff() || opponent.isInjured()) continue;

            // Final 2.5 rows check (isInFinalQuarter): rows <= 2.5 HOME / >= 6.5 AWAY
            double opponentRow = opponent.getPosition().getRow();
            boolean inFinalQuarter = home ? opponentRow <= 2.5 : opponentRow >= 6.5;
            if (!inFinalQuarter) continue;

            // Isolated: no teammate within 0.5 cells (isIsolated)
            boolean isolated = state.getPlayers().stream()
                    .filter(p -> p != defender && p.getTeam().equals(defender.getTeam()))
                    .noneMatch(p -> SimUtils.distance(p.getPosition(), opponent.getPosition()) <= 0.5);
            if (!isolated) continue;

            double distToOpponent = SimUtils.distance(defender.getPosition(), opponent.getPosition());
            if (distToOpponent > RANGE_B) continue;

            // "One defender per threat" — closest eligible presser claims the threat
            if (!isClosestEligiblePresser(opponent, defender, state)) continue;

            return new Position(opponent.getPosition().getRow(), opponent.getPosition().getColumn());
        }
        return null;
    }

    // --- TYPE C: Offside retreat ---

    /**
     * If attacker has been offside for OFFSIDE_RETREAT_THRESHOLD consecutive
     * ticks, pull them back toward their own goal until clearly onside.
     * Once onside: reset counter, resume normal tactical targets.
     */
    private Position offsideRetreat(Player attacker, MatchState state, Position tacticalTarget) {
        if (attacker.getConsecutiveOffsideCount() < OFFSIDE_RETREAT_THRESHOLD) return null;

        boolean home = "HOME".equals(attacker.getTeam());

        // Compute offside line (second-to-last defender row)
        String defendingTeam = home ? "AWAY" : "HOME";
        double offsideLineRow = findSecondLastDefenderRow(state, defendingTeam, home);

        // Retreat target: RETREAT_BUFFER cells behind the offside line
        double retreatRow;
        if (home) {
            retreatRow = Math.max(1.0, offsideLineRow - RETREAT_BUFFER);
        } else {
            retreatRow = Math.min(7.9, offsideLineRow + RETREAT_BUFFER);
        }

        // Check if attacker is already clearly onside
        if (isClearlyOnside(attacker, state, defendingTeam, home)) {
            attacker.resetConsecutiveOffside();
            return null; // back to normal tactical target
        }

        // Still offside — return retreat position
        return new Position(retreatRow, tacticalTarget.getColumn());
    }

    // --- Helpers ---

    private double findSecondLastDefenderRow(MatchState state, String defendingTeam, boolean home) {
        var rows = state.getPlayers().stream()
                .filter(p -> defendingTeam.equals(p.getTeam()))
                .filter(p -> !p.isSentOff() && !p.isInjured() && !"GK".equals(p.getRole()))
                .map(p -> p.getPosition().getRow())
                .sorted(home ? Comparator.<Double>reverseOrder()
                             : Comparator.naturalOrder())
                .toList();
        return rows.size() >= 2 ? rows.get(1) : (home ? 1.0 : 8.0);
    }

    private boolean isClearlyOnside(Player attacker, MatchState state,
                                          String defendingTeam, boolean home) {
        // FIFA offside line: the attacker is onside when at least TWO opponents
        // (including the goalkeeper) are level with or closer to the goal line
        // he is attacking. The retreat ends only then (mirrors the demo/service
        // user rule), then the counter resets and normal tactics resume.
        double playerRow = attacker.getPosition().getRow();
        int opponentsGoalSide = 0;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            double defRow = p.getPosition().getRow();
            if (home ? defRow > playerRow : defRow < playerRow) {
                opponentsGoalSide++;
            }
        }
        return opponentsGoalSide >= 2;
    }

    /** Return true only for the closest eligible presser for this threat. */
    private boolean isClosestEligiblePresser(Player threat, Player candidate, MatchState state) {
        double candidateDistance = SimUtils.distance(candidate.getPosition(), threat.getPosition());
        for (Player teammate : state.getPlayers()) {
            if (teammate == candidate) continue;
            if (!candidate.getTeam().equals(teammate.getTeam())) continue;
            if ("GK".equals(teammate.getRole())) continue;
            if (teammate.isSentOff() || teammate.isInjured() || teammate.isLocked()) continue;
            if (teammate == state.getCarrier()) continue;
            if (!isPressingEligible(teammate.getRole())) continue;

            double otherDistance = SimUtils.distance(teammate.getPosition(), threat.getPosition());
            if (otherDistance + 1e-9 < candidateDistance) return false;
        }
        return true;
    }

    private boolean isPressingEligible(String role) {
        return isDefender(role)
                || role.equals("ML") || role.equals("CML")
                || role.equals("CMR") || role.equals("MR")
                || role.equals("MID") || role.equals("CM")
                || role.equals("AM") || role.equals("WNG");
    }

    private boolean isDefender(String role) {
        return role.equals("DEF") || role.equals("CB")
                || role.equals("LB") || role.equals("RB") || role.equals("DM")
                || role.equals("DL") || role.equals("DCL")
                || role.equals("DCR") || role.equals("DR");
    }
}
