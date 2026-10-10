package org.example.footballmanager.newLogic.sim.tactics;

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