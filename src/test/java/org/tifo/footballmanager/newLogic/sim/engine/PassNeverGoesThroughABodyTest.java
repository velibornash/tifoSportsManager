package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.Ball;
import org.tifo.footballmanager.newLogic.sim.model.BallStepResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pass never travels through a player standing on its line.
 *
 * <p><b>What was broken (owner's log, 2026-11-13).</b> {@code 0:04 PASS by Nenad Kačar(DCL) at
 * (2.5,3.5) -> David_Ionut Petri(DR)} — with Vladislav Cvijić standing at (2.5,4.5), dead on the line
 * between them. The ball went through him. In {@code BallPhysicsEngine.stepBall} the designated
 * receiver was checked <em>first</em>, and on a hit it returned immediately: no other body on the
 * tick's segment was ever examined, and the ball was moved onto the receiver. The pass was therefore
 * a teleport, and any player in between was invisible to it.
 *
 * <p><b>The rule.</b> The receiver keeps his boosted reach, but a body struck <em>earlier</em> in the
 * same tick is the physical contact: the ball bounces off it (a fast ball reflects, a slow one is
 * taken).
 *
 * <p><b>Both halves are asserted.</b> The first test would pass on a build that never completed a
 * pass at all, so the second one proves the ordinary pass still arrives — it is the control, and
 * without it the guard would be measuring nothing.
 */
class PassNeverGoesThroughABodyTest {

    @Test
    @DisplayName("a body standing on the line stops the pass — the ball bounces off it")
    void thePassBouncesOffABodyOnTheLine() {
        MatchState state = stateWithTeams();
        Player kicker = player(state, "HOME", "CML");
        Player blocker = player(state, "HOME", "DCR");
        Player receiver = player(state, "HOME", "STL");

        kicker.setPosition(new Position(4.0, 3.5));
        blocker.setPosition(new Position(3.0, 3.5));    // dead on the line, 1 cell from the kicker
        receiver.setPosition(new Position(2.0, 3.5));   // the target, 2 cells away

        state.setPendingReceiver(receiver);
        state.setLastTouchTeam("HOME");
        state.setLastTouchPlayer(kicker);

        Ball ball = state.getBall();
        BallPhysicsEngine engine = (BallPhysicsEngine) state.getBallEngine();
        engine.launch(ball, kicker.getPosition(), receiver.getPosition(),
                BallPhysicsEngine.LAUNCH_SPEED, false, 0.0);

        BallStepResult step = engine.stepBall(state);

        // A fast ball that strikes a body is not a possession event: the physics reports the tick as
        // FLIGHT (the ball keeps moving), so what is asserted here is the geometry, not the type.
        assertEquals(BallStepResult.Type.FLIGHT, step.getType(),
                "a bounced ball keeps moving — got " + step.getType());
        assertNull(state.getCarrier(),
                "the ball was handed to the receiver through the body standing on the line — a pass "
                        + "must be struck by the first body it reaches, not teleport past it");
        assertTrue(ball.getPosition().getRow() > 3.0,
                "the ball ended at row " + ball.getPosition().getRow() + ", past the body at row 3.0 "
                        + "— it went through him");
        assertTrue(ball.getVelY() > 0,
                "the ball did not rebound off the body: it is still travelling at velY="
                        + ball.getVelY() + " (it was launched at -1 against a body at row 3.0)");
    }

    @Test
    @DisplayName("control: with nobody on the line the same pass still reaches its receiver")
    void theSamePassStillArrivesWhenNobodyIsInTheWay() {
        MatchState state = stateWithTeams();
        Player kicker = player(state, "HOME", "CML");
        Player blocker = player(state, "HOME", "DCR");
        Player receiver = player(state, "HOME", "STL");

        kicker.setPosition(new Position(4.0, 3.5));
        blocker.setPosition(new Position(3.0, 5.5));    // two cells off the line
        receiver.setPosition(new Position(2.0, 3.5));

        state.setPendingReceiver(receiver);
        state.setLastTouchTeam("HOME");
        state.setLastTouchPlayer(kicker);

        Ball ball = state.getBall();
        BallPhysicsEngine engine = (BallPhysicsEngine) state.getBallEngine();
        engine.launch(ball, kicker.getPosition(), receiver.getPosition(),
                BallPhysicsEngine.LAUNCH_SPEED, false, 0.0);

        BallStepResult step = engine.stepBall(state);

        assertEquals(BallStepResult.Type.RECEIVE, step.getType(),
                "the pass had a clear line and was not taken — without this half the guard above "
                        + "would pass on an engine that never completes a pass at all");
        assertEquals(receiver, state.getCarrier(), "the receiver must be the carrier");
    }

    private MatchState stateWithTeams() {
        SimulationRandom.seed(20261113L);
        MatchState state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        state.setBallEngine(new BallPhysicsEngine());
        // Park everyone far from the line so only the three players this test places can touch the
        // ball. Without this the guard would be reading the default shape.
        for (Player p : state.getPlayers()) {
            p.setPosition(new Position("HOME".equals(p.getTeam()) ? 1.3 : 7.7,
                    p.getPosition().getColumn()));
        }
        return state;
    }

    private Player player(MatchState state, String team, String role) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }
}
