package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.example.footballmanager.newLogic.sim.model.ActionType;
import org.example.footballmanager.newLogic.sim.model.DecisionOption;
import org.example.footballmanager.newLogic.sim.model.DecisionResult;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.tactics.SideTactics;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross to a runner who is offside is an offence, and the whistle has to be able to fire.
 *
 * <p><b>What was dead (found 2026-10-19, E-2).</b> The offside rules flag a receiver, and the ball-result
 * handler whistles when the flagged player touches the ball. <b>But the flag was only ever set for an
 * ordinary pass.</b> The orchestrator's rule read
 *
 * <pre>
 *   if (decision.getType() == ActionType.PASS &amp;&amp; decision.getTarget() != null) {
 * </pre>
 *
 * and {@code OffsideService.checkOffside} — the one and only place that sets
 * {@code offsideFlaggedReceiver} — was reached from nowhere else. A cross and a centre are lofted
 * deliveries into the penalty box, which is exactly where a runner is most likely to be beyond the
 * second-to-last defender, and they had <b>no offside test of any kind</b>: the target was picked on
 * threat alone, the flag was never set, and the reception was normal. He could shoot and score from it.
 *
 * <p><b>What this test drives.</b> The orchestrator, with a decision engine that always returns the
 * delivery under examination. Without that seam, proving the rules are asked about a cross would mean
 * building a match situation that happens to make the engine choose a cross — and the test would then be
 * measuring the decision engine rather than the offside rules.
 *
 * <p><b>The assertion is on the flag</b>, because that is the thing the whole chain hangs on: the flag is
 * set at the moment the ball is played, and the whistle fires at the reception, when the flagged player
 * actually touches it.
 */
class CrossToOffsideRunnerIsFlaggedTest {

    /** A decision engine that always returns the same delivery. */
    private static class ForcedDelivery extends CleanDecisionEngine {
        private final DecisionOption option;

        ForcedDelivery(DecisionOption option) {
            this.option = option;
        }

        @Override
        public DecisionResult decideWithOptions(MatchState state) {
            return new DecisionResult(option, List.of(option));
        }
    }

    private MatchState state;
    private Player crosser;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20261019L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");

        crosser = homePlayer("ML");
        // Wide, in the final third, with the ball at his feet.
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
     * AWAY's back four sit on rows 6.5 and 7.0, so the offside line — the second-to-last defender — is
     * row 7.0. A HOME runner on row 7.6 is 0.6 cells (8.4 m) beyond it: clearly offside, and well past
     * the 0.5-cell (7 m) limit the decision engine treats as "never a target".
     */
    private Player runnerOn(double row) {
        Player runner = homePlayer("STL");
        runner.setPosition(new Position(row, 3.0));
        return runner;
    }

    private MatchOrchestrator orchestratorReturning(DecisionOption decision) {
        return new MatchOrchestrator(state, new SideTactics(new TacticsRules()),
                new ForcedDelivery(decision));
    }

    @Test
    @DisplayName("a cross to a runner who is offside raises the flag")
    void aCrossToAnOffsideRunnerIsFlagged() {
        Player target = runnerOn(7.6);
        MatchOrchestrator orch = orchestratorReturning(
                new DecisionOption(ActionType.CROSS, target, 10.0, "forced cross"));

        orch.tick();

        assertSame(target, state.getOffsideFlaggedReceiver(),
                "a cross to a runner who was beyond the second-to-last defender was not flagged, so the "
                        + "whistle could never fire and the reception was a normal one — he could shoot "
                        + "and score from it");
        assertSame(target, state.getPendingReceiver(),
                "precondition: the cross was actually delivered. Without this the test could pass "
                        + "because the tick died before the decision, and would be proving nothing");
    }

    @Test
    @DisplayName("a cross to a runner who is onside raises nothing")
    void aCrossToAnOnsideRunnerIsNotFlagged() {
        // Row 6.8 is 0.2 cells BEHIND the offside line — onside.
        Player target = runnerOn(6.8);
        MatchOrchestrator orch = orchestratorReturning(
                new DecisionOption(ActionType.CROSS, target, 10.0, "forced cross"));

        orch.tick();

        assertNull(state.getOffsideFlaggedReceiver(),
                "a runner who was behind the offside line was flagged. The flag means the whistle fires "
                        + "at his reception, and a whistle for an onside runner is not a near miss");
        assertSame(target, state.getPendingReceiver(),
                "precondition: the cross was actually delivered");
    }

    @Test
    @DisplayName("an ordinary pass is still checked the same way")
    void anOrdinaryPassIsStillChecked() {
        // The control. The cross is the addition, not a replacement: a pass to the same offside runner
        // has been flagged since before this change, and it must stay that way.
        Player target = runnerOn(7.6);
        MatchOrchestrator orch = orchestratorReturning(
                new DecisionOption(ActionType.PASS, target, 10.0, "forced pass"));

        orch.tick();

        assertSame(target, state.getOffsideFlaggedReceiver(),
                "an ordinary pass to an offside runner is no longer flagged, so the test above could "
                        + "pass for the wrong reason");
    }

    @Test
    @DisplayName("a shot is not a delivery and is never flagged")
    void aShotIsNotADelivery() {
        Player target = runnerOn(7.6);
        MatchOrchestrator orch = orchestratorReturning(
                new DecisionOption(ActionType.SHOT, target, 10.0, "forced shot"));

        orch.tick();

        assertNull(state.getOffsideFlaggedReceiver(),
                "a shot is not a delivery to a team-mate, so it cannot be offside");
    }

    @Test
    @DisplayName("the runner under test really is beyond the offside line")
    void theRunnerIsBeyondTheOffsideLine() {
        Player target = runnerOn(7.6);

        // The offside line is the second-to-last AWAY defender. Compute it the way the rules do
        // rather than trusting the formation, so this precondition cannot quietly become meaningless
        // if the factory's shape ever changes.
        List<Double> rows = state.getPlayers().stream()
                .filter(p -> "AWAY".equals(p.getTeam()))
                .map(p -> p.getPosition().getRow())
                .sorted(Comparator.reverseOrder())
                .toList();
        double line = rows.get(1);

        assertTrue(target.getPosition().getRow() > line,
                "the runner must be beyond the offside line (" + line + ") for this test to mean "
                        + "anything; he is on " + target.getPosition().getRow());
    }
}
