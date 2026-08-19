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
        // Only check for forward passes
        boolean home = "HOME".equals(receiver.getTeam());
        boolean forwardPass = home
                ? receiver.getPosition().getRow() > passOrigin.getRow()
                : receiver.getPosition().getRow() < passOrigin.getRow();
        if (!forwardPass) return false;

        // Receiver must be in opponent's half
        boolean inOpponentHalf = home
                ? receiver.getPosition().getRow() >= 4
                : receiver.getPosition().getRow() <= 4;
        if (!inOpponentHalf) return false;

        // Find second-to-last defender (excluding goalkeeper)
        String defendingTeam = home ? "AWAY" : "HOME";
        int defendersInFront = 0;
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            boolean defenderCloserToGoal = home
                    ? p.getPosition().getRow() <= receiver.getPosition().getRow()
                    : p.getPosition().getRow() >= receiver.getPosition().getRow();
            if (defenderCloserToGoal) defendersInFront++;
        }

        // If receiver is beyond second-to-last defender = offside
        return defendersInFront >= 2;
    }

    /**
     * Determine ball out-of-bounds result.
     * Returns the type of restart: CORNER, GOAL_KICK, THROW_IN.
     */
    public RestartType determineRestart(Position ballPos, String lastTouchTeam) {
        boolean outLeft = ballPos.getColumn() < 1;
        boolean outRight = ballPos.getColumn() > 6;
        boolean outTop = ballPos.getRow() > 7;
        boolean outBottom = ballPos.getRow() < 1;

        if (outLeft || outRight) {
            // Side of goal? Determine by row
            boolean nearGoalLine = ballPos.getRow() >= 6 || ballPos.getRow() <= 2;
            if (nearGoalLine) {
                return RestartType.CORNER;
            }
            return RestartType.THROW_IN;
        }

        if (outTop || outBottom) {
            // End line — goal kick or corner
            String attackingTeam = lastTouchTeam;
            boolean wasAttacking = ("HOME".equals(attackingTeam) && outTop)
                    || ("AWAY".equals(attackingTeam) && outBottom);
            return wasAttacking ? RestartType.GOAL_KICK : RestartType.CORNER;
        }

        return RestartType.NONE;
    }

    /**
     * Evaluate whether a tackle results in a foul.
     * @return foul probability based on defender skill and pressure
     */
    public boolean isFoul(Player defender, Player attacker) {
        double foulChance = 0.15; // base foul probability
        foulChance += (1.0 - defender.getSkills().defender() / 20.0) * 0.15;
        foulChance += (attacker.getSkills().technique() / 20.0) * 0.1;
        return state.getRandom().nextDouble() < foulChance;
    }

    /**
     * Determine card for a foul.
     */
    public CardType determineCard(Player defender, boolean isSecondYellow) {
        if (isSecondYellow) return CardType.RED;

        double redChance = 0.05; // base straight red
        double yellowChance = 0.35;

        // Dangerous position = higher chance
        Position defenderPos = defender.getPosition();
        boolean inPenaltyBox = defenderPos.getRow() >= 6 && defenderPos.getColumn() >= 2 && defenderPos.getColumn() <= 5;
        if (inPenaltyBox) {
            redChance += 0.1;
            yellowChance += 0.2;
        }

        if (state.getRandom().nextDouble() < redChance) return CardType.RED;
        if (state.getRandom().nextDouble() < yellowChance) return CardType.YELLOW;
        return CardType.NONE;
    }

    public enum RestartType {
        NONE, CORNER, GOAL_KICK, THROW_IN, FREE_KICK, PENALTY, KICK_OFF
    }

    public enum CardType {
        NONE, YELLOW, RED
    }
}
