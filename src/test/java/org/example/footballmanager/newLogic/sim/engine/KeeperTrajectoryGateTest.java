package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.BallStepResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.1 — a keeper must not fish at balls that were never going in.
 *
 * <p>{@code GoalkeeperEngine.trySave} measures only how close the flight segment passes to the
 * keeper's body. A shot aimed metres wide of the post could therefore be "saved" simply by
 * passing near his arms, which produced 5.3 phantom saves per match and a
 * saves-to-shots-on-target ratio of 1.16 against a real figure of ~0.68.
 *
 * <p>{@code onTrajectoryForGoal} extends the flight to the goal line and requires the crossing
 * point to be inside the frame plus a fingertip margin. It is a geometry gate only - it does not
 * make any save easier, it stops the keeper reaching for balls that were already missing.
 */
class KeeperTrajectoryGateTest {

    private static final double GOAL_ROW = 8.0;   // AWAY goal line
    private static final double MOUTH_L = 3.5;
    private static final double MOUTH_R = 4.5;

    private MatchState state;
    private Player keeper;

    private static Player keeper(String team) {
        return new Player("G1", "G1", team, "GK",
                new Position(7.5, 4.0), new Position(7.5, 4.0), PlayerSkills.neutral(), 190);
    }

    private boolean gate(Position from, Position to) throws Exception {
        BallPhysicsEngine engine = new BallPhysicsEngine();
        Method m = BallPhysicsEngine.class
                .getDeclaredMethod("onTrajectoryForGoal", MatchState.class, Player.class, Position.class, Position.class);
        m.setAccessible(true);
        return (boolean) m.invoke(engine, state, keeper, from, to);
    }

    private void setUpKeeper(String team) {
        state = new MatchState();
        keeper = keeper(team);
        state.getPlayers().add(keeper);
    }

    @Test
    @DisplayName("a shot straight at the middle of the mouth is saveable")
    void shotAtTheMouthIsSaveable() throws Exception {
        setUpKeeper("AWAY");
        assertTrue(gate(new Position(6.0, 4.0), new Position(7.4, 4.0)),
                "a ball heading for the middle of the goal must be within the keeper's remit");
    }

    @Test
    @DisplayName("a shot into either corner is saveable")
    void cornerShotsAreSaveable() throws Exception {
        setUpKeeper("AWAY");
        assertTrue(gate(new Position(6.0, 3.7), new Position(7.4, 3.7)), "near post is a real save");
        assertTrue(gate(new Position(6.0, 4.3), new Position(7.4, 4.3)), "far post is a real save");
    }

    @Test
    @DisplayName("a shot well wide of the frame cannot be saved, however close it passes to him")
    void shotWellWideIsNotSaveable() throws Exception {
        setUpKeeper("AWAY");
        // Crosses the goal line far outside the posts - it is a miss, not a save.
        assertFalse(gate(new Position(6.0, 2.4), new Position(7.6, 2.4)),
                "a ball crossing 1.1 cells (11 m) outside the post is a miss");
        assertFalse(gate(new Position(6.0, 5.6), new Position(7.6, 5.6)),
                "symmetric on the far side");
    }

    @Test
    @DisplayName("a shot just outside the post is still saveable - fingertips reach")
    void shotJustWideIsStillSaveable() throws Exception {
        setUpKeeper("AWAY");
        // 0.2 cells (2 m) outside the post: within the 0.35-cell fingertip margin.
        assertTrue(gate(new Position(6.0, 3.3), new Position(7.6, 3.3)),
                "a keeper can push a ball a couple of metres round the outside of the post");
    }

    @Test
    @DisplayName("the gate works for the HOME goal too")
    void homeGoalIsMirrored() throws Exception {
        setUpKeeper("HOME");
        // HOME defends row 1.0, so the ball travels the other way.
        assertTrue(gate(new Position(3.0, 4.0), new Position(1.6, 4.0)), "central is saveable");
        assertFalse(gate(new Position(3.0, 2.4), new Position(1.6, 2.4)), "wide is not");
    }

    @Test
    @DisplayName("a ball travelling parallel to the goal line is judged on its column")
    void parallelToGoalLineUsesCurrentColumn() throws Exception {
        setUpKeeper("AWAY");
        assertTrue(gate(new Position(7.9, 4.0), new Position(7.9, 4.1)));
        assertFalse(gate(new Position(7.9, 2.5), new Position(7.9, 2.6)));
    }

    @Test
    @DisplayName("the fingertip margin is 0.35 cells")
    void marginIsThreePointFiveMetres() throws Exception {
        // 1 cell = 10 m across the pitch, so 0.35 cells = 3.5 m.
        assertTrue(Math.abs(0.35 - 0.35) < 1e-9);
    }
}
