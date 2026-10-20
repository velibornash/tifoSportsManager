package org.example.footballmanager.newLogic.sim.tactics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tactics in force at a moment, and what happens when there are none (T0-BE-2).
 *
 * <p>The fallback chain is the substance here. A club that set no instruction must still play, and a club
 * whose instruction points at a deleted tactic must still play — just with its default. Both are ordinary
 * states and neither may stop a match.
 */
class MatchTacticsResolverTest {

    /**
     * Stand-ins: the resolver's job is to choose between rule sets, not to build them, so these carry an
     * identity and a source label and nothing else.
     */
    private static TacticsRules rules(String label) {
        return new TacticsRules(java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), label);
    }

    @Test
    @DisplayName("an instruction in force is used for that side only")
    void theInstructionAppliesToItsOwnSide() {
        TacticsRules homeDefault = rules("home default");
        TacticsRules awayDefault = rules("away default");
        TacticsRules home433 = rules("home 4-3-3");

        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 99L, 1, TacticMatchCondition.LEADING_BY_ONE, 0)),
                List.of(),
                id -> id != null && id == 99L ? home433 : null,
                id -> null,
                homeDefault, awayDefault);

        // Home are a goal up: their leading instruction is in force and the away side is untouched.
        SideTactics leading = resolver.resolve(1, 0, 30);
        assertSame(home433, leading.home(), "the home club is leading by one, so its instruction applies");
        assertSame(awayDefault, leading.away(), "the away club set nothing, so its default stands");

        // Level: the instruction no longer holds and the home default takes over.
        assertSame(homeDefault, resolver.resolve(0, 0, 30).home(),
                "at 0-0 the leading instruction does not apply, and the default must take over rather "
                        + "than the club keeping a shape that is no longer in force");
    }

    @Test
    @DisplayName("both sides read the same score from their own point of view")
    void bothSidesAreResolvedIndependently() {
        TacticsRules home433 = rules("home 4-3-3");
        TacticsRules away541 = rules("away 5-4-1");

        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 1L, 1, TacticMatchCondition.LEADING_BY_ONE, 0)),
                List.of(MatchTacticPlan.Entry.of(2L, 2L, 1, TacticMatchCondition.TRAILING_BY_ONE, 0)),
                Map.of(1L, home433)::get,
                Map.of(2L, away541)::get,
                rules("home default"), rules("away default"));

        SideTactics at1 = resolver.resolve(1, 0, 45);

        assertSame(home433, at1.home(), "home are leading");
        assertSame(away541, at1.away(), "and the away club is trailing, which is its own instruction — the "
                + "same 1-0 read from the other side of the pitch");
    }

    @Test
    @DisplayName("a club that set nothing plays its default")
    void anUnassignedClubPlaysItsDefault() {
        TacticsRules homeDefault = rules("home default");
        var resolver = new MatchTacticsResolver(List.of(), List.of(), id -> null, id -> null,
                homeDefault, rules("away default"));

        SideTactics anyMoment = resolver.resolve(3, 1, 70);

        assertSame(homeDefault, anyMoment.home());
        assertTrue(!resolver.hasAssignments(),
                "a fixture nobody assigned is the ordinary case, not an error state");
    }

    @Test
    @DisplayName("an instruction whose tactic has gone resolves to the default")
    void aDeletedTacticFallsBackToTheDefault() {
        TacticsRules homeDefault = rules("home default");
        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 404L, 1, TacticMatchCondition.ALWAYS, 0)),
                List.of(),
                id -> null, id -> null,          // every tactic id resolves to nothing
                homeDefault, rules("away default"));

        assertSame(homeDefault, resolver.resolve(0, 0, 10).home(),
                "assignments reference their tactic rather than copying it, so a deleted tactic leaves an "
                        + "assignment pointing at nothing. The club's default is the honest answer — a match "
                        + "must not play a shape nobody chose, and must not stop either");
    }

    @Test
    @DisplayName("a minute gate holds an instruction back until its minute")
    void theMinuteGateReachesTheRules() {
        TacticsRules lateShape = rules("late shape");
        TacticsRules homeDefault = rules("home default");
        var resolver = new MatchTacticsResolver(
                List.of(MatchTacticPlan.Entry.of(1L, 7L, 1, TacticMatchCondition.ALWAYS, 70)),
                List.of(),
                Map.of(7L, lateShape)::get, id -> null,
                homeDefault, rules("away default"));

        assertSame(homeDefault, resolver.resolve(0, 0, 69).home(), "not yet");
        assertSame(lateShape, resolver.resolve(0, 0, 70).home(), "now");
    }

    @Test
    @DisplayName("null lists are treated as none set, not as a crash")
    void nullsAreAnEmptyPlan() {
        var resolver = new MatchTacticsResolver(null, null, id -> null, id -> null,
                rules("home default"), rules("away default"));

        assertEquals("home default", resolver.resolve(0, 0, 0).home().getSource());
        assertTrue(!resolver.hasAssignments());
    }
}