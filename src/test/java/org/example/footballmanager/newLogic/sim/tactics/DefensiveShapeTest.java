package org.example.footballmanager.newLogic.sim.tactics;

import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 4.6 — the out-of-possession shape.
 *
 * <p>Both rule sets were shipped and both were consulted, and all 506 out-of-possession rules were
 * copies of their in-possession twins. Everything below would have passed against that build, which
 * is the point: the bug was not a crash, it was a feature that did nothing, and only a test that
 * asserts the two shapes <b>differ</b> can see it.
 */
class DefensiveShapeTest {

    private static final Position MIDFIELD_ATTACKING = new Position(5.5, 4.0);
    private static final Position CENTRE_BALL = new Position(4.5, 4.0);

    @Test
    @DisplayName("Defending is not the same shape as attacking — the bug this fixes")
    void theTwoShapesAreDifferent() {
        Position attacking = new Position(7.0, 4.0);
        Position defending = DefensiveShape.derive(attacking, CENTRE_BALL, "STL");

        assertFalse(DefensiveShape.sameShape(attacking, defending),
                "the defensive block is identical to the attacking shape, so possession context"
                        + " still does nothing");
        assertNotNull(defending);
    }

    @Test
    @DisplayName("A team drops toward its own goal when it defends")
    void theBlockDrops() {
        Position high = new Position(7.0, 4.0);
        Position defensive = DefensiveShape.derive(high, CENTRE_BALL, "STL");
        assertTrue(defensive.getRow() < high.getRow(),
                "a striker did not come back to defend: " + defensive.getRow());
    }

    @Test
    @DisplayName("The further forward a player was, the further he has to come back")
    void aStrikerWalksFurtherThanAFullBack() {
        Position striker = new Position(7.0, 4.0);
        Position fullBack = new Position(2.0, 1.5);
        double strikerDrop = striker.getRow() - DefensiveShape.derive(striker, CENTRE_BALL, "STL").getRow();
        double fullBackDrop = fullBack.getRow() - DefensiveShape.derive(fullBack, CENTRE_BALL, "DFL").getRow();
        assertTrue(strikerDrop > fullBackDrop * 3,
                "a striker dropped " + strikerDrop + " and a full-back " + fullBackDrop
                        + ", so everybody retreats by the same distance");
    }

    @Test
    @DisplayName("Nobody defends from behind their own goal line")
    void nobodyIsBehindTheirOwnGoal() {
        Position onHisOwnLine = new Position(1.1, 4.0);
        Position defensive = DefensiveShape.derive(onHisOwnLine, CENTRE_BALL, "DFL");
        assertTrue(defensive.getRow() >= 1.2,
                "a defender was placed behind his own goal line at " + defensive.getRow());
    }

    @Test
    @DisplayName("The block narrows, which is what closing down space means")
    void theBlockCompacts() {
        Position wideLeft = new Position(4.0, 1.2);
        Position defensive = DefensiveShape.derive(wideLeft, CENTRE_BALL, "ML");
        assertTrue(defensive.getColumn() > wideLeft.getColumn(),
                "the wide player got wider while defending");
        assertTrue(defensive.getColumn() < 3.0,
                "a winger did not come inside to defend, ended at column " + defensive.getColumn());
    }

    @Test
    @DisplayName("The block slides toward the ball, so the near side presses")
    void theBlockShiftsTowardTheBall() {
        Position shape = new Position(4.0, 4.0);
        Position ballLeft = new Position(4.5, 1.5);
        Position ballRight = new Position(4.5, 6.5);

        double left = DefensiveShape.derive(shape, ballLeft, "CMR").getColumn();
        double right = DefensiveShape.derive(shape, ballRight, "CMR").getColumn();
        assertTrue(left < right, "the block did not move with the ball");
    }

    @Test
    @DisplayName("The shift is partial, because a block that slid all the way concedes from the far post")
    void theShiftIsPartial() {
        Position shape = new Position(4.0, 4.0);
        Position farBall = new Position(4.5, 7.0);
        double shifted = DefensiveShape.derive(shape, farBall, "CMR").getColumn();
        assertTrue(shifted < 7.0 && shifted > 4.0,
                "the block either abandoned the far side or did not move at all: " + shifted);
    }

    @Test
    @DisplayName("A goalkeeper holds his line instead of dropping with the outfield")
    void theKeeperStays() {
        Position keeper = new Position(1.5, 4.0);
        Position defensive = DefensiveShape.derive(keeper, CENTRE_BALL, "GK");
        assertEquals(keeper.getRow(), defensive.getRow(), 1e-9,
                "the goalkeeper was dragged out of his six-yard box");
        assertEquals(keeper.getColumn(), defensive.getColumn(), 1e-9);
    }

    @Test
    @DisplayName("Nobody defends from off the pitch")
    void everybodyStaysOnThePitch() {
        for (double row = 1.0; row <= 7.6; row += 0.5) {
            for (double col = 0.8; col <= 7.2; col += 0.5) {
                for (String role : new String[]{"GK", "DFL", "CMR", "ML", "STL", "MR"}) {
                    Position defensive = DefensiveShape.derive(new Position(row, col), CENTRE_BALL, role);
                    assertNotNull(defensive);
                    assertTrue(defensive.getColumn() >= 1.0 && defensive.getColumn() <= 7.0,
                            role + " was told to defend at column " + defensive.getColumn()
                                    + " from " + row + "/" + col);
                    assertTrue(defensive.getRow() >= 1.0 && defensive.getRow() <= 7.6,
                            role + " was told to defend at row " + defensive.getRow());
                }
            }
        }
    }

    @Test
    @DisplayName("A missing ball or shape is handled rather than throwing mid-match")
    void missingInputsAreSafe() {
        assertNull(DefensiveShape.derive(null, CENTRE_BALL, "CMR"));
        Position noBall = DefensiveShape.derive(MIDFIELD_ATTACKING, null, "CMR");
        assertNotNull(noBall);
        assertTrue(noBall.getRow() < MIDFIELD_ATTACKING.getRow(),
                "with no ball the block neither dropped nor compacted");
    }
}
