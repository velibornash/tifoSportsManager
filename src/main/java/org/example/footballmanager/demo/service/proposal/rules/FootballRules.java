package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Football rules engine - ONLY checks football rules (offside, etc.).
 * Does NOT make decisions. Does NOT move players.
 * 
 * Core principle: Rules are checked AFTER execution, not before.
 * A player may ATTEMPT an illegal action - rules are checked after.
 */
public class FootballRules {

    /**
     * Check if a receiver is in offside position.
     * Offside = in opponent's half AND closer to goal line than
     * second-last opponent AND forward of ball.
     */
    public boolean isOffside(Player passer, Player receiver, MatchState state) {
        boolean home = "HOME".equals(passer.getTeam());
        double passerRow = passer.getPosition().getRow();
        double receiverRow = receiver.getPosition().getRow();

        // Must be forward of ball
        boolean forward = home ? receiverRow > passerRow : receiverRow < passerRow;
        if (!forward) return false;

        // Must be in opponent's half
        boolean opponentHalf = home ? receiverRow >= 4.5 : receiverRow <= 4.5;
        if (!opponentHalf) return false;

        // Find second-last opponent
        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> opponentRows = new ArrayList<>();
        for (Player opponent : state.getPlayers()) {
            if (defendingTeam.equals(opponent.getTeam())
                    && !opponent.isSentOff() && !opponent.isInjured()) {
                opponentRows.add(opponent.getPosition().getRow());
            }
        }
        if (opponentRows.size() < 2) return false;

        opponentRows.sort(home ? java.util.Comparator.reverseOrder()
                                : java.util.Comparator.naturalOrder());
        double secondLast = opponentRows.get(1);

        // Check margin (0.5 cells = ~7m)
        double margin = home ? receiverRow - secondLast
                                : secondLast - receiverRow;

        return margin > 0.5;
    }

    /**
     * Check offside after a pass/shot/cross.
     * Called after ball arrives, not before.
     */
    public void checkOffsideAfterAction(MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;

        Action action = state.getCurrentAction();
        if (action == null) return;

        Player receiver = action.getTarget();
        if (receiver == null) return;

        if (isOffside(carrier, receiver, state)) {
            receiver.setOffside(true);
            receiver.incrementConsecutiveOffside();
            // Handle restart (offside = indirect FK)
            handleOffsideRestart(state, receiver);
        }
    }

    /**
     * Check if a shot was on target (for goal / save / miss).
     */
    public boolean isShotOnTarget(Player shooter, Position shotTarget, Position goal) {
        return SimUtils.distance(shotTarget, goal) < 1.0;
    }

    private void handleOffsideRestart(MatchState state, Player offender) {
        // Offside = indirect free kick for defending team
        state.setPhase(MatchPhase.SET_PIECE);
        state.setSetPieceType("OFFSID_FREE_KICK");
    }
}