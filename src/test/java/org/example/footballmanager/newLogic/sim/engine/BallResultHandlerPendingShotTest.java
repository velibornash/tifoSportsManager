package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.BallStepResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.0 — the pending-shot flag must not leak.
 *
 * <p>{@code BallResultHandler} treats {@code lastShooter != null} as "a shot is in flight awaiting
 * its outcome". Only the explicit shot outcomes used to clear it. RECEIVE, INTERCEPT, DEFLECT,
 * LOOSE_PICKUP and STOPPED all resolved the ball while leaving the flag set, so a resolved shot
 * stayed pending forever and the <em>next</em> goalkeeper touch — of any ball — was recorded as a
 * save against that long-dead shot.
 *
 * <p>Measured symptom over 50 matches: 14.1 saves against 12.4 shots on target. A save cannot
 * exceed the shots it saved, so the statistic was impossible. After the fix: 7.4 saves, and shot
 * conversion fell from 53.7% to 32.0% against a real-football figure of ~32%.
 */
class BallResultHandlerPendingShotTest {

    private static final double MID_ROW = 4.0;
    private static final double MID_COL = 4.0;

    private MatchState state;
    private BallResultHandler handler;
    private Player shooter;
    private Player keeper;

    private static Player player(String id, String team, String role, double row, double col) {
        Player p = new Player(id, id, team, role,
                new Position(row, col), new Position(row, col), PlayerSkills.neutral(), 180);
        return p;
    }

    private void givenAPendingShot() {
        state = new MatchState();
        state.setActionLogger(new org.example.footballmanager.newLogic.sim.engine.ActionLogService(state, new java.util.ArrayList<>()));
        handler = new BallResultHandler(state,
                new org.example.footballmanager.newLogic.sim.recording.MatchRecorder(),
                new org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector("Home", "Away"),
                null);
        shooter = player("S1", "HOME", "STL", 6.0, 3.5);
        keeper = player("G1", "AWAY", "GK", 7.5, 4.0);
        state.getPlayers().add(shooter);
        state.getPlayers().add(keeper);
        state.setLastShooter(shooter);
    }

    @Test
    @DisplayName("a RECEIVE resolves the ball and must clear the pending-shot flag")
    void receiveClearsPendingShot() {
        givenAPendingShot();
        assertNotNull(state.getLastShooter(), "precondition: a shot is pending");

        state.setCarrier(player("R1", "AWAY", "DCL", 5.0, 3.5));
        handler.handle(BallStepResult.receive("R1"));

        assertNull(state.getLastShooter(),
                "a gathered/intercepted ball must consume the pending shot, or the next GK touch "
                        + "is miscounted as a save");
    }

    @Test
    @DisplayName("an INTERCEPT clears the pending-shot flag")
    void interceptClearsPendingShot() {
        givenAPendingShot();

        state.setCarrier(player("I1", "AWAY", "DCR", 5.5, 4.5));
        handler.handle(BallStepResult.intercept("I1"));

        assertNull(state.getLastShooter());
    }

    @Test
    @DisplayName("a DEFLECT clears the pending-shot flag")
    void deflectClearsPendingShot() {
        givenAPendingShot();

        handler.handle(BallStepResult.deflect("D1"));

        assertNull(state.getLastShooter());
    }

    @Test
    @DisplayName("a LOOSE_PICKUP clears the pending-shot flag")
    void loosePickupClearsPendingShot() {
        givenAPendingShot();

        state.setCarrier(player("L1", "AWAY", "DCL", 5.0, 3.0));
        handler.handle(BallStepResult.loosePickup("L1"));

        assertNull(state.getLastShooter());
    }

    @Test
    @DisplayName("a genuine SAVE still resolves the shot and attributes it to the shooter")
    void saveStillResolvesTheShot() {
        givenAPendingShot();

        state.setCarrier(keeper);
        handler.handle(BallStepResult.save("G1"));

        assertNull(state.getLastShooter(), "a save consumes the pending shot");
    }

    @Test
    @DisplayName("an in-flight FLIGHT step must NOT clear the flag - the shot is still pending")
    void flightKeepsPendingShot() {
        givenAPendingShot();

        handler.handle(BallStepResult.flight());

        assertSame(shooter, state.getLastShooter(),
                "FLIGHT is not an outcome; clearing here would make every shot a miss");
    }

    @Test
    @DisplayName("the flag survives a save so a rebound off the keeper is not a second shot")
    void saveConsumesFlagExactlyOnce() {
        givenAPendingShot();

        state.setCarrier(keeper);
        handler.handle(BallStepResult.save("G1"));
        assertNull(state.getLastShooter());

        // A later, unrelated ball gathered by the keeper must NOT be reported as a shot save.
        Player other = player("X1", "AWAY", "DCL", 5.0, 4.0);
        state.setCarrier(other);
        handler.handle(BallStepResult.receive("X1"));
        assertNull(state.getLastShooter());
    }

    @Test
    @DisplayName("classification: only genuine shot outcomes preserve the flag")
    void shotOutcomeClassification() throws Exception {
        java.lang.reflect.Method m = BallResultHandler.class
                .getDeclaredMethod("clearsPendingShot", BallStepResult.Type.class);
        m.setAccessible(true);

        // Terminal: the ball's journey ends, so a pending shot must be consumed.
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.RECEIVE));
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.INTERCEPT));
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.DEFLECT));
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.LOOSE_PICKUP));
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.STOPPED));
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.OOB_RESTART));
        assertTrue((Boolean) m.invoke(null, BallStepResult.Type.OOB_CANCEL));

        // Not terminal: the ball is still in play, so the shooter must be retained.
        assertFalse((Boolean) m.invoke(null, BallStepResult.Type.FLIGHT));
        assertFalse((Boolean) m.invoke(null, BallStepResult.Type.OOB_HOLD));

        // Genuine shot outcomes clear the flag inside their own case blocks.
        assertFalse((Boolean) m.invoke(null, BallStepResult.Type.SAVE));
        assertFalse((Boolean) m.invoke(null, BallStepResult.Type.BLOCK));
        assertFalse((Boolean) m.invoke(null, BallStepResult.Type.GOAL));
        assertFalse((Boolean) m.invoke(null, BallStepResult.Type.POST_HIT));
    }
}
