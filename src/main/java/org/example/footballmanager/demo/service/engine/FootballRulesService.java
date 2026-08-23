package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.Position;

/**
 * Football Rules Service — corePrinciples Section 15-16.
 *
 * "Football rules are authoritative over player intentions."
 * "Rules should not be embedded inside tactical decision logic."
 *
 * Evaluates legality of actions: offside, fouls, throw-ins, corners, goal kicks.
 */
public class FootballRulesService {

    private final MatchState state;

    public FootballRulesService(MatchState state) {
        this.state = state;
    }

    /**
     * Check offside for a forward pass.
     * corePrinciples Section 16: offside from second-to-last defender.
     *
     * @param receiver the player receiving the pass
     * @param passOrigin position from where the pass was played
     * @param ballPosition current ball position (at moment of pass)
     * @return true if receiver is offside
     */
    public boolean isOffside(Player receiver, Position passOrigin, Position ballPosition) {
        boolean home = "HOME".equals(receiver.getTeam());

        boolean forwardPass = home
                ? receiver.getPosition().getRow() > passOrigin.getRow()
                : receiver.getPosition().getRow() < passOrigin.getRow();
        if (!forwardPass) return false;

        boolean inOpponentHalf = home
                ? receiver.getPosition().getRow() >= 4
                : receiver.getPosition().getRow() <= 4;
        if (!inOpponentHalf) return false;

        String defendingTeam = home ? "AWAY" : "HOME";
        // Precision: tolerance of 0.15 cells ≈ 2.1m — tight offside line.
        // Defender within this range of the receiver counts as onside.
        double tolerance = 0.15;
        int defendersInFront = 0;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            // Exact decimal check: is this defender between the receiver and the goal?
            boolean defenderIsGoalSide = home
                    ? p.getPosition().getRow() >= receiver.getPosition().getRow() - tolerance
                    : p.getPosition().getRow() <= receiver.getPosition().getRow() + tolerance;
            if (defenderIsGoalSide) defendersInFront++;
        }

        return defendersInFront < 1;
    }

    /**
     * Determine ball out-of-bounds result.
     * Returns the type of restart: CORNER, GOAL_KICK, THROW_IN.
     */
    public RestartType determineRestart(Position ballPos, String lastTouchTeam) {
        // Ball must be clearly past the pitch boundaries (not just near the edge).
        // Pitch is rows 1-7, cols 1-6 (playing area). End lines at rows 0/8, sidelines at cols 0/8.
        // Thresholds 0.5 / 7.5 ensure balls at row 0.92 or 7.08 (from ExecutionQuality clamp)
        // do NOT trigger false restarts.
        boolean outLeft = ballPos.getColumn() < 0.5;
        boolean outRight = ballPos.getColumn() > 7.5;
        boolean outTop = ballPos.getRow() > 7.5;
        boolean outBottom = ballPos.getRow() < 0.5;

        // Side out (left/right) → always throw-in (corners only from end-line)
        if (outLeft || outRight) {
            return RestartType.THROW_IN;
        }

        // End line (top/bottom) — corner or goal kick
        // HOME attacks toward row 7 (up/outTop), AWAY attacks toward row 1 (down/outBottom)
        if (outTop || outBottom) {
            String attackingTeam = lastTouchTeam;
            boolean wasAttacking = ("HOME".equals(attackingTeam) && outTop)
                    || ("AWAY".equals(attackingTeam) && outBottom);
            if (wasAttacking) {
                // Attacker played ball over end line — goal kick for defenders
                return RestartType.GOAL_KICK;
            } else {
                // Defender played ball over end line — corner for attackers
                // In real football, ~25% of defender clearances over end line yield corners
                // (some are goal kicks if clearance goes directly behind the goal)
                double cornerChance = 0.25;
                return state.getRandom().nextDouble() < cornerChance
                        ? RestartType.CORNER : RestartType.GOAL_KICK;
            }
        }

        return RestartType.NONE;
    }

    /**
     * Evaluate whether a tackle results in a foul.
     * @return foul probability based on defender skill and pressure
     */
    public boolean isFoul(Player defender, Player attacker) {
        // Base foul chance: ~6% per contested tackle (real football: ~5-8%)
        double foulChance = 0.06;
        // Lower-skill defenders foul more
        foulChance += (1.0 - defender.getSkills().defender() / 20.0) * 0.05;
        // Higher-skill attackers draw more fouls
        foulChance += (attacker.getSkills().technique() / 20.0) * 0.03;
        // Penalty box: ~1 row deep (row 7 for HOME, row 1 for AWAY), columns 2-5
        Position pos = defender.getPosition();
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        boolean inPenaltyBox = homeAttacking
                ? (pos.getRow() >= 7 && pos.getColumn() >= 2 && pos.getColumn() <= 5)
                : (pos.getRow() <= 1 && pos.getColumn() >= 2 && pos.getColumn() <= 5);
        // In the penalty box, very slightly more fouls (last-ditch tackles) but NOT double the chance
        if (inPenaltyBox) foulChance += 0.005;
        return state.getRandom().nextDouble() < foulChance;
    }

    /**
     * Determine card for a foul.
     */
    public CardType determineCard(Player defender, boolean isSecondYellow) {
        Position defenderPos = defender.getPosition();
        // Penalty box: row 7 for HOME attacking, row 1 for AWAY attacking, columns 2-5
        boolean inPenaltyBox = ((defenderPos.getRow() >= 7 && defenderPos.getColumn() >= 2 && defenderPos.getColumn() <= 5)
                || (defenderPos.getRow() <= 1 && defenderPos.getColumn() >= 2 && defenderPos.getColumn() <= 5));

        // Straight red: very rare (0.2% base, 0.5% in penalty box)
        double redChance = inPenaltyBox ? 0.005 : 0.002;
        if (state.getRandom().nextDouble() < redChance) return CardType.RED;

        // Yellow card: ~10% of fouls (real football: ~10-15%)
        double yellowChance = 0.10;
        if (inPenaltyBox) yellowChance += 0.02;

        if (state.getRandom().nextDouble() < yellowChance) {
            return isSecondYellow ? CardType.RED : CardType.YELLOW;
        }
        return CardType.NONE;
    }

    public enum RestartType {
        NONE, CORNER, GOAL_KICK, THROW_IN, FREE_KICK, PENALTY, KICK_OFF
    }

    public enum CardType {
        NONE, YELLOW, RED
    }
}
