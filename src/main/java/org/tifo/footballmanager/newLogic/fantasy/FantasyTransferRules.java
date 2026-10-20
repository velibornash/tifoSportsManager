package org.tifo.footballmanager.newLogic.fantasy;

/**
 * What changing a squad costs (owner, 2026-10-17).
 *
 * <p>FPL's rule, which the owner asked for by name: <b>one change a gameweek is free, and every one beyond
 * it costs four points</b>, taken off that gameweek's total.
 *
 * <p><b>A change is counted against the squad you started the gameweek with, not against the last save.</b>
 * That distinction is the whole rule. Counting against the last save makes an undo cost twice — bring a
 * player back and he is "in" again — and a manager who tries something and changes his mind would be
 * punished for it. Counting against the gameweek's baseline means an undo is free, which is what every
 * fantasy player expects.
 *
 * <p>A substitution is not a change. Swapping a starter with one of your own substitutes moves nobody in or
 * out of the squad, so it costs nothing and is unlimited.
 */
public final class FantasyTransferRules {

    private FantasyTransferRules() {
    }

    /** One change a gameweek costs nothing. */
    public static final int FREE_PER_WEEK = 1;

    /** Every change beyond the free one costs this many points. */
    public static final int COST_PER_EXTRA = 4;

    /**
     * What a gameweek's changes cost, in points.
     *
     * @param changes how many players are in the squad who were not in it when the gameweek opened. Never
     *        negative; a manager cannot be given points for standing still.
     */
    public static int cost(int changes) {
        if (changes <= FREE_PER_WEEK) {
            return 0;
        }
        return (changes - FREE_PER_WEEK) * COST_PER_EXTRA;
    }

    /** How many of the changes are free this week. */
    public static int freeRemaining(int changes) {
        return Math.max(0, FREE_PER_WEEK - Math.max(0, changes));
    }
}
