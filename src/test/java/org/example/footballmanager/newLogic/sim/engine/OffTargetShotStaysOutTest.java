package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.GoalPhysical;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shot the engine calls off target must cross the goal line outside the mouth.
 *
 * <p>Owner, 2026-10-18, ENG-TEST-2. The classifier decides {@code onTarget} once, from the aim point,
 * and {@code BallPhysicsEngine} decides the goal at the crossing. When those two disagree, the scoreline
 * says a shot missed and the ball went in.
 *
 * <p><b>What was wrong.</b> The aim was placed wide of the post AND short of the goal line, on the
 * reasoning that a ball short of the line cannot score. But the ball is launched airborne at up to 1.5
 * cells/tick against {@code AIR_DECEL = 0.03}, a range of v²/(2a) — about 37 cells. It passes through the
 * aim point and keeps going, so the aim was only a direction, and a direction pointing a little wide of
 * a post, taken from far out, crosses the line inside the mouth. Enumerating the shipped formula over the
 * pitch grid found 37 origin/aim combinations that scored.
 *
 * <p><b>Why this enumerates rather than sampling.</b> The bug lived in the geometry, not in a random
 * draw, so a loop over every reachable origin and every outcome the formula can produce either finds
 * the fault or proves there is none. Sampling would have missed it — that is how it shipped.
 */
class OffTargetShotStaysOutTest {

    /** Every row and column a striker can stand on. */
    private static final double MIN_POS = 1.0;
    private static final double MAX_POS = 8.0;
    private static final double STEP = 0.5;

    @Test
    @DisplayName("every off-target shot crosses the goal line outside the mouth")
    void everyOffTargetShotCrossesOutsideTheMouth() {
        int checked = 0;
        for (double row = MIN_POS; row <= MAX_POS; row += STEP) {
            for (double col = 0.9; col <= 7.0; col += STEP) {
                for (boolean attackingHigherRows : new boolean[]{true, false}) {
                    // The build is deterministic given the aim, so this enumerates the AIMS the
                    // formula can produce rather than re-rolling: 400 draws per origin, which covers
                    // the 0.3-1.3 cell clearance range densely enough to catch a boundary error.
                    for (int draw = 0; draw < 400; draw++) {
                        checked++;
                        Position origin = new Position(row, col);
                        Position goal = attackingHigherRows
                                ? new Position(8.0, 4.0)
                                : new Position(1.0, 4.0);

                        var result = ExecutionQuality.evaluateShot(goal, 14, 0.3, origin);
                        if (result.isOnTarget()) continue;

                        Position aim = result.getActualTarget();
                        double crossing = columnAtGoalLine(origin, aim, goal.getRow());
                        assertTrue(crossing < GoalPhysical.MOUTH_LEFT - 1e-6
                                        || crossing > GoalPhysical.MOUTH_RIGHT + 1e-6,
                                "an off-target shot from (" + String.format("%.1f", row) + ","
                                        + String.format("%.1f", col) + ") aimed at ("
                                        + String.format("%.1f", aim.getRow()) + ","
                                        + String.format("%.1f", aim.getColumn())
                                        + ") crosses the goal line at column "
                                        + String.format("%.3f", crossing)
                                        + ", which is inside the mouth "
                                        + GoalPhysical.MOUTH_LEFT + ".." + GoalPhysical.MOUTH_RIGHT);
                    }
                }
            }
        }
        assertTrue(checked > 10_000, "the sweep must actually cover the grid, checked " + checked);
    }

    @Test
    @DisplayName("an on-target shot does cross inside the mouth")
    void onTargetShotsDoCrossInsideTheMouth() {
        // The other direction. A fix that made every shot miss would pass the test above, so this
        // asserts the classifier and the geometry still agree when the shot IS meant to be on target.
        int onTarget = 0;
        for (double row = 6.0; row <= 8.0; row += STEP) {
            for (double col = 2.0; col <= 6.0; col += STEP) {
                Position origin = new Position(row, col);
                Position goal = new Position(8.0, 4.0);
                for (int draw = 0; draw < 200; draw++) {
                    var result = ExecutionQuality.evaluateShot(goal, 14, 0.3, origin);
                    if (!result.isOnTarget()) continue;
                    onTarget++;
                    double crossing = columnAtGoalLine(origin, result.getActualTarget(), goal.getRow());
                    assertTrue(crossing >= GoalPhysical.MOUTH_LEFT - 1e-6
                                    && crossing <= GoalPhysical.MOUTH_RIGHT + 1e-6,
                            "an on-target shot aimed at column "
                                    + String.format("%.3f", result.getActualTarget().getColumn())
                                    + " crosses at " + String.format("%.3f", crossing)
                                    + ", outside the mouth");
                }
            }
        }
        assertTrue(onTarget > 100, "the sweep must actually reach on-target shots, got " + onTarget);
    }

    /**
     * Where the line from the origin through the aim meets the goal line.
     *
     * <p>This is the whole of the bug: {@code BallPhysicsEngine} flies the ball along
     * origin→aim and tests the goal at the crossing, so a shot's outcome is decided by this number and
     * not by where the aim point happens to be.
     */
    private static double columnAtGoalLine(Position origin, Position aim, double goalRow) {
        double dy = aim.getRow() - origin.getRow();
        if (Math.abs(dy) < 1e-9) return aim.getColumn();     // parallel to the line
        double t = (goalRow - origin.getRow()) / dy;
        return origin.getColumn() + t * (aim.getColumn() - origin.getColumn());
    }
}