package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One club's grid is written from the HOME perspective and mirrored when that club is the away side
 * (owner, 2026-10-10).
 *
 * <p><b>The owner's concern, stated precisely.</b> The editor has a single frame. Every club's grid is
 * authored in it, from the home perspective. When a club is the away side its stored grid means "two cells
 * from the left touchline, on the far side" — which, taken literally, walks that team onto the wrong half
 * of the pitch and towards its own goal. The mirror is what makes a stored grid mean "from my own
 * perspective" wherever the club happens to be playing.
 *
 * <p><b>Where the mirror lives, and why it is not in {@code SideTactics}.</b>
 * {@link TacticalPerspectiveTransformer} converts between editor and physical coordinates, and
 * {@link TacticsRules#desiredCell(String, Position, String)} applies it once, keyed on the team asking.
 * Every club's grid therefore lives in the same frame, and selecting the right object per side is the whole
 * of the fix. A mirror inside {@code SideTactics} would mirror twice and put the away shape back where it
 * started.
 *
 * <p><b>This was asserted by comment and not by test.</b> {@code SideTactics} and
 * {@code TacticalPerspectiveTransformer} each carry a long comment saying the perspective is handled
 * elsewhere, and {@code EachSidePlaysItsOwnShapeTest} proves each side is handed its own grid — but
 * nothing asserted that a grid handed over as AWAY comes out mirrored. "Both sides may be the same object"
 * made that gap easy to leave.
 */
class AwaySideMirrorsTheHomePerspectiveTest {

    /**
     * A real grid in the editor's frame.
     *
     * <p>{@code ballStateKey} is <b>where the ball is</b>, not where the player goes — a rule is keyed on
     * the state it applies to, and the target is where the player stands in that state. The ball sits at
     * (4.5, 4.0) in every assertion below, which is centre row and the 4.0 symmetry column, so
     * {@code ballStateKey(4.5, 4.0)} is {@code CELL_3_3} for <em>both</em> sides — a ball on the halfway
     * line maps to itself, which is what keeps the mirror comparison clean.
     */
    private static TacticsRules aClubsGrid() {
        TacticsRules rules = TacticsRules.fromProfileJson(
                "[{\"slotKey\":\"ST\",\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"CELL_3_3\",\"targetCellKey\":\"CELL_5_2\"}]",
                "4-4-2",
                "test club");
        assertNotNull(rules, "the test grid must parse, or every assertion below is measuring nothing");
        return rules;
    }

    /** The pitch in physical coordinates: HOME goal line row 1.0, AWAY goal line row 8.0, centre 4.5. */
    private static Position cell(double row, double column) {
        return new Position(row, column);
    }

    @Test
    @DisplayName("the same grid, asked for AWAY, comes out mirrored on both axes")
    void theSameGridMirrorsForAway() {
        TacticsRules club = aClubsGrid();
        Position ball = cell(4.5, 4.0);

        Position asHome = club.desiredCell("ST", ball, "HOME");
        Position asAway = club.desiredCell("ST", ball, "AWAY");
        assertNotNull(asHome, "the home answer must exist for the mirror to mean anything");
        assertNotNull(asAway, "the away answer must exist for the mirror to mean anything");

        assertEquals(9.0 - asHome.getRow(), asAway.getRow(), 1e-9,
                "row must mirror about the centre line: a club's grid is authored from the home "
                        + "perspective, and unmirrored it would walk the away side towards its own goal");
        assertEquals(8.0 - asHome.getColumn(), asAway.getColumn(), 1e-9,
                "column must mirror about 4.0 — the pitch's only symmetry axis. It used to reflect over "
                        + "3.5, which pushed away's right-sided players outside the touchline");
    }

    @Test
    @DisplayName("the club's own goal line is the end it defends, whichever side it is on")
    void theClubDefendsItsOwnGoal() {
        TacticsRules club = aClubsGrid();
        Position ball = cell(4.5, 4.0);

        // HOME goal line is row 1.0, AWAY goal line is row 8.0.
        assertEquals(1.0, TacticalPerspectiveTransformer.toPhysical(cell(1.0, 4.0), "HOME").getRow(), 1e-9,
                "the editor's row 1 is the home goal line");
        assertEquals(8.0, TacticalPerspectiveTransformer.toPhysical(cell(1.0, 4.0), "AWAY").getRow(), 1e-9,
                "and for the away side the same authored row becomes row 8 — which is the goal that side "
                        + "defends. A grid authored at row 1 is the keeper's own goal for whoever plays it");
    }

    @Test
    @DisplayName("both sides handed the same grid still get opposite halves")
    void bothSidesOnOneGridDoNotOverlap() {
        // The fallback for a club with no profile is one object for both sides. That is only safe if the
        // mirror is applied at lookup, which is exactly what this asserts.
        SideTactics both = new SideTactics(aClubsGrid());
        Position ball = cell(4.5, 4.0);

        Position home = both.forTeam("HOME").desiredCell("ST", ball, "HOME");
        Position away = both.forTeam("AWAY").desiredCell("ST", ball, "AWAY");

        assertEquals(9.0 - home.getRow(), away.getRow(), 1e-9,
                "one object handed to both sides must still produce two mirrored shapes, or both teams "
                        + "stack on the same half of the pitch");
        assertEquals(8.0 - home.getColumn(), away.getColumn(), 1e-9);
    }

    @Test
    @DisplayName("the resolver hands the away side a grid that still mirrors")
    void theConditionalPlanPreservesTheMirror() {
        // The path added by T0-BE-2. A fixture can now put any of a club's tactics in force for either
        // side, so a club's grid reaches the engine through a new door — and a new door is exactly where
        // a perspective bug would arrive unnoticed.
        TacticsRules awayClubGrid = aClubsGrid();
        TacticsRules homeDefault = TacticsRules.fromProfileJson(
                "[{\"slotKey\":\"ST\",\"possessionContext\":\"WE_HAVE_BALL\","
                        + "\"ballStateKey\":\"CELL_3_3\",\"targetCellKey\":\"CELL_4_5\"}]",
                "4-4-2", "home default");
        assertNotNull(homeDefault, "the default grid must parse");

        var resolver = new MatchTacticsResolver(
                java.util.List.of(),
                java.util.List.of(MatchTacticPlan.Entry.of(
                        1L, 5L, 1, TacticMatchCondition.ALWAYS, 0)),
                id -> null,
                Map.of(5L, awayClubGrid)::get,
                homeDefault, homeDefault);

        SideTactics atKickoff = resolver.resolve(0, 0, 0);
        Position ball = cell(4.5, 4.0);

        Position awayFromPlan = atKickoff.forTeam("AWAY").desiredCell("ST", ball, "AWAY");
        Position awayDirect = awayClubGrid.desiredCell("ST", ball, "AWAY");
        Position awayAsHome = awayClubGrid.desiredCell("ST", ball, "HOME");

        assertEquals(9.0 - awayAsHome.getRow(), awayFromPlan.getRow(), 1e-9,
                "a tactic put in force through the game plan must still be mirrored for the away side — the "
                        + "conditional plan changes WHICH grid, never WHOSE perspective it is read in");
        assertEquals(8.0 - awayAsHome.getColumn(), awayFromPlan.getColumn(), 1e-9);
        assertEquals(awayDirect.getRow(), awayFromPlan.getRow(), 1e-9,
                "and it must be the same answer as asking that grid directly, not a second mirror");
    }
}