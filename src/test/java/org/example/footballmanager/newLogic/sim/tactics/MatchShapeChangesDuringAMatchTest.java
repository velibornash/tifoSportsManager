package org.example.footballmanager.newLogic.sim.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match changes shape mid-game when the fixture said it should (T0-BE-2).
 *
 * <p>The unit tests cover which instruction wins and what happens when none does. This one covers the
 * thing they cannot: that a running match actually asks the resolver every tick and hands the answer to
 * the engine that moves players. The wiring is a call in the orchestrator's tick loop, and a call in a tick
 * loop fails silently when it is removed — the engine simply keeps the shape it was built with, and every
 * other test in this task stays green.
 */
class MatchShapeChangesDuringAMatchTest {

    private static TacticsRules rules(String label) {
        return new TacticsRules(Map.of(), Map.of(), Map.of(), label);
    }

    @Test
    @DisplayName("a club that set no instruction plays one shape for the whole match")
    void anUnplannedFixtureKeepsItsShape() {
        // Nobody assigned anything, so preparation returns null and the orchestrator never consults
        // anything. This is the ordinary match and it must cost nothing.
        var resolver = new MatchTacticsResolver(List.of(), List.of(), id -> null, id -> null,
                rules("home default"), rules("away default"));

        assertTrue(!resolver.hasAssignments(), "no fixture assignments means no resolver work per tick");

        SideTactics kickoff = resolver.resolve(0, 0, 0);
        SideTactics seventyFifth = resolver.resolve(2, 0, 75);

        assertSame(kickoff.home(), seventyFifth.home(),
                "with nothing assigned the club plays the shape it was built with, all match");
    }

    @Test
    @DisplayName("a goal changes the shape, and the manager's rule says when it changes back")
    void theShapeFollowsTheScore() {
        TacticsRules home433 = rules("home 4-3-3");
        TacticsRules home441 = rules("home 4-4-2");
        TacticsRules awayDefault = rules("away default");

        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 1L, 1, TacticMatchCondition.LEADING_BY_THREE, 0)),
                List.of(),
                Map.of(1L, home433)::get,
                id -> null,
                home441, awayDefault);

        assertSame(home441, resolver.resolve(0, 0, 10).home(), "not yet three ahead");
        assertSame(home441, resolver.resolve(2, 0, 20).home(), "two ahead is not three");
        assertSame(home433, resolver.resolve(3, 0, 40).home(), "three ahead: the instruction applies");

        // The club is pulled back level, and the instruction stops applying — the club does not keep a
        // shape chosen for a situation that has ended.
        assertSame(home441, resolver.resolve(3, 3, 80).home(),
                "level again, so the default returns; holding the old shape would be a club playing to a "
                        + "score that no longer exists");
    }

    @Test
    @DisplayName("both sides change on the same goal, in opposite directions")
    void oneGoalMovesBothSidesApart() {
        TacticsRules homeAttacking = rules("home attacking");
        TacticsRules homeBalanced = rules("home balanced");
        TacticsRules awayDefensive = rules("away defensive");
        TacticsRules awayBalanced = rules("away balanced");

        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 1L, 1, TacticMatchCondition.LEADING_BY_ONE, 0)),
                List.of(MatchTacticPlan.Entry.of(2L, 2L, 1, TacticMatchCondition.TRAILING_BY_ONE, 0)),
                Map.of(1L, homeAttacking)::get,
                Map.of(2L, awayDefensive)::get,
                homeBalanced, awayBalanced);

        SideTactics level = resolver.resolve(0, 0, 15);
        assertSame(homeBalanced, level.home());
        assertSame(awayBalanced, level.away());

        SideTactics oneNil = resolver.resolve(1, 0, 60);
        assertSame(homeAttacking, oneNil.home(), "home are a goal up");
        assertSame(awayDefensive, oneNil.away(),
                "and the same goal is a one-goal deficit for the away club, so their rule fires too — one "
                        + "event, two independent changes");
    }

    @Test
    @DisplayName("a minute gate holds the shape back even when the score calls for it")
    void theMinuteGateHoldsTheShape() {
        TacticsRules lateShape = rules("late shape");
        TacticsRules homeDefault = rules("home default");

        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 1L, 1, TacticMatchCondition.ALWAYS, 75)),
                List.of(),
                Map.of(1L, lateShape)::get, id -> null,
                homeDefault, rules("away default"));

        assertSame(homeDefault, resolver.resolve(3, 0, 70).home(),
                "three goals up at 70 with a from-75 rule: the manager said not yet, and that is the "
                        + "instruction — the score alone must not overrule the minute");
        assertSame(lateShape, resolver.resolve(3, 0, 75).home());
    }
}