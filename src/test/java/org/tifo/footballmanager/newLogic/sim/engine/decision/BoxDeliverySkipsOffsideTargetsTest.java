package org.tifo.footballmanager.newLogic.sim.engine.decision;

import org.tifo.footballmanager.newLogic.sim.model.ActionType;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine stops aiming crosses at runners who are clearly offside.
 *
 * <p><b>What was missing (found 2026-10-19, E-2 / F-DE-7).</b> An ordinary pass is filtered: a team-mate
 * more than 0.5 cells (7 m) beyond the offside line is <b>never a target</b>, and inside that band the
 * pass is only played if the carrier's playmaking skill says he takes the risk. A cross had
 * <b>none of that</b> — {@code scoreBoxDelivery} picked the best attacker in the box on threat alone, so
 * the engine aimed at whoever was best positioned, including a runner a metre beyond the second-to-last
 * defender. The rules layer did not catch it either, because it only ever asked about
 * {@code ActionType.PASS}.
 *
 * <p>So the cross was a way to be offside on purpose. The two halves of the fix are separate and both are
 * needed: the engine stops <em>planning</em> the offence, and the rules stop <em>letting it stand</em> if
 * it happens anyway.
 *
 * <p><b>Why the assertions are on the target, not on the score.</b> The cross is also gated by a
 * perception roll — a crosser who does not see the man does not cross at all — so the score of the option
 * is not a stable thing to assert on. The <b>target</b> is: a clearly offside runner is not a delivery
 * target, whichever way the roll went.
 */
class BoxDeliverySkipsOffsideTargetsTest {

    private MatchState state;
    private CleanDecisionEngine engine;
    private Player crosser;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20261019L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        engine = new CleanDecisionEngine();

        crosser = homePlayer("ML");
        // Wide, in the final third — a crossing position.
        crosser.setPosition(new Position(6.0, 1.0));
        state.setCarrier(crosser);
        state.getBall().setPosition(crosser.getPosition());
    }

    private Player homePlayer(String role) {
        return state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }

    /**
     * A HOME runner in the penalty box.
     *
     * <p>AWAY's back four sit on rows 6.5 and 7.0, so the offside line — the second-to-last defender — is
     * row 7.0. Row 7.6 is 0.6 cells beyond it: clearly offside. Row 6.8 is 0.2 cells behind it: onside.
     */
    private Player runnerInTheBox(double row) {
        Player runner = homePlayer("STL");
        runner.setPosition(new Position(row, 3.0));
        return runner;
    }

    private DecisionOption crossOption() {
        DecisionResult result = engine.decideWithOptions(state);
        return result.getOptions().stream()
                .filter(o -> o.getType() == ActionType.CROSS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the engine produced no CROSS option at all"));
    }

    @Test
    @DisplayName("a cross is aimed at the runner who is onside, not the one who is offside")
    void aCrossIsAimedAtTheOnsideRunner() {
        Player offside = runnerInTheBox(7.6);
        Player onside = homePlayer("STR");
        onside.setPosition(new Position(6.8, 4.2));

        DecisionOption cross = crossOption();

        assertSame(onside, cross.getTarget(),
                "the cross was aimed at " + (cross.getTarget() == null ? "nobody" : cross.getTarget().getLabel())
                        + " instead of the runner who is onside. The offside runner at "
                        + offside.getLabel() + " must not be a target");
    }

    @Test
    @DisplayName("a box with nobody onside in it has no cross to make")
    void aBoxWithNobodyOnsideHasNoCross() {
        runnerInTheBox(7.6);
        homePlayer("STR").setPosition(new Position(7.9, 4.2));

        DecisionOption cross = crossOption();

        assertNull(cross.getTarget(),
                "every runner in the box was offside and the engine still found a target");
        assertTrue(cross.getReason().contains("offside"),
                "the cross was not unavailable because of the offside — it says: " + cross.getReason());
    }

    @Test
    @DisplayName("the control: a cross into a box with a legal target is still available")
    void aCrossIntoALegalBoxIsStillAvailable() {
        homePlayer("STL").setPosition(new Position(6.6, 3.0));
        homePlayer("STR").setPosition(new Position(6.9, 4.2));

        DecisionOption cross = crossOption();

        assertNotNull(cross.getTarget(),
                "the cross has no target even though two runners are onside — so the test above could be "
                        + "passing because crosses never happen, and would be proving nothing");
    }
}
