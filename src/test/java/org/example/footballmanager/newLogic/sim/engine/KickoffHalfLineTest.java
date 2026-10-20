package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.MatchSimulationLauncher;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner rule 2026-09-25: at kickoff, excluding the player taking the kickoff,
 * every other player must be at least 0.5 cells in his OWN half.
 *
 * The placement in RestartManager.handleKickoff did that, but only for the
 * instant of the kickoff: on the very next tick the ordinary attacking targets
 * took over and the away side walked across the half-way line, so the replay
 * showed the away attackers standing in the home half while the KICK OFF overlay
 * was still up. The hold now lasts until the kickoff pass is actually RECEIVED.
 */
class KickoffHalfLineTest {

    private static final double BUFFER = TacticalIntentEngine.KICKOFF_HALF_BUFFER;
    private static final double MID_LINE = 4.5;

    private MatchState state;
    private MatchOrchestrator orch;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(2026L);
        state = new MatchState();
        MatchSimulationLauncher.addTeam(state, "HOME");
        MatchSimulationLauncher.addTeam(state, "AWAY");
        orch = new MatchOrchestrator(state);
        orch.getRestartManager().handleKickoff(state, "HOME");
        kickerRef = state.getRestartTaker() != null ? state.getRestartTaker() : state.getCarrier();
    }

    /**
     * The player who took the kickoff. Captured once, at the kickoff: during the
     * pass flight there is no carrier, so asking the state for "the kicker"
     * mid-flight would return null and the real kicker would be checked as an
     * ordinary player. The owner rule exempts him ("except the player taking
     * the kickoff"), so the reference is kept.
     */
    private Player kickerRef;

    private Player kicker() {
        return kickerRef;
    }

    private void assertEveryoneInOwnHalf(String when) {
        Player k = kicker();
        for (Player p : state.getPlayers()) {
            if (p.isUnavailable()) continue;
            double row = p.getPosition().getRow();
            if (p == k) {
                // The kicker stands on the centre spot.
                assertEquals(MID_LINE, row, 0.6,
                        "the kicker must be on the centre spot (" + when + ")");
                continue;
            }
            if ("HOME".equals(p.getTeam())) {
                assertTrue(row <= MID_LINE - BUFFER + 1e-6,
                        "HOME " + p.getLabel() + " must be at least " + BUFFER
                                + " cells in his own half, was row " + row + " (" + when + ")");
            } else {
                assertTrue(row >= MID_LINE + BUFFER - 1e-6,
                        "AWAY " + p.getLabel() + " must be at least " + BUFFER
                                + " cells in his own half, was row " + row + " (" + when + ")");
            }
        }
    }

    @Test
    void everyoneIsInTheirOwnHalfAtTheKickoff() {
        assertNotNull(kicker(), "a kicker must be placed on the centre spot");
        assertEveryoneInOwnHalf("at kickoff");
    }

    @Test
    void theHoldSurvivesTheKickoffPassFlight() {
        // The hold must not lapse the instant the pass is struck, otherwise the
        // whole point of it is lost: the ball is still in the air.
        for (int t = 0; t < 2; t++) {
            orch.tick();
            if (state.getBall().getSpeed() > 0 && state.getCarrier() == null) {
                assertEveryoneInOwnHalf("tick " + t + " of the kickoff pass flight");
            }
        }
    }

    @Test
    void theHoldIsReleasedOnceTheKickoffPassIsReceived() {
        // Walk the match forward; the hold must not survive the whole match.
        for (int t = 0; t < 40; t++) {
            orch.tick();
            if (!state.isKickoffHalfHold()) return;
        }
        assertTrue(!state.isKickoffHalfHold(),
                "the kickoff hold must be released after the pass is received");
    }

    @Test
    void theHoldCannotOutliveTheMatchIfThePassIsNeverReceived() {
        // Safety cap: a kickoff pass that is intercepted/deflected out must not
        // freeze both teams in their own halves for the rest of the match.
        for (int t = 0; t < 200; t++) orch.tick();
        assertTrue(!state.isKickoffHalfHold(),
                "the kickoff hold must be released by the safety cap");
    }

    @Test
    void goalLinesAreTheExpectedSides() {
        assertEquals(1.0, PitchEnvironment.HOME_GOAL_LINE, 1e-9);
        assertEquals(8.0, PitchEnvironment.AWAY_GOAL_LINE, 1e-9);
    }
}
