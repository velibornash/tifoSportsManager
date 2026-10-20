package org.example.footballmanager.newLogic.sim;

import org.example.footballmanager.BaseTest;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.tactics.SideTactics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two clubs, two shapes. For the first time.
 *
 * <p><b>The defect.</b> {@code MatchOrchestrator} held one {@code TacticsRules} and every player — home and
 * away — was shaped by it. So a 4-3-3 visitor was resolved against the home 4-4-2's vocabulary and asked
 * about {@code CM}, {@code WL}, {@code WR} and {@code ST}, names a 4-4-2 grid never contains. Each of those
 * players then fell back to his own formation's anchor — safe, thanks to {@code 1420306}, but it meant
 * <b>the away side played no shape at all.</b>
 *
 * <p><b>Why no mirror appears here.</b> {@code TacticalPerspectiveTransformer} stores every club's grid in
 * the editor's single frame and mirrors once at lookup, keyed on the side asking. So the fix is only to hand
 * each side its own object; adding a mirror in {@link SideTactics} would mirror twice and put the away shape
 * in the wrong corners.
 *
 * <p><b>Asserted on roles, not on coordinates.</b> A grid that names {@code ST} cannot produce a cell for
 * {@code WL}, so "does this side's own vocabulary answer for its own roles" is the property that distinguishes
 * the fix, and it survives any change to the pitch geometry. The coordinate assertion that follows is the
 * anti-vacuous half: it proves the roles resolved to <i>real, distinct</i> cells rather than to null.
 */
class EachSidePlaysItsOwnShapeTest extends BaseTest {

    private static final Position CENTRE = new Position(4.5, 4.0);

    // ── The guarantee ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a 4-3-3 visitor is shaped by its own grid, not the home 4-4-2's")
    void theAwaySideIsShapedByItsOwnGrid() {
        TacticsRules home442 = grid("4-4-2", "ST", "LW", "RW", "LM", "RM", "LCM", "RCM", "LB", "RB", "CB", "GK");
        TacticsRules away433 = grid("4-3-3", "ST", "LW", "RW", "LCM", "RCM", "CAM", "LB", "CB", "RB", "GK");

        SideTactics tactics = new SideTactics(home442, away433);
        Player awayWinger = aPlayer("AWAY", "LW");

        Position fromOwnGrid = tactics.forPlayer(awayWinger).desiredCell("LW", CENTRE, "AWAY");

        assertNotNull(fromOwnGrid,
                "the away winger's own grid names LW and should answer for it. It did not, so the away side "
                        + "is still being resolved against somebody else's vocabulary.");
    }

    /**
     * The failing case, spelled out: the role the home grid does not have.
     *
     * <p>{@code 1420306} made an unnamed role return null so a player holds his shape rather than being sent
     * to one hardcoded cell. That is why this defect was invisible and why it was survivable — but "holds his
     * shape" is not "plays his shape", and this is the difference.
     */
    @Test
    @DisplayName("a role the other side's grid does not name is not silently answered from it")
    void aRoleFromTheOtherGridIsNotAnswered() {
        TacticsRules home442 = grid("4-4-2", "ST", "LW", "RW", "LM", "RM", "LCM", "RCM", "LB", "RB", "CB", "GK");
        TacticsRules away433 = grid("4-3-3", "ST", "LW", "RW", "LCM", "RCM", "CAM", "LB", "CB", "RB", "GK");

        SideTactics tactics = new SideTactics(home442, away433);

        // CAM belongs to the away grid only. Asked of the home grid it must come back empty rather than
        // answered, because an answer from the wrong vocabulary is a plausible-looking wrong position.
        Position fromHomeGrid = tactics.forTeam("HOME").desiredCell("CAM", CENTRE, "HOME");
        Position fromOwnGrid = tactics.forTeam("AWAY").desiredCell("CAM", CENTRE, "AWAY");

        assertNull(fromHomeGrid,
                "the home 4-4-2 grid answered for CAM, which it does not name -- so the grids are not being "
                        + "kept apart");
        assertNotNull(fromOwnGrid, "the away 4-3-3 grid names CAM and should answer for it");
    }

    @Test
    @DisplayName("each side gets the grid that belongs to it")
    void eachSideGetsItsOwnGrid() {
        TacticsRules home442 = grid("4-4-2", "ST", "GK");
        TacticsRules away433 = grid("4-3-3", "ST", "GK");

        SideTactics tactics = new SideTactics(home442, away433);

        assertTrue(tactics.forTeam("HOME") == home442, "HOME should get the home grid");
        assertTrue(tactics.forTeam("AWAY") == away433, "AWAY should get the away grid");
        assertTrue(tactics.forPlayer(aPlayer("AWAY", "ST")) == away433,
                "the selector should follow the player's side, not an argument someone forgot");
        assertTrue(tactics.forPlayer(aPlayer("HOME", "ST")) == home442);
    }

    // ── The narrowing, not a lockout ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("one grid for both sides still works, and is still the old behaviour")
    void oneGridForBothSidesStillWorks() {
        TacticsRules one = grid("4-4-2", "ST", "GK");
        SideTactics tactics = new SideTactics(one);

        assertTrue(tactics.forTeam("HOME") == one);
        assertTrue(tactics.forTeam("AWAY") == one,
                "a caller with one grid must keep getting that grid for both sides, or every launcher and "
                        + "diagnostic changes its football");
    }

    @Test
    @DisplayName("a missing away grid falls back to the home one rather than to nothing")
    void aMissingAwayGridFallsBack() {
        TacticsRules home442 = grid("4-4-2", "ST", "GK");
        SideTactics tactics = new SideTactics(home442, null);

        assertTrue(tactics.forTeam("AWAY") == home442,
                "a club with no profile must still get a shape, or half the world stops playing football");
    }

    @Test
    @DisplayName("an unrecognised side falls back to home rather than to nothing")
    void anUnknownSideFallsBack() {
        TacticsRules home442 = grid("4-4-2", "ST", "GK");
        TacticsRules away433 = grid("4-3-3", "ST", "GK");
        SideTactics tactics = new SideTactics(home442, away433);

        assertTrue(tactics.forTeam("NEITHER") == home442);
        assertTrue(tactics.forPlayer(null) == home442, "no player means no side, and home is the old answer");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * A grid naming the given roles, each with its own cell.
     *
     * <p>Cells are spread down the pitch by index so **every role resolves to a distinct position** — which
     * is what makes the coordinate assertions able to tell two answers apart, and stops a fixture that
     * silently returns one cell for everything from passing.
     */
    private static TacticsRules grid(String formation, String... roles) {
        Map<String, Map<String, Position>> inPossession = new HashMap<>();
        Map<String, Map<String, Position>> outOfPossession = new HashMap<>();
        Map<String, Position> anchors = new HashMap<>();
        for (int i = 0; i < roles.length; i++) {
            String role = roles[i];
            double row = 1.0 + (i * 6.0 / Math.max(1, roles.length - 1));
            double col = 1.0 + (i % 5) * 1.2;
            Position cell = new Position(row, col);
            anchors.put(role, cell);
            inPossession.put(role, new HashMap<>(Map.of("WE_HAVE_BALL", cell)));
            outOfPossession.put(role, new HashMap<>(Map.of("OPPONENT_HAS_BALL", new Position(row + 0.5, col))));
        }
        return new TacticsRules(inPossession, outOfPossession, anchors, "test:" + formation);
    }

    /** The sim's own Player: immutable, and built through its constructor. */
    private static Player aPlayer(String side, String role) {
        return new Player(side + "-" + role, side + " " + role, side, role,
                CENTRE, CENTRE, org.example.footballmanager.newLogic.sim.model.PlayerSkills.neutral(), 180);
    }
}