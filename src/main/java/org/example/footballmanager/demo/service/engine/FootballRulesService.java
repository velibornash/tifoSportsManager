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
        // FIFA Rule 11: offside if fewer than 2 opponents (including GK) between
        // receiver and goal line at the moment the ball is played.
        // "Second-last opponent" defines the offside line — GK is usually the last.
        int opponentsGoalSide = 0;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if (p.isLocked() || p.isSentOff() || p.isInjured()) continue;
            boolean goalSide = home
                    ? p.getPosition().getRow() > receiver.getPosition().getRow()
                    : p.getPosition().getRow() < receiver.getPosition().getRow();
            if (goalSide) opponentsGoalSide++;
        }

        return opponentsGoalSide < 2;
    }

    /**
     * Determine ball out-of-bounds result.
     * Returns the type of restart: CORNER, GOAL_KICK, THROW_IN.
     */
    public RestartType determineRestart(Position ballPos, String lastTouchTeam, boolean wasInsideField) {
        // Ball is OOB if it crosses any field boundary.
        // Playing area: rows 1-7, cols 1-6. Lines are AT the boundary.
        // Any ball with row < 1 or row > 7 or col < 1 or col > 6 is OOB.
        boolean outLeft = ballPos.getColumn() < 1.0;
        boolean outRight = ballPos.getColumn() > 6.0;
        boolean outTop = ballPos.getRow() > 7.0;
        boolean outBottom = ballPos.getRow() < 1.0;

        // Side out (left/right) → always throw-in (corners only from end-line)
        if (outLeft || outRight) {
            return RestartType.THROW_IN;
        }

        // End line (top/bottom) — goal, goal kick, or corner
        // HOME attacks toward row 7 (up/outTop), AWAY attacks toward row 1 (down/outBottom)
        if (outTop || outBottom) {
            if (wasInsideField) {
                // Ball was inside the field before the shot, then went over the goal line → GOAL
                return RestartType.GOAL;
            }
            // Ball was already over the goal line → GOAL_KICK or CORNER
            String attackingTeam = lastTouchTeam;
            boolean wasAttacking = ("HOME".equals(attackingTeam) && outTop)
                    || ("AWAY".equals(attackingTeam) && outBottom);
            if (wasAttacking) {
                // Attacker played ball over end line — goal kick for defenders
                return RestartType.GOAL_KICK;
            } else {
                // Defender played ball over end line — ALWAYS corner for attacking team
                // (real football: defensive clearance over end line = corner)
                return RestartType.CORNER;
            }
        }

        return RestartType.NONE;
    }

    /**
     * Evaluate whether a tackle results in a foul.
     * @return foul probability based on defender skill and pressure
     */
    public boolean isFoul(Player defender, Player attacker) {
        // Base foul chance: ~20% per contested tackle (real football: ~15-20%)
        double foulChance = 0.20;
        // Lower-skill defenders foul more
        foulChance += (1.0 - defender.getSkills().defender() / 20.0) * 0.08;
        // Higher-skill attackers draw more fouls
        foulChance += (attacker.getSkills().technique() / 20.0) * 0.05;
        // Penalty box: ~1 row deep (row 7 for HOME, row 1 for AWAY), columns 2-5
        Position pos = defender.getPosition();
        boolean homeAttacking = "HOME".equals(attacker.getTeam());
        boolean inPenaltyBox = homeAttacking
                ? (pos.getRow() >= 7 && pos.getColumn() >= 2 && pos.getColumn() <= 5)
                : (pos.getRow() <= 1 && pos.getColumn() >= 2 && pos.getColumn() <= 5);
        // In the penalty box, slightly more fouls
        if (inPenaltyBox) foulChance += 0.02;
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

        // Straight red: rare (1% base, 2% in penalty box)
        double redChance = inPenaltyBox ? 0.02 : 0.01;
        if (state.getRandom().nextDouble() < redChance) return CardType.RED;

        // Yellow card: ~25% of fouls (real football: ~10-15%)
        double yellowChance = 0.25;
        if (inPenaltyBox) yellowChance += 0.05;

        if (state.getRandom().nextDouble() < yellowChance) {
            return isSecondYellow ? CardType.RED : CardType.YELLOW;
        }
        return CardType.NONE;
    }

    public enum RestartType {
        NONE, CORNER, GOAL_KICK, THROW_IN, FREE_KICK, PENALTY, KICK_OFF, GOAL
    }

    public enum CardType {
        NONE, YELLOW, RED
    }
}
