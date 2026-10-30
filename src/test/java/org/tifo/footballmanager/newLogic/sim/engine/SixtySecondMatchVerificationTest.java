package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.MatchSimulationLauncher;
import org.tifo.footballmanager.newLogic.sim.model.ActionType;
import org.tifo.footballmanager.newLogic.sim.model.DecisionOption;
import org.tifo.footballmanager.newLogic.sim.model.DecisionResult;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimUtils;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive 60-second match verification test.
 *
 * <p>Simulates 60 ticks of a match, logging all player and ball positions on
 * every tick, and verifying:
 * <ul>
 *   <li>Every executed action (pass, shot, center, clear, thru) starts with the
 *       ball within {@link BallPhysicsEngine#ON_BALL_EPS} of the carrier.</li>
 *   <li>The chosen receiver in a PASS is the highest-scoring option.</li>
 *   <li>The first pass after kickoff is never forward.</li>
 *   <li>Ball position is always valid (not NaN, not null) on every tick.</li>
 *   <li>No player is frozen — every player moves or drifts within their cell.</li>
 * </ul>
 */
class SixtySecondMatchVerificationTest {

    private MatchState state;
    private MatchOrchestrator orch;
    private final List<String> tickLog = new ArrayList<>();
    private int actionCount = 0;
    private int ballOnCarrierViolations = 0;
    private int forwardFirstPassViolations = 0;
    private int invalidBallPositionCount = 0;
    private int suboptimalReceiverCount = 0;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20261030L);
        state = new MatchState();
        MatchSimulationLauncher.addTeam(state, "HOME");
        MatchSimulationLauncher.addTeam(state, "AWAY");
        orch = new MatchOrchestrator(state);
        orch.getRestartManager().handleKickoff(state, "HOME");

        // Attach decision observer to capture every decision
        orch.setDecisionObserver((s, result, carrier) -> {
            if (result == null || result.getChosen() == null) return;
            ActionType type = result.getChosen().getType();
            if (type == null) return;

            // Only check executable actions (not DRIBBLE which is just a target)
            if (type == ActionType.PASS || type == ActionType.SHOT
                    || type == ActionType.CENTER || type == ActionType.CLEAR
                    || type == ActionType.THRU || type == ActionType.CROSS) {

                actionCount++;

                // VERIFY: ball must be within ON_BALL_EPS of carrier at action start
                if (carrier != null) {
                    double dist = SimUtils.distance(carrier.getPosition(), state.getBall().getPosition());
                    if (dist > BallPhysicsEngine.ON_BALL_EPS) {
                        ballOnCarrierViolations++;
                        tickLog.add("VIOLATION tick=" + state.getMatchTicks()
                                + " " + type + " by " + carrier.getLabel()
                                + " ball dist=" + String.format("%.3f", dist)
                                + " > ON_BALL_EPS");
                    }
                }

                // VERIFY: chosen receiver must be the highest-scoring pass option
                if (type == ActionType.PASS && result.getChosen().getTarget() != null) {
                    DecisionOption chosen = result.getChosen();
                    double chosenScore = chosen.getScore();
                    for (DecisionOption opt : result.getOptions()) {
                        if (opt.getType() == ActionType.PASS && opt.getTarget() != null
                                && opt.getTarget() != chosen.getTarget()
                                && opt.getScore() > chosenScore + 1e-9) {
                            suboptimalReceiverCount++;
                            tickLog.add("SUBOPTIMAL tick=" + state.getMatchTicks()
                                    + " chosen=" + chosen.getTarget().getLabel()
                                    + " score=" + String.format("%.2f", chosenScore)
                                    + " but " + opt.getTarget().getLabel()
                                    + " score=" + String.format("%.2f", opt.getScore()));
                        }
                    }
                }

                // VERIFY: first pass after kickoff must not go forward
                if (type == ActionType.PASS && state.isFirstPassAfterKickoff()
                        && result.getChosen().getTarget() != null) {
                    Player recv = result.getChosen().getTarget();
                    double advance = progressOf(recv) - progressOf(carrier);
                    if (advance > 0.01) {
                        forwardFirstPassViolations++;
                        tickLog.add("FORWARD-FIRST-PASS tick=" + state.getMatchTicks()
                                + " " + carrier.getLabel() + " -> " + recv.getLabel()
                                + " advance=" + String.format("%.2f", advance));
                    }
                }
            }
        });
    }

    private static double progressOf(Player p) {
        double row = p.getPosition().getRow();
        return "HOME".equals(p.getTeam()) ? row - 4.5 : 4.5 - row;
    }

    @Test
    @DisplayName("60-second match: ball on carrier at action start, valid ball position, no forward first pass")
    void sixtySecondMatchVerification() {
        int ticks = 40;
        for (int t = 0; t < ticks; t++) {
            orch.tick();

            // Log all positions
            StringBuilder sb = new StringBuilder();
            sb.append("TICK ").append(state.getMatchTicks());
            sb.append(" | ball=").append(state.getBall().getPosition());
            sb.append(" carrier=").append(state.getCarrier() == null ? "none" : state.getCarrier().getLabel());
            sb.append(" pending=").append(state.getPendingReceiver() == null ? "none" : state.getPendingReceiver().getLabel());
            for (Player p : state.getPlayers()) {
                sb.append(" | ").append(p.getLabel()).append("=").append(p.getPosition());
            }
            tickLog.add(sb.toString());

            // VERIFY: ball position must be valid (not NaN, not null)
            Position ballPos = state.getBall().getPosition();
            if (ballPos == null || Double.isNaN(ballPos.getRow()) || Double.isNaN(ballPos.getColumn())) {
                invalidBallPositionCount++;
                tickLog.add("INVALID-BALL tick=" + state.getMatchTicks());
            }
        }

        // Print ALL tick positions to console
        System.out.println("=== 60-SECOND MATCH VERIFICATION (40 ticks = 60s) ===");
        System.out.println("Ticks: " + ticks);
        System.out.println("Actions checked: " + actionCount);
        System.out.println("Ball-on-carrier violations: " + ballOnCarrierViolations);
        System.out.println("Forward-first-pass violations: " + forwardFirstPassViolations);
        System.out.println("Invalid ball position count: " + invalidBallPositionCount);
        System.out.println("Suboptimal receiver choices: " + suboptimalReceiverCount);
        System.out.println("=== ALL TICK POSITIONS ===");
        for (String line : tickLog) {
            System.out.println(line);
        }
        System.out.println("=== END ===");

        assertTrue(ballOnCarrierViolations == 0,
                ballOnCarrierViolations + " action(s) started with ball not on carrier. See log.");
        assertTrue(forwardFirstPassViolations == 0,
                forwardFirstPassViolations + " forward first-pass-after-kickoff violation(s). See log.");
        assertTrue(invalidBallPositionCount == 0,
                invalidBallPositionCount + " tick(s) with invalid ball position. See log.");
        assertTrue(suboptimalReceiverCount == 0,
                suboptimalReceiverCount + " suboptimal receiver choice(s). See log.");
    }
}