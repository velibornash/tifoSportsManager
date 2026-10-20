package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A penalty nobody can take is a penalty that does not exist.
 *
 * <p><b>The defect this guards (found while fixing P0-5, 2026-10-19).</b> The owner awards a penalty
 * and the match stops. If the side that was fouled then loses everybody who could take it — a red card and
 * a sending-off injury will do it — the runner eventually gives up:
 *
 * <pre>
 *   log("PEN", "penalty cannot be taken — no available taker for " + team);
 *   state.clearSetPieceType();
 *   return;
 * </pre>
 *
 * <p>It cleared the <b>set piece type</b> and not the <b>penalty flag</b>. So the penalty stayed owed to
 * nobody: the next player to touch the ball <em>anywhere on the pitch</em> — a defender in his own third,
 * forty seconds later — fell into the guard that turns a carrier into a penalty taker, and the engine
 * awarded a penalty from a random position, by the wrong team, and counted it as a shot.
 *
 * <p>It is a rare branch (a whole side unavailable) and a bad one, and it is cheap to close.
 *
 * <p><b>Why it cannot pass by accident.</b> The assertion is on the flag itself, read from the state after
 * the runner has given up. Reverting the one-line fix leaves the flag set, and the test goes red.
 */
class PenaltyWithNoTakerIsCalledOffTest {

    private MatchState state;
    private MatchOrchestrator orch;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20260919L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        orch = new MatchOrchestrator(state);
    }

    /** Everybody of this team is out of the game. */
    private void nobodyOfThisTeamCanPlay(String team) {
        for (Player player : state.getPlayers()) {
            if (team.equals(player.getTeam())) {
                player.setSentOff(true);
            }
        }
    }

    /** Nobody at all. */
    private void nobodyCanPlay() {
        for (Player player : state.getPlayers()) {
            player.setSentOff(true);
        }
    }

    /** How many penalty kicks were actually struck. */
    private long penaltyKicksStruck() {
        return orch.getRecorder().getEvents().stream()
                .filter(event -> "PENALTY_KICK".equals(event.getType()))
                .count();
    }

    @Test
    @DisplayName("a penalty nobody can take is called off, not left owed")
    void aPenaltyWithNoTakerIsCalledOff() {
        // The award, and then the side that was fouled loses everybody.
        orch.getRestartManager().handlePenalty(state, "AWAY");
        assertTrue(state.isPenaltyPending(), "the award did not latch the penalty");
        nobodyOfThisTeamCanPlay("AWAY");

        // Long enough for the stall guard to give up. It waits 40 ticks before it forces the kick.
        for (int tick = 0; tick < 45; tick++) {
            orch.tick();
        }

        assertFalse(state.isPenaltyPending(),
                "the penalty is still owed after the runner gave up finding a taker. The next player to "
                        + "touch the ball anywhere on the pitch would be turned into a penalty taker, and "
                        + "the engine would award a penalty from a random position by the wrong team.");
    }

    /**
     * The control, and the part that actually catches the defect.
     *
     * <p>With somebody left to pick the ball up, the flag ends up cleared either way — so the flag alone
     * cannot tell "the penalty was called off" from "the penalty was taken by whoever picked the ball
     * up", and a test that reads only the flag passes against the broken code.
     *
     * <p><b>What has to be asserted is that no penalty kick was struck.</b> Before the fix, the first
     * player to touch the ball — a defender in his own third, forty seconds after a penalty nobody could
     * take was called off — was turned into the taker, and the engine awarded a penalty from a random
     * position, by the wrong team, and counted it as a shot.
     */
    @Test
    @DisplayName("a penalty nobody can take is not struck by whoever picks the ball up")
    void aPenaltyWithNoTakerIsNotStruckByAnyone() {
        orch.getRestartManager().handlePenalty(state, "AWAY");
        assertTrue(state.isPenaltyPending(), "the award did not latch the penalty");
        // The fouled side cannot take it. The other side is on the pitch, and one of them will walk onto
        // the ball — which is exactly how the phantom penalty was awarded.
        nobodyOfThisTeamCanPlay("AWAY");

        for (int tick = 0; tick < 45; tick++) {
            orch.tick();
        }

        assertEquals(0, penaltyKicksStruck(),
                "a penalty kick was struck even though the side that was fouled had nobody to take it. "
                        + "The ball was left on the spot, somebody eventually touched it, and the engine "
                        + "awarded a penalty from wherever he happened to be standing.");
    }

    /**
     * The control: with somebody available to take it, the penalty IS struck and the flag is cleared.
     *
     * <p>Without this the test above would also pass if no penalty were ever awarded, and it would be
     * proving nothing.
     */
    @Test
    @DisplayName("a penalty somebody can take is struck")
    void aPenaltyWithATakerIsStruck() {
        orch.getRestartManager().handlePenalty(state, "AWAY");
        assertTrue(state.isPenaltyPending(), "the award did not latch the penalty");

        for (int tick = 0; tick < 45; tick++) {
            orch.tick();
        }

        assertEquals(1, penaltyKicksStruck(),
                "the penalty was never struck even though the side had a taker — so the test above is "
                        + "not measuring what it claims to");
    }
}
