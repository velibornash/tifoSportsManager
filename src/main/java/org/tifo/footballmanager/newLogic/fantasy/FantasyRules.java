package org.tifo.footballmanager.newLogic.fantasy;

import org.tifo.footballmanager.newLogic.model.Position;

/**
 * The fantasy rules, as constants (owner, 2026-10-17).
 *
 * <p>Every number here was decided by the owner and is quoted in `kanban.md`. They live in one class so
 * that "3 players from each tier" and "2/5/5/3" are the same statement wherever they are read, rather
 * than a shape restated in a picker, a validator and a scoring pass that can drift apart.
 *
 * <p><b>Wingers are midfielders.</b> The game has five positions ({@code GK, DEF, MID, ATT, WNG}) and
 * fantasy has four. {@link #fantasyPositionOf} folds {@code WNG} into {@code MID}: a winger is a
 * midfielder, and it keeps the 2/5/5/3 shape every fantasy player already knows instead of inventing a
 * five-position formation nobody can hold in their head.
 */
public final class FantasyRules {

    private FantasyRules() {
    }

    /** Fifteen: eleven starters, one reserve goalkeeper and three substitutes. */
    public static final int SQUAD_SIZE = 15;

    /** Eleven of the fifteen are named to start. */
    public static final int STARTERS = 11;

    /** Three outfield substitutes. The fifteenth man is the reserve goalkeeper. */
    public static final int BENCH = 3;

    /**
     * Three players from each tier of the country's pyramid.
     *
     * <p>This is the rule that makes the game what it is: three of the fifteen must come from the
     * <b>fifth</b> division, so every manager has to scout 160 amateur clubs. It is also why a country is
     * only fantasy-eligible when its pyramid has exactly {@link #TIERS_REQUIRED} tiers — three times five
     * is fifteen, and three times four is twelve.
     */
    public static final int PER_TIER = 3;

    /** A pyramid needs this many tiers for the per-tier rule to add up to a squad. */
    public static final int TIERS_REQUIRED = SQUAD_SIZE / PER_TIER;

    /** At most two players from any one club. */
    public static final int MAX_PER_CLUB = 2;

    /**
     * The squad, fixed: two goalkeepers, five defenders, five midfielders, three attackers.
     *
     * <p>Fixed rather than free on purpose. With a free shape the legal range of a squad is 28% wide and
     * the budget barely constrains anything; with 2/5/5/3 the composition is a constant and the budget is
     * the only thing left to trade with.
     */
    public static int squadSlots(Position position) {
        return switch (fantasyPositionOf(position)) {
            case GK -> 2;
            case DEF -> 5;
            case MID -> 5;
            case ATT -> 3;
            default -> throw new IllegalArgumentException("No fantasy squad slot for " + position);
        };
    }

    /**
     * What the eleven must contain: one goalkeeper, three defenders, two midfielders, one attacker.
     *
     * <p>Minimums, not a fixed shape, so every legal formation is reachable — 4-4-2, 3-5-2, 4-3-3 and the
     * rest — while a manager cannot field a team with no goalkeeper or nobody up front.
     */
    public static int startingMinimum(Position position) {
        return switch (fantasyPositionOf(position)) {
            case GK -> 1;
            case DEF -> 3;
            case MID -> 2;
            case ATT -> 1;
            default -> throw new IllegalArgumentException("No starting minimum for " + position);
        };
    }

    /** The captain scores double. */
    public static final int CAPTAIN_MULTIPLIER = 2;

    /** One free transfer a week; every one beyond that costs {@link #EXTRA_TRANSFER_COST} points. */
    public static final int FREE_TRANSFERS_PER_WEEK = 1;

    public static final int EXTRA_TRANSFER_COST = 4;

    /**
     * The four positions fantasy knows, with {@code WNG} folded into {@code MID}.
     *
     * <p>Returns the position unchanged when it is already one of the four, so callers can hand it
     * anything and get a legal answer back.
     */
    public static Position fantasyPositionOf(Position position) {
        return position == Position.WNG ? Position.MID : position;
    }
}
