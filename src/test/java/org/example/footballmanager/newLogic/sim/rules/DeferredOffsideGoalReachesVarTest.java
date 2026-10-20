package org.example.footballmanager.newLogic.sim.rules;

import org.example.footballmanager.newLogic.sim.engine.ActionLogService;
import org.example.footballmanager.newLogic.sim.engine.BallResultHandler;
import org.example.footballmanager.newLogic.sim.model.ActionType;
import org.example.footballmanager.newLogic.sim.model.BallStepResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * A goal from a marginally offside position goes to VAR, and VAR decides before the goal counts.
 *
 * <p><b>What was dead (found 2026-10-19).</b> The offside rules have three bands. Outside the tolerance the
 * whistle fires at the reception and the flag is set; inside it, the check is <em>held</em> and the class
 * javadoc says what happens next: <i>"VAR confirms ONSIDE only if the next action was a GOAL"</i>.
 *
 * <p><b>Nothing ever said a goal had been scored.</b> {@code setOffsideLedToGoal(true)} was never called
 * from anywhere in the codebase — the three call sites are the setter's own declaration, a false, and the
 * demo module. So the held check was cancelled a tick after it was created, the whole deferred branch of
 * {@code resolvePendingVAROffside} was unreachable, the margin-based overturn model in
 * {@code VARService.checkOffside} was never evaluated, and a goal from a marginally offside position stood
 * unquestioned.
 *
 * <p><b>And the ordering matters as much as the wiring.</b> The check has to be resolved <b>before</b> the
 * goal is counted, or the scorer is both credited and flagged. The goal path already asks VAR whether the
 * goal stands before it counts it; the offside question belongs in the same place.
 *
 * <p><b>Why VAR is mocked.</b> The real {@code VARService} draws a coin — 25 % of the time it reviews at
 * all, and then an overturn chance of 5–40 %. A test that asserted the goal's fate against a coin would
 * pass most of the time and mean nothing, which is the failure this repository has paid for three times.
 */
class DeferredOffsideGoalReachesVarTest {

    private MatchState state;
    private BallResultHandler handler;
    private VARService varService;
    private MatchRecorder recorder;
    private Player scorer;

    private static Player player(String id, String team, String role, double row, double col) {
        return new Player(id, id, team, role,
                new Position(row, col), new Position(row, col), PlayerSkills.neutral(), 180);
    }

    @BeforeEach
    void setUp() {
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new ArrayList<>()));
        recorder = new MatchRecorder();

        varService = Mockito.mock(VARService.class);
        // The generic VAR goal review lets the goal stand; the offside question is the one under test.
        Mockito.when(varService.checkGoal(anyString(), any())).thenReturn(true);

        RestartManager restartManager = new RestartManager();
        OffsideService offsideService = new OffsideService(state, varService, recorder, restartManager);
        handler = new BallResultHandler(state, recorder,
                new ProposalStatsCollector("Home", "Away"), restartManager, varService, offsideService);

        scorer = player("S1", "HOME", "STL", 6.0, 3.5);
        state.getPlayers().add(scorer);
        state.getPlayers().add(player("D1", "AWAY", "DCL", 5.0, 3.0));
    }

    /** The player was left in the marginal band: no whistle, the check held. */
    private void aHeldOffsideCheck() {
        state.setPendingVARReview("ONSIDE_CHECK", scorer, "AWAY");
        state.setOffsideDeferred(true);
        state.setOffsideDeferredMargin(0.20);
    }

    /** And he scored. */
    private void heScores() {
        state.setLastTouchPlayer(scorer);
        state.setLastShooter(scorer);
        state.setLastActionType(ActionType.SHOT);
        handler.handle(BallStepResult.goal("HOME"));
    }

    @Test
    @DisplayName("VAR sees the goal and can turn it away")
    void varIsAskedAboutTheGoal() {
        aHeldOffsideCheck();
        Mockito.when(varService.checkOffside(eq(scorer), any(), eq(state))).thenReturn(true);
        Mockito.when(varService.getLastVARDecision()).thenReturn("OFFSIDE_CONFIRMED");

        heScores();

        Mockito.verify(varService).checkOffside(eq(scorer), any(), eq(state));
        assertTrue(state.isOffsideLedToGoal(),
                "the goal was not recorded as the thing the held check was waiting for");
    }

    @Test
    @DisplayName("a goal VAR confirms as offside does not count")
    void aConfirmedOffsideGoalDoesNotCount() {
        aHeldOffsideCheck();
        Mockito.when(varService.checkOffside(eq(scorer), any(), eq(state))).thenReturn(true);
        Mockito.when(varService.getLastVARDecision()).thenReturn("OFFSIDE_CONFIRMED");

        heScores();

        assertEquals(0, state.getHomeGoals(),
                "a goal VAR confirmed as offside was counted. The check has to be resolved BEFORE the goal "
                        + "is counted, or the scorer is both credited and flagged.");
        assertTrue(recorder.getEvents().stream().anyMatch(e -> "OFFSIDE".equals(e.getType())),
                "the offside was not recorded, so nobody would know why there is no goal");
        assertFalse(state.isOffsideDeferred(),
                "the held check is still open after the goal was settled");
    }

    @Test
    @DisplayName("a goal VAR overturns stands")
    void anOverturnedGoalStands() {
        aHeldOffsideCheck();
        Mockito.when(varService.checkOffside(eq(scorer), any(), eq(state))).thenReturn(false);
        Mockito.when(varService.getLastVARDecision()).thenReturn("OFFSIDE_OVERTURNED");

        heScores();

        assertEquals(1, state.getHomeGoals(),
                "a goal VAR overturned did not count. The review exists to correct a marginal call, not to "
                        + "remove every goal that came from a tight offside position.");
        assertFalse(state.isOffsideDeferred(),
                "the held check is still open after the goal was settled");
    }

    @Test
    @DisplayName("a goal by somebody else is not the held check's business")
    void aGoalBySomebodyElseIsNotReviewed() {
        aHeldOffsideCheck();
        Player other = player("S2", "HOME", "STR", 5.0, 4.0);
        state.getPlayers().add(other);
        state.setLastTouchPlayer(other);
        state.setLastShooter(other);
        state.setLastActionType(ActionType.SHOT);

        handler.handle(BallStepResult.goal("HOME"));

        assertEquals(1, state.getHomeGoals(),
                "the goal did not count, and the only reason could be that the held check was applied to a "
                        + "goal by a different player");
        Mockito.verify(varService, Mockito.never()).checkOffside(any(), any(), any());
    }
}
