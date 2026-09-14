package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

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
 * <p>Placeholder — logic to be implemented per backlog (§8.5).</p>
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
        // TODO per backlog (§8.5):
        // 1. Clear all threatOverrideActive flags
        // 2. For each non-carrier outfield player (both teams):
        //    a. Try TYPE A: press carrier if within RANGE_A
        //    b. Try TYPE B: press isolated opponent in own defensive third
        //    c. Try TYPE C: offside retreat if consecutiveOffsideCount >= threshold
        // 3. Set threatOverrideActive = true for any player whose target was changed
        // 4. Log THREAT COVER / OFFSIDE RETREAT / THREAT PRESS events
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

        double dist = SimUtils.distance(defender.getPosition(), carrier.getPosition());
        if (dist > RANGE_A) return null;

        // TODO: return goal-side position toward carrier (not ON carrier — that
        // would cause wall collision).  The defender closes to ~0.35 cells and
        // then DuelEngine handles the tackle/dribble duel.
        return null;
    }

    // --- TYPE B: Press isolated opponent in defensive third ---

    /**
     * Check if this defender should press a dangerous opponent without ball.
     * Only in own defensive third, only if no teammate is already covering.
     */
    private Position pressIsolatedOpponent(Player defender, MatchState state) {
        boolean home = "HOME".equals(defender.getTeam());

        // Find opponents without ball in our defensive third
        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(defender.getTeam())) continue;
            if (opponent == state.getCarrier()) continue; // carrier is TYPE A
            if (opponent.isSentOff() || opponent.isInjured()) continue;

            double opponentRow = opponent.getPosition().getRow();
            boolean inOurDefensiveThird = home
                    ? opponentRow <= 3.0   // HOME defending rows 1-3
                    : opponentRow >= 6.0;  // AWAY defending rows 6-8
            if (!inOurDefensiveThird) continue;

            double distToOpponent = SimUtils.distance(defender.getPosition(), opponent.getPosition());
            if (distToOpponent > RANGE_B) continue;

            // Check if any teammate is already within RANGE_B of this opponent
            boolean alreadyCovered = state.getPlayers().stream()
                    .filter(p -> p != defender && p.getTeam().equals(defender.getTeam()))
                    .filter(p -> !p.isSentOff() && !p.isInjured() && !"GK".equals(p.getRole()))
                    .anyMatch(p -> SimUtils.distance(p.getPosition(), opponent.getPosition()) <= RANGE_B);
            if (alreadyCovered) continue;

            // TODO: return position that closes the opponent down (intercept
            // passing lane + be ready for duel if ball arrives)
            return null;
        }
        return null;
    }

    // --- TYPE C: Offside retreat ---

    /**
     * If attacker has been offside for OFFSIDE_RETREAT_THRESHOLD consecutive
     * ticks, pull them back toward their own goal until onside.
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
                .sorted(home ? java.util.Comparator.<Double>reverseOrder()
                             : java.util.Comparator.naturalOrder())
                .toList();
        return rows.size() >= 2 ? rows.get(1) : (home ? 1.0 : 8.0);
    }

    private boolean isClearlyOnside(Player attacker, MatchState state,
                                     String defendingTeam, boolean home) {
        // Attacker is onside if at least one outfield defender is goal-side
        return state.getPlayers().stream()
                .filter(p -> defendingTeam.equals(p.getTeam()))
                .filter(p -> !p.isSentOff() && !p.isInjured() && !"GK".equals(p.getRole()))
                .anyMatch(p -> home
                        ? p.getPosition().getRow() <= attacker.getPosition().getRow()
                        : p.getPosition().getRow() >= attacker.getPosition().getRow());
    }
}
