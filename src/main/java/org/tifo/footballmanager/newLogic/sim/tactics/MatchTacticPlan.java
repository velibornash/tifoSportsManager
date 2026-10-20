package org.tifo.footballmanager.newLogic.sim.tactics;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which of a club's assignments is in force at this moment in the match.
 *
 * <p>Pure logic: score difference in, one assignment out. No clock, no database, no state. That is what
 * makes it testable against every combination of the six conditions at once, which is the part a manager
 * will get wrong and the part the engine must get right.
 *
 * <p><b>Priority breaks ties, and it does so before anything else.</b> A manager who sets "always play
 * 4-4-2" at priority 1 and "if leading by two, play 4-3-3" at priority 2 means the second only in the
 * gap the first does not cover. Taking the first <em>matching</em> assignment instead would mean the
 * priority number only mattered when the manager happened to list them in order, and the number would be
 * decoration.
 *
 * <p><b>An instruction that cannot apply yet is not an instruction that applies.</b> {@code minuteFrom} is
 * checked with the score condition, so "from 60, if trailing" does not fire at 0–0 — which is not
 * trailing, but is very often where a manager's rule would have fired had the minute not been checked.
 */
public final class MatchTacticPlan {

    private MatchTacticPlan() { }

    /**
     * The instruction in force for one side, or empty when none of them applies.
     *
     * @param assignments that club's assignments for this fixture, in any order
     * @param scoreDifference goals scored by this side minus goals by the other, so positive means ahead
     * @param minute         the match minute, 0 at kickoff
     */
    /**
     * An instruction to change tactic mid-match, and whether it has been spent (T-MATCH-6).
     *
     * <p><b>One-shot, owner ruling.</b> The fired flag is the whole difference between this and an
     * assignment: an assignment is a standing instruction and is asked the same question every tick, while
     * an order fires the first time its condition holds and is never asked again. A standing order would
     * flip the club back and forth as the score moved, and ten rows would produce far more than ten
     * switches.
     *
     * <p>Mutable on purpose: the resolver is created once per match and asked once per tick, so the fired
     * state has to live somewhere and this is the only place that is per-match.
     */
    public static final class Order {
        private final Long tacticId;
        private final TacticMatchCondition condition;
        private final int minuteFrom;
        private final int position;
        private Integer firedAtMinute;

        public Order(Long tacticId, TacticMatchCondition condition, int minuteFrom, int position) {
            if (condition == null) {
                throw new IllegalArgumentException("A tactic order needs a condition");
            }
            this.tacticId = tacticId;
            this.condition = condition;
            this.minuteFrom = minuteFrom;
            this.position = position;
        }

        public Long tacticId() { return tacticId; }
        public int minuteFrom() { return minuteFrom; }
        public int position() { return position; }
        public boolean isPending() { return firedAtMinute == null; }
        public Integer firedAtMinute() { return firedAtMinute; }

        /** Spends the order. Called once, by the resolver, at the minute it fires. */
        void fireAt(int minute) {
            this.firedAtMinute = minute;
        }

        /** Whether the order is ready to fire right now, minute and score difference included. */
        boolean ready(int scoreDifference, int minute) {
            return isPending() && minuteFrom <= minute && condition.holds(scoreDifference);
        }
    }

    /**
     * The order that fires now, or empty when none is ready.
     *
     * <p><b>The last one written wins</b> — owner ruling. Among the orders that are ready, the highest
     * position is taken, because a manager adding a fourth instruction to a list of three almost always
     * means it corrects the one above it, and the alternative silently ignores the row they just added.
     *
     * <p>Exactly one order fires per call even if several are ready, and it is marked spent: firing two at
     * once would apply one and silently drop the other, and the manager would see an instruction vanish.
     *
     * @param scoreDifference this side's goals minus the other side's, so positive means ahead
     * @param minute          the match minute, 0 at kickoff
     */
    public static Optional<Order> orderInForce(List<Order> orders, int scoreDifference, int minute) {
        if (orders == null || orders.isEmpty()) {
            return Optional.empty();
        }
        Order winner = null;
        for (Order order : orders) {
            if (order.ready(scoreDifference, minute) && (winner == null || order.position > winner.position)) {
                winner = order;
            }
        }
        if (winner != null) {
            winner.fireAt(minute);
        }
        return Optional.ofNullable(winner);
    }

    public static Optional<Entry> inForce(List<Entry> assignments, int scoreDifference, int minute) {
        if (assignments == null || assignments.isEmpty()) {
            return Optional.empty();
        }
        return assignments.stream()
                .filter(entry -> entry.minuteFrom() <= minute)
                .filter(entry -> entry.condition().holds(scoreDifference))
                .min(Comparator.comparingInt(Entry::priority));
    }

    /**
     * One instruction, in the form this class needs it.
     *
     * <p>Not the entity. The entity carries a lazy {@code tactic} and a lazy {@code fixture}, and this runs
     * every tick of every match — resolving them inside a tick would be a query per player per second.
     * The caller reads them once, when the match starts, and what travels into the engine is this.
     *
     * @param tacticId the assignment's tactic, so a caller can look up its rules and can tell two
     *                 instructions apart when reporting which one is in force
     */
    public record Entry(Long assignmentId, Long tacticId, int priority,
                        TacticMatchCondition condition, int minuteFrom) {

        public Entry {
            if (condition == null) {
                throw new IllegalArgumentException("A tactic instruction needs a condition");
            }
        }

        public static Entry of(Long assignmentId, Long tacticId, int priority,
                               TacticMatchCondition condition, int minuteFrom) {
            return new Entry(assignmentId, tacticId, priority, condition, minuteFrom);
        }
    }
}