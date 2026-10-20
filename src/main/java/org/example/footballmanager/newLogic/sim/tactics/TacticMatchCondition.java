package org.example.footballmanager.newLogic.sim.tactics;

/**
 * When a manager wants a tactic to be in force (owner, 2026-10-10).
 *
 * <p>The owner's six: always · leading by 1 · leading by 3+ · drawing · trailing by 1 · trailing by 3+.
 * There is deliberately no "leading by two" — a manager who wants that has two rules that cover it, and
 * a rule that fires on a one-goal margin and a rule that fires on a three-goal margin leave the gap where
 * it belongs: under the default.
 *
 * <p><b>Every condition is read from one number.</b> The score difference from the side's own point of
 * view — positive when that side is ahead — so a condition means the same thing for both clubs without
 * either of them being special. "Leading by one" is the home side's lead and the away side's deficit
 * depending on who is reading, and neither needs a second set of names.
 */
public enum TacticMatchCondition {

    /** In force whatever the score. The instruction "always play this way". */
    ALWAYS,

    /** Ahead by exactly one. */
    LEADING_BY_ONE,

    /** Ahead by three or more. */
    LEADING_BY_THREE,

    /** Level. */
    DRAWING,

    /** Behind by exactly one. */
    TRAILING_BY_ONE,

    /** Behind by three or more. */
    TRAILING_BY_THREE;

    /**
     * Whether this condition is true right now.
     *
     * @param scoreDifference goals scored by this side minus goals scored by the other, so positive means
     *                        this side is ahead
     */
    public boolean holds(int scoreDifference) {
        return switch (this) {
            case ALWAYS -> true;
            case LEADING_BY_ONE -> scoreDifference == 1;
            case LEADING_BY_THREE -> scoreDifference >= 3;
            case DRAWING -> scoreDifference == 0;
            case TRAILING_BY_ONE -> scoreDifference == -1;
            case TRAILING_BY_THREE -> scoreDifference <= -3;
        };
    }

    /** The label the manager picks from, and the wording the refusal message uses. */
    public String label() {
        return switch (this) {
            case ALWAYS -> "Always";
            case LEADING_BY_ONE -> "If leading by 1";
            case LEADING_BY_THREE -> "If leading by 3 or more";
            case DRAWING -> "If drawing";
            case TRAILING_BY_ONE -> "If trailing by 1";
            case TRAILING_BY_THREE -> "If trailing by 3 or more";
        };
    }
}