package org.example.footballmanager.demo.service.proposal.rules;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Football rules engine - ONLY checks football rules (offside, etc.).
 * Does NOT make decisions. Does NOT move players.
 *
 * Offside geometry (FIFA Law 11): a player is in an offside POSITION when,
 * AT THE MOMENT THE BALL IS PLAYED, he is in the opponent's half AND nearer
 * to the opponent's goal line than both the ball AND the second-to-last
 * opponent (incl. the goalkeeper).
 */
public class FootballRules {

    /** Offside position = strictly beyond the second-to-last opponent. */
    public static final double DEEP_OFFSIDE_FILTER_CELLS = 0.7;

    /**
     * Offside margin of a receiver relative to the second-to-last opponent
     * (incl. GK), measured at the moment of the pass. Positive = offside,
     * negative = onside.
     *
     * Returns {@code -Double.MAX_VALUE} when the receiver does NOT qualify
     * for an offside check at all (not forward of the ball, not in the
     * opponent's half, or fewer than 2 opponents available) — callers treat
     * that as "clearly onside".
     */
    public double offsideDepth(Player passer, Player receiver, MatchState state) {
        if (passer == null || receiver == null) return -Double.MAX_VALUE;
        boolean home = "HOME".equals(passer.getTeam());
        if (receiver.getTeam().equals(passer.getTeam()) == false) return -Double.MAX_VALUE;

        double passerRow = passer.getPosition().getRow();
        double receiverRow = receiver.getPosition().getRow();

        // Must be forward of the ball (the passer is on the ball at pass time).
        boolean forward = home ? receiverRow > passerRow : receiverRow < passerRow;
        if (!forward) return -Double.MAX_VALUE;

        // Must be in the opponent's half.
        boolean opponentHalf = home ? receiverRow >= 4.5 : receiverRow <= 4.5;
        if (!opponentHalf) return -Double.MAX_VALUE;

        // Second-to-last opponent (incl. GK), sorted toward the goal line.
        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> opponentRows = new ArrayList<>();
        for (Player opponent : state.getPlayers()) {
            if (defendingTeam.equals(opponent.getTeam())
                    && !opponent.isSentOff() && !opponent.isInjured()) {
                opponentRows.add(opponent.getPosition().getRow());
            }
        }
        if (opponentRows.size() < 2) return -Double.MAX_VALUE;

        opponentRows.sort(home ? java.util.Comparator.reverseOrder()
                : java.util.Comparator.naturalOrder());
        double secondLast = opponentRows.get(1);

        return home ? receiverRow - secondLast : secondLast - receiverRow;
    }

    /** True when the receiver is in an offside position at the moment of the pass. */
    public boolean isInOffsidePosition(Player passer, Player receiver, MatchState state) {
        return offsideDepth(passer, receiver, state) > 0;
    }

    /**
     * Offside call threshold (the referee's clear-call band): beyond 0.5 cells
     * (~7 m) past the second-to-last opponent the offside is obvious and is
     * whistled immediately. Trailing that the flag is held for VAR.
     */
    public boolean isOffside(Player passer, Player receiver, MatchState state) {
        double depth = offsideDepth(passer, receiver, state);
        return depth > 0.5;
    }

    /**
     * Check if a shot was on target (for goal / save / miss).
     */
    public boolean isShotOnTarget(Player shooter, Position shotTarget, Position goal) {
        return SimUtils.distance(shotTarget, goal) < 1.0;
    }
}