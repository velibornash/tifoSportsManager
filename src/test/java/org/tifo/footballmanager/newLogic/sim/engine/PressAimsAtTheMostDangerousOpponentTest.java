package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * When two opponents qualify for the press, the presser goes to the dangerous one.
 *
 * <p><b>What decided it (found 2026-10-19, E-6).</b> {@code pressIsolatedOpponent} walked the
 * opponent list and returned <b>the first</b> opponent that passed the danger-zone, range and
 * goal-side tests. The order of the opponent list is the order the players were added to the state —
 * and that is the <b>formation order</b>: goalkeeper, full-backs, centre-backs, midfielders,
 * strikers. So when two attackers qualified, the one who happened to be listed earlier won. A
 * full-back was preferred to a striker, and the choice was an accident of the team factory rather
 * than a football decision.
 *
 * <p><b>What decides it now.</b> The most dangerous opponent. Both candidates are in the danger zone
 * (near our own goal) and goal-side of the ball, so the one that can hurt us most is the one nearest
 * our own goal line: the attacker who is deepest in our defensive third, with the ball behind him and
 * our net in front of him. That is the threat a defender leaves alone at his own peril.
 *
 * <p><b>One man per threat is unchanged.</b> {@code isClosestEligiblePresser} still decides <em>who</em>
 * goes — the closest man to the threat, and only him, so the rules cannot turn into a swarm. This
 * change only decides <em>which</em> threat, when a man is close enough to more than one of them.
 */
class PressAimsAtTheMostDangerousOpponentTest {

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
     * Two AWAY attackers in HOME's danger zone, both goal-side of the ball, both within a presser's
     * reach — and the dangerous one is listed <b>later</b> than the harmless one.
     *
     * <p>The listing order is the formation order the team factory uses, so the midfielder is added
     * before the striker. The old code returned the first candidate it found, so it would have gone
     * to the midfielder.
     */
    private void twoQualifyingOpponents() {
        Player harmless = player("AWAY", "ML");
        harmless.setPosition(new Position(2.0, 3.4));

        Player dangerous = player("AWAY", "STR");
        dangerous.setPosition(new Position(1.5, 2.6));

        // The presser: close enough to both, and the closest HOME man to either.
        Player presser = player("HOME", "DL");
        presser.setPosition(new Position(1.75, 3.0));

        // Every other outfielder well out of the way — and, for the AWAY men, out of HOME's danger
        // zone, so they cannot qualify as a third candidate.
        for (Player p : state.getPlayers()) {
            if ("HOME".equals(p.getTeam()) && !p.isGoalkeeper() && p != presser) {
                p.setPosition(new Position(4.5, p.getPosition().getColumn()));
            }
            if ("AWAY".equals(p.getTeam()) && !p.isGoalkeeper()
                    && p != harmless && p != dangerous) {
                p.setPosition(new Position(6.5, p.getPosition().getColumn()));
            }
        }

        // The ball, behind both of them: they are goal-side of it, so both are real threats.
        state.getBall().setPosition(new Position(1.1, 3.0));
    }

    @Test
    @DisplayName("the presser goes to the opponent nearest our own goal")
    void thePresserGoesToTheMostDangerousOpponent() {
        twoQualifyingOpponents();
        Player presser = player("HOME", "DL");
        Player dangerous = player("AWAY", "STR");
        Player harmless = player("AWAY", "ML");

        engine.evaluate(state);

        assertNotNull(presser.getTarget(), "the presser was not given a target at all");
        assertEquals(dangerous.getPosition().getRow(), presser.getTarget().getRow(), 1e-9,
                "the presser went to the wrong man. Both attackers qualified — in HOME's danger zone, "
                        + "goal-side of the ball, within reach — and the one nearest our own goal is "
                        + "the dangerous one. The harmless man is on row "
                        + harmless.getPosition().getRow() + " and the dangerous one on row "
                        + dangerous.getPosition().getRow());
    }

    @Test
    @DisplayName("the control: with one qualifying opponent, the presser goes to him")
    void withOneOpponentThePresserGoesToHim() {
        Player only = player("AWAY", "STR");
        only.setPosition(new Position(1.5, 2.6));

        Player presser = player("HOME", "DL");
        presser.setPosition(new Position(1.75, 3.0));

        for (Player p : state.getPlayers()) {
            if ("HOME".equals(p.getTeam()) && !p.isGoalkeeper() && p != presser) {
                p.setPosition(new Position(4.5, p.getPosition().getColumn()));
            }
            if ("AWAY".equals(p.getTeam()) && !p.isGoalkeeper() && p != only) {
                p.setPosition(new Position(6.5, p.getPosition().getColumn()));
            }
        }
        state.getBall().setPosition(new Position(1.1, 3.0));

        engine.evaluate(state);

        assertNotNull(presser.getTarget(), "the presser was not given a target at all");
        assertEquals(only.getPosition().getRow(), presser.getTarget().getRow(), 1e-9,
                "the presser did not go to the only opponent he could reach, so the test above is not "
                        + "measuring what it claims to");
    }
}
