package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The TYPE C retreat is computed from the offside line, and the offside line counts the goalkeeper.
 *
 * <p><b>What disagreed (found 2026-10-19, E-5).</b> The retreat target is
 * {@code RETREAT_BUFFER} cells behind the offside line, and the offside line is the
 * <b>second-to-last defender</b> — and Law 11 counts <b>every</b> opponent, the goalkeeper included.
 *
 * <p>Four places in this codebase compute that line. Three of them count the goalkeeper:
 * {@code OffsideService.calculateOffsideMargin} (the rule itself),
 * {@code CleanDecisionEngine.secondLastDefenderRow} (who a pass may be played to) and
 * {@code ThreatOverrideEngine.isClearlyOnside} (when the retreat ends). The fourth —
 * {@code ThreatOverrideEngine.findSecondLastDefenderRow}, the one the <b>retreat target</b> comes
 * from — filtered the goalkeeper out, so it disagreed <b>with the other half of its own class</b>.
 *
 * <p>It is not a cosmetic difference. The goalkeeper is the deepest defender of the side he defends,
 * so for a side defending the low rows the line moves a full cell: the second-deepest defender is
 * row 2.5 with him counted and row 3.0 without, and the attacker retreats to a row half a cell
 * deeper than the law asks for.
 */
class OffsideRetreatUsesTheOffsideLineTest {

    private MatchState state;
    private ThreatOverrideEngine engine;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20261019L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        engine = new ThreatOverrideEngine();
    }

    private Player player(String team, String role) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }

    /**
     * The offside line, the way Law 11 states it: the second-deepest defender of the side being
     * attacked, <b>the goalkeeper included</b>.
     */
    private double theOffsideLine(String defendingTeam, boolean home) {
        List<Double> rows = state.getPlayers().stream()
                .filter(p -> defendingTeam.equals(p.getTeam()))
                .filter(p -> !p.isSentOff() && !p.isInjured())
                .map(p -> p.getPosition().getRow())
                .sorted(home ? java.util.Comparator.<Double>reverseOrder()
                             : java.util.Comparator.naturalOrder())
                .toList();
        return rows.get(1);
    }

    @Test
    @DisplayName("the retreat target is the offside line plus the buffer, goalkeeper included")
    void theRetreatTargetUsesTheOffsideLine() {
        // AWAY attacks the low rows. HOME's goalkeeper is the deepest HOME player (row 0.0), and
        // his next deepest team-mate is on row 2.5 — so the offside line is row 2.5.
        //
        // Every other HOME man is moved well up the pitch first, so the two defenders placed
        // below are the only ones in that half of the pitch.
        for (Player p : state.getPlayers()) {
            if ("HOME".equals(p.getTeam()) && !p.isGoalkeeper()) {
                p.setPosition(new Position(4.0, p.getPosition().getColumn()));
            }
        }
        player("HOME", "GK").setPosition(new Position(0.0, 3.5));
        // ONE defender on row 2.5 and the next on row 3.0 — which is what a real match looks like
        // once the formation has drifted. With the goalkeeper counted, the line is row 2.5; without
        // him it is row 3.0. (Two defenders on the SAME row would hide the difference, because the
        // goalkeeper is the deepest of them either way.)
        player("HOME", "DL").setPosition(new Position(2.5, 1.5));
        player("HOME", "DR").setPosition(new Position(3.0, 5.5));

        // An AWAY attacker who has been offside for long enough to be pulled back.
        Player attacker = player("AWAY", "STR");
        attacker.setPosition(new Position(1.5, 3.0));
        attacker.setConsecutiveOffsideCount(ThreatOverrideEngine.OFFSIDE_RETREAT_THRESHOLD);

        double line = theOffsideLine("HOME", false);
        assertEquals(2.5, line, 1e-9,
                "precondition: the offside line is the second-deepest HOME player with the goalkeeper "
                        + "counted, and HOME's goalkeeper is the deepest of them");

        engine.evaluate(state);

        assertNotNull(attacker.getTarget(), "the attacker was not given a retreat target at all");
        assertEquals(line + ThreatOverrideEngine.RETREAT_BUFFER, attacker.getTarget().getRow(), 1e-9,
                "the retreat target is not the offside line plus the buffer. The goalkeeper is the "
                        + "deepest defender of the side being attacked, so leaving him out moves the "
                        + "line a full cell and pulls the attacker back further than the law asks");
    }

    @Test
    @DisplayName("the control: an attacker who is not offside is not pulled back")
    void anOnsideAttackerIsNotPulledBack() {
        player("HOME", "GK").setPosition(new Position(0.0, 3.5));
        player("HOME", "DL").setPosition(new Position(2.5, 1.5));
        player("HOME", "DR").setPosition(new Position(2.5, 5.5));

        Player attacker = player("AWAY", "STR");
        attacker.setPosition(new Position(4.0, 3.0));
        attacker.setConsecutiveOffsideCount(0);
        attacker.setTarget(new Position(4.0, 3.0));

        engine.evaluate(state);

        assertNotNull(attacker.getTarget(), "precondition: the attacker has a target");
        assertEquals(4.0, attacker.getTarget().getRow(), 1e-9,
                "an attacker who is not offside was pulled back to the offside line. The retreat is for "
                        + "chronic offenders, not for everybody");
    }
}
