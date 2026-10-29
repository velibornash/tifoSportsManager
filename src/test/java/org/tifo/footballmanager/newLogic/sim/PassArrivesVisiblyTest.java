package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.BallPhysicsEngine;
import org.tifo.footballmanager.newLogic.sim.engine.MatchOrchestrator;
import org.tifo.footballmanager.newLogic.sim.model.Ball;
import org.tifo.footballmanager.newLogic.sim.model.BallStepResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.recording.MatchSnapshot;
import org.tifo.footballmanager.newLogic.sim.recording.SimReplayView;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pass visibly ARRIVES: the replay keeps the tick the ball is taken on, and the taken ball
 * ends on the receiver.
 *
 * <p><b>What was broken (replay 5633, 2026-10-26, F1+F2).</b> Owner watched the first forty
 * seconds of a match and the passes did not read as passes. Two faults, one visual:</p>
 *
 * <ol>
 *   <li><b>F1 — the arrival tick was dropped.</b> {@code SimReplayView.downsample} kept a
 *       snapshot only when the ball was <em>in flight</em> (carrier null) or on a stride tick
 *       (1 in 10). At reception the carrier is no longer null, so the one tick that says "the
 *       ball is now his" was dropped unless it happened to land on a stride tick. The viewer
 *       therefore showed the ball still travelling after the log said the pass was complete,
 *       and then jumping to wherever the receiver had walked to.</li>
 *   <li><b>F2 — the received ball stopped short.</b> The ball stopped where its own last step
 *       left it — up to the boosted receive radius (0.5 cells = 7 m) from the man receiving it —
 *       and only the next decision snapped it to his feet. So the arrival frame had the ball
 *       sitting several metres from the receiver, and the pass read as arriving at nobody.</li>
 * </ol>
 *
 * <p><b>The owner's rule, and the reconciliation with F3.</b> The received pass must be
 * unambiguously visible — player and ball on the same coordinate. The receiver keeps his boost
 * (he may take the ball at a reach, his answer on F3: "izabrani receiver treba da ima boost da
 * pokupi loptu"), which is why the radius is not zero — but it is an honest reach now (0.25
 * cells = 3.5 m, half of what it was) and the taken ball is his.
 */
class PassArrivesVisiblyTest {

    @Test
    @DisplayName("F1: the replay keeps every tick where the ball changes hands")
    void theReplayKeepsEveryPossessionChange() {
        // Seeded, because the count below is a floor: an unseeded run varies and the floor
        // would be measuring the dice, not the filter.
        SimulationRandom.seed(20261026L);
        MatchOrchestrator o = SimMatchRunner.run("Home FC", "Away United", 900);
        List<MatchSnapshot> raw = o.getRecorder().getSnapshots();

        // Every tick where the carrier becomes someone he was not before — a pass received,
        // a clearance taken, an interception. These are the ticks the old filter threw away.
        Set<Long> possessionChanges = new HashSet<>();
        String previous = null;
        for (MatchSnapshot s : raw) {
            String carrier = s.getBallCarrierId();
            if (carrier != null && !carrier.equals(previous)) possessionChanges.add(s.getTick());
            previous = carrier;
        }
        assertTrue(possessionChanges.size() > 10,
                "a 900-tick match must change hands more than a handful of times, it changed "
                        + possessionChanges.size() + " times — the check would prove nothing");

        Map<String, Object> view = SimReplayView.build(o, "Home FC", "Away United");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kept = (List<Map<String, Object>>) view.get("snapshots");
        Set<Long> keptTicks = new HashSet<>();
        for (Map<String, Object> s : kept) keptTicks.add(((Number) s.get("tick")).longValue());

        Set<Long> dropped = new HashSet<>(possessionChanges);
        dropped.removeAll(keptTicks);
        assertTrue(dropped.isEmpty(),
                "the replay dropped " + dropped.size() + " tick(s) where the ball changed hands: "
                        + dropped + " — the pass has no arrival frame, so the viewer shows the "
                        + "ball still travelling and then jumping to the receiver");
    }

    @Test
    @DisplayName("F2: the received ball ends ON the receiver, not several metres short")
    void theReceivedBallEndsOnTheReceiver() {
        MatchState state = stateWithTeams();
        Player receiver = player(state, "HOME", "MR");
        receiver.setPosition(new Position(4.0, 5.0));
        state.setPendingReceiver(receiver);
        state.setLastTouchTeam("HOME");

        Ball ball = state.getBall();
        BallPhysicsEngine engine = (BallPhysicsEngine) state.getBallEngine();
        engine.launch(ball, new Position(4.0, 2.0), receiver.getPosition(), 0.3, false, 0.0);

        boolean received = false;
        for (int i = 0; i < 20 && !received; i++) {
            BallStepResult step = engine.stepBall(state);
            if (step.getType() == BallStepResult.Type.RECEIVE) received = true;
        }

        assertTrue(received, "the pass was aimed straight at the receiver and was never taken");
        assertEquals(receiver, state.getCarrier(), "the receiver must be the carrier after the receive");
        assertEquals(receiver.getPosition().getRow(), ball.getPosition().getRow(), 1e-9,
                "the received ball stopped away from the receiver — it must END ON HIM, "
                        + "the owner's rule is that a received pass is unambiguously visible");
        assertEquals(receiver.getPosition().getColumn(), ball.getPosition().getColumn(), 1e-9,
                "the received ball stopped away from the receiver — it must END ON HIM, "
                        + "the owner's rule is that a received pass is unambiguously visible");
    }

    @Test
    @DisplayName("F2: the receiver's boost is an honest reach, not a seven-metre catch")
    void theBoostIsAnHonestReach() {
        // The receiver stands 0.3 cells (4.2 m) off the ball's flight line. At the old boosted
        // radius of 0.5 cells he caught it from there — a pass taken seven metres from his body.
        // At 0.25 the ball sails past him and stays alive, exactly as it would for anyone who
        // is not within reach of the ball.
        MatchState state = stateWithTeams();
        Player receiver = player(state, "HOME", "MR");
        receiver.setPosition(new Position(4.3, 5.0));
        state.setPendingReceiver(receiver);
        state.setLastTouchTeam("HOME");

        Ball ball = state.getBall();
        BallPhysicsEngine engine = (BallPhysicsEngine) state.getBallEngine();
        engine.launch(ball, new Position(4.0, 2.0), new Position(4.0, 5.0), 0.3, false, 0.0);

        // 3.0 cells at 0.3 per tick: the ball is beside the receiver from tick 10 on. Stepping
        // 15 takes it clean past him, so the assertion is about the whole flight, not a moment
        // before the ball got there.
        for (int i = 0; i < 15; i++) engine.stepBall(state);

        assertNotEquals(receiver, state.getCarrier(),
                "the receiver took the ball from 0.3 cells (4.2 m) off the pass's line — that is "
                        + "the seven-metre catch the owner saw, the boost must be an honest reach "
                        + "(" + BallPhysicsEngine.RECEIVE_R_BOOSTED + " cells)");
    }

    @Test
    @DisplayName("control: within the reach, the same pass IS taken")
    void withinTheReachTheSamePassIsTaken() {
        MatchState state = stateWithTeams();
        Player receiver = player(state, "HOME", "MR");
        receiver.setPosition(new Position(4.2, 5.0));   // 0.2 cells off the line — inside 0.25
        state.setPendingReceiver(receiver);
        state.setLastTouchTeam("HOME");

        Ball ball = state.getBall();
        BallPhysicsEngine engine = (BallPhysicsEngine) state.getBallEngine();
        engine.launch(ball, new Position(4.0, 2.0), new Position(4.0, 5.0), 0.3, false, 0.0);

        for (int i = 0; i < 15 && state.getCarrier() == null; i++) engine.stepBall(state);

        assertEquals(receiver, state.getCarrier(),
                "a receiver inside the reach did not take the pass — so the test above could be "
                        + "passing because passes are never taken at all, and would prove nothing");
        assertNull(state.getPendingReceiver(), "the pending receiver must be cleared on a receive");
    }

    private MatchState stateWithTeams() {
        SimulationRandom.seed(20261026L);
        MatchState state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        state.setBallEngine(new BallPhysicsEngine());
        return state;
    }

    private Player player(MatchState state, String team, String role) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }
}
