package org.example.footballmanager.newLogic.sim.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tactic is in force, and when (T0-BE-2).
 *
 * <p>Pure logic, so every combination of the owner's six conditions is checked here rather than
 * discovered in a match. The engine calls this every tick; a wrong answer is a team that changes shape at
 * a moment nobody chose, or does not change shape when they said it would.
 */
class MatchTacticPlanTest {

    private static final Long ALWAYS_442 = 1L;
    private static final Long LEADING_433 = 2L;
    private static final Long TRAILING_541 = 3L;
    private static final Long LATE_442 = 4L;

    private static MatchTacticPlan.Entry entry(Long tacticId, int priority,
                                               TacticMatchCondition condition, int minuteFrom) {
        return MatchTacticPlan.Entry.of((long) (100 + priority), tacticId, priority, condition, minuteFrom);
    }

    // ── the six conditions, read from one number ────────────────────────────────────────────────────────

    @Test
    @DisplayName("every condition is true of exactly the score difference it names")
    void eachConditionMatchesItsOwnMargin() {
        assertTrue(TacticMatchCondition.ALWAYS.holds(-5), "always is always");
        assertTrue(TacticMatchCondition.ALWAYS.holds(0));

        assertTrue(TacticMatchCondition.LEADING_BY_ONE.holds(1));
        assertFalse(TacticMatchCondition.LEADING_BY_ONE.holds(2), "leading by two is not leading by one");
        assertFalse(TacticMatchCondition.LEADING_BY_ONE.holds(0));

        assertTrue(TacticMatchCondition.LEADING_BY_THREE.holds(3));
        assertTrue(TacticMatchCondition.LEADING_BY_THREE.holds(7), "three or more means three or more");
        assertFalse(TacticMatchCondition.LEADING_BY_THREE.holds(2));

        assertTrue(TacticMatchCondition.DRAWING.holds(0));
        assertFalse(TacticMatchCondition.DRAWING.holds(1));

        assertTrue(TacticMatchCondition.TRAILING_BY_ONE.holds(-1));
        assertFalse(TacticMatchCondition.TRAILING_BY_ONE.holds(-2));

        assertTrue(TacticMatchCondition.TRAILING_BY_THREE.holds(-3));
        assertTrue(TacticMatchCondition.TRAILING_BY_THREE.holds(-9));
        assertFalse(TacticMatchCondition.TRAILING_BY_THREE.holds(-2));
    }

    @Test
    @DisplayName("the same list reads correctly from both sides of the same score")
    void theConditionIsRelativeToTheSideReadingIt() {
        // No "always" rule in either list, so what is being checked is the condition and not the priority
        // ordering — which priorityBreaksTies covers on its own.
        List<MatchTacticPlan.Entry> home = List.of(
                entry(LEADING_433, 1, TacticMatchCondition.LEADING_BY_ONE, 0));
        // The away club reads the same fixture with the sign reversed, because a deficit for them is a
        // lead for the home side. There is no second set of condition names.
        List<MatchTacticPlan.Entry> away = List.of(
                entry(TRAILING_541, 1, TacticMatchCondition.TRAILING_BY_ONE, 0));

        assertEquals(LEADING_433,
                MatchTacticPlan.inForce(home, 1, 30).orElseThrow().tacticId(),
                "the home club is a goal up, so its leading rule applies");
        assertEquals(TRAILING_541,
                MatchTacticPlan.inForce(away, -1, 30).orElseThrow().tacticId(),
                "the same 1-0 is a one-goal deficit for the away club, so its trailing rule applies");
        assertTrue(MatchTacticPlan.inForce(home, -1, 30).isEmpty(),
                "and the home club is not leading, so its leading rule does not apply");
        assertTrue(MatchTacticPlan.inForce(away, 1, 30).isEmpty(),
                "and the away club is not trailing, so its trailing rule does not apply");
    }

    // ── priority ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("priority decides when two conditions are true at once, not the order they were listed")
    void priorityBreaksTies() {
        List<MatchTacticPlan.Entry> listedWrongOrder = List.of(
                entry(TRAILING_541, 3, TacticMatchCondition.ALWAYS, 0),
                entry(LEADING_433, 2, TacticMatchCondition.LEADING_BY_ONE, 0),
                entry(ALWAYS_442, 1, TacticMatchCondition.ALWAYS, 0));

        assertEquals(ALWAYS_442,
                MatchTacticPlan.inForce(listedWrongOrder, 2, 30).orElseThrow().tacticId(),
                "priority 1 wins even though it was listed last, and even though 'always' also holds");
    }

    @Test
    @DisplayName("a lower-priority rule fills a gap its higher-priority sibling does not cover")
    void priorityLeavesTheGapToTheDefault() {
        List<MatchTacticPlan.Entry> plan = List.of(
                entry(LEADING_433, 1, TacticMatchCondition.LEADING_BY_ONE, 0),
                entry(TRAILING_541, 2, TacticMatchCondition.TRAILING_BY_THREE, 0));

        assertEquals(LEADING_433, MatchTacticPlan.inForce(plan, 1, 20).orElseThrow().tacticId());
        assertEquals(TRAILING_541, MatchTacticPlan.inForce(plan, -4, 20).orElseThrow().tacticId());
        assertTrue(MatchTacticPlan.inForce(plan, 2, 20).isEmpty(),
                "leading by two is covered by neither rule, so nothing applies and the club's default "
                        + "stands — which is the whole point of numbering them");
        assertTrue(MatchTacticPlan.inForce(plan, 0, 20).isEmpty(), "0–0 is neither of the two rules");
    }

    // ── minuteFrom ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an instruction that starts at 60 does not fire before 60")
    void minuteFromGatesTheInstruction() {
        List<MatchTacticPlan.Entry> plan = List.of(
                entry(LATE_442, 1, TacticMatchCondition.ALWAYS, 60),
                entry(ALWAYS_442, 2, TacticMatchCondition.ALWAYS, 0));

        assertEquals(ALWAYS_442, MatchTacticPlan.inForce(plan, 0, 59).orElseThrow().tacticId(),
                "at 0–0 in the first hour, the from-60 rule must not apply even though 'always' holds — "
                        + "otherwise 'from minute 60' would mean nothing");
        assertEquals(LATE_442, MatchTacticPlan.inForce(plan, 0, 60).orElseThrow().tacticId());
        assertEquals(LATE_442, MatchTacticPlan.inForce(plan, 0, 61).orElseThrow().tacticId());
    }

    @Test
    @DisplayName("minuteFrom is checked with the score condition, not instead of it")
    void minuteFromAndConditionBothApply() {
        List<MatchTacticPlan.Entry> plan = List.of(
                entry(TRAILING_541, 1, TacticMatchCondition.TRAILING_BY_THREE, 60));

        assertTrue(MatchTacticPlan.inForce(plan, 0, 70).isEmpty(),
                "the score is not yet trailing by three, so the rule does not apply however late it is");
        assertEquals(TRAILING_541, MatchTacticPlan.inForce(plan, -3, 70).orElseThrow().tacticId());
        assertTrue(MatchTacticPlan.inForce(plan, -3, 59).isEmpty(), "and it has not started yet");
    }

    // ── the states that must not throw ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a club with no assignments has nothing in force, which is not an error")
    void noAssignmentsMeansTheDefaultStands() {
        assertTrue(MatchTacticPlan.inForce(List.of(), 2, 30).isEmpty());
        assertTrue(MatchTacticPlan.inForce(null, 2, 30).isEmpty(),
                "a fixture nobody assigned must not be able to break a match");
    }

    @Test
    @DisplayName("an instruction with no condition cannot be built")
    void anInstructionNeedsACondition() {
        assertThrows(IllegalArgumentException.class,
                () -> MatchTacticPlan.Entry.of(1L, 1L, 1, null, 0),
                "a null condition would be a rule that means nothing, and it would have to be defended "
                        + "against on every tick instead of once at construction");
    }

    @Test
    @DisplayName("the six conditions the owner named are the six that exist")
    void theOwnersSixAreTheOnesImplemented() {
        assertEquals(List.of("ALWAYS", "LEADING_BY_ONE", "LEADING_BY_THREE",
                        "DRAWING", "TRAILING_BY_ONE", "TRAILING_BY_THREE"),
                java.util.Arrays.stream(TacticMatchCondition.values()).map(Enum::name).toList(),
                "always, leading by 1, leading by 3+, drawing, trailing by 1, trailing by 3+ — and no "
                        + "others, because a condition nobody specified is one nobody can predict");
        for (TacticMatchCondition condition : TacticMatchCondition.values()) {
            assertFalse(condition.label().isBlank(), condition.name() + " needs a label for the dropdown");
        }
    }
}