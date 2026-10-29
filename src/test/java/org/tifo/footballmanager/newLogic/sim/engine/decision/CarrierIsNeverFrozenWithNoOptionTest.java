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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carrier always gets a legal action — he is never frozen on the ball.
 *
 * <p><b>What was broken (found 2026-10-26, replay 5633, F7).</b> When the carrier had no team-mate
 * to pass to, {@code scorePassOptions} scored the receiverless pass at <b>-20</b> — above
 * {@code UNAVAILABLE} (-10 000). That single number caused a deadlock chain:</p>
 *
 * <ol>
 *   <li>The -20 pass was a <em>legal</em> option, so it won the "least bad of a bad menu"
 *       selection whenever everything else was worse (a clearance is vetoed outside the defensive
 *       third and behind a frequency gate; a shot is vetoed outside the shooting zone; a dribble
 *       was vetoed precisely <em>because</em> "a pass is available").</li>
 *   <li>{@code ActionExecutor.executePass} receives a null target and does nothing at all.</li>
 *   <li>So the same situation was re-decided every tick and the ball never moved. Measured in
 *       the replay: Kačar stood at (2.2, 2.9) from 0:21 to 0:55 — 23 consecutive
 *       "PASS score=-20 [no receiver]" ticks, a player frozen for half a minute.</li>
 * </ol>
 *
 * <p><b>The fix, both halves.</b> A receiverless pass now scores {@code UNAVAILABLE} like every
 * other impossible option — so it no longer vetoes the dribble and no longer wins the selection.
 * And when <em>nothing</em> is available at all (no receiver, the clear gates both closed, no shot,
 * no dribble), {@code decideWithOptions} produces an emergency clearance: a legal, executable
 * action that puts the ball in play upfield. The carrier must always have something to do.</p>
 */
class CarrierIsNeverFrozenWithNoOptionTest {

    private MatchState state;
    private CleanDecisionEngine engine;
    private Player carrier;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20261026L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        engine = new CleanDecisionEngine();

        // The replay's own frozen player: a HOME defender inside his own third.
        carrier = homePlayer("DCL");
        carrier.setPosition(new Position(2.2, 2.9));
        state.setCarrier(carrier);
        state.getBall().setPosition(carrier.getPosition());
    }

    private Player homePlayer(String role) {
        return state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }

    /** Every team-mate off the pitch → this carrier has nobody to pass to. */
    private void noReceiverAvailable(Player theCarrier) {
        state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && p != theCarrier)
                .forEach(p -> p.setSentOff(true));
    }

    private DecisionOption passOption() {
        DecisionResult result = engine.decideWithOptions(state);
        return result.getOptions().stream()
                .filter(o -> o.getType() == ActionType.PASS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the engine produced no PASS option at all"));
    }

    @Test
    @DisplayName("a pass to nobody is not an option, and it must not veto the dribble")
    void aPassToNobodyIsNotAnOption() {
        noReceiverAvailable(carrier);

        DecisionOption pass = passOption();

        assertEquals(-10_000.0, pass.getScore(), 0.001,
                "a receiverless pass scored " + pass.getScore() + " — above UNAVAILABLE. That made "
                        + "it a legal option, won it the least-bad selection, and ActionExecutor "
                        + "then did nothing with its null target: the carrier froze on the ball. "
                        + "It says: " + pass.getReason());
        assertTrue(pass.getReason().contains("no receiver"),
                "the reason should say why the pass is unavailable, it says: " + pass.getReason());
    }

    @Test
    @DisplayName("the deadlock: no receiver, clear gates closed, no shot — the carrier still gets a legal action")
    void theFrozenDeadlockProducesAnAction() {
        noReceiverAvailable(carrier);
        // The replay's exact gate: the clearance's per-possession frequency roll failed, so CLEAR
        // was UNAVAILABLE too, and the -20 pass was the only "legal" option on the menu.
        state.possessionRoll("CLEAR_GATE", () -> false);

        DecisionResult result = engine.decideWithOptions(state);
        DecisionOption chosen = result.getChosen();

        assertTrue(chosen.getScore() > -10_000.0,
                "the carrier was left with no legal action at all — he would stand on the ball. "
                        + "Chosen: " + chosen.getType() + " score=" + chosen.getScore()
                        + " [" + chosen.getReason() + "]");
        assertTrue(chosen.getType() != ActionType.PASS || chosen.getTarget() != null,
                "the chosen action is a pass with no target — executing it does nothing and the "
                        + "carrier is frozen again");
    }

    @Test
    @DisplayName("the emergency clearance fires when every option is unavailable")
    void theEmergencyClearFiresWhenNothingIsAvailable() {
        // A goalkeeper never dribbles, so with every team-mate off and both clear gates closed
        // there is literally no option left: PASS/DRIBBLE/SHOT/CLEAR/THRU/CENTER all UNAVAILABLE.
        Player keeper = homePlayer("GK");
        keeper.setPosition(new Position(1.5, 3.5));
        state.setCarrier(keeper);
        state.getBall().setPosition(keeper.getPosition());
        noReceiverAvailable(keeper);
        state.possessionRoll("CLEAR_GATE", () -> false);

        DecisionResult result = engine.decideWithOptions(state);
        DecisionOption chosen = result.getChosen();

        assertEquals(ActionType.CLEAR, chosen.getType(),
                "nothing on the menu was available and the chosen action is " + chosen.getType()
                        + " [" + chosen.getReason() + "] — the emergency clearance is what keeps "
                        + "the match moving when a carrier has literally no option");
        assertTrue(chosen.getReason().contains("emergency"),
                "the fallback should be recognisable in the log, it says: " + chosen.getReason());
    }
}
