package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The press danger zone is the same distance from each side's own goal line.
 *
 * <p><b>What was wrong (found 2026-10-19, P0-52).</b> {@code pressIsolatedOpponent} decides whether
 * an opponent is worth pressing with
 *
 * <pre>
 *   home ? opponentRow &lt;= DANGER_ZONE_ROW : opponentRow &gt;= 8.0 - DANGER_ZONE_ROW
 * </pre>
 *
 * and its own comment says: <i>"Danger zone = 20-30 m from OUR OWN GOAL. HOME goal line row 1 -&gt; rows
 * &lt;= 3.0; AWAY goal line row 8 -&gt; rows &gt;= 6.0."</i>
 *
 * {@code DANGER_ZONE_ROW} is 2.0 cells — 28 m, which is inside the 20–30 m band the comment names —
 * and the goal lines are row 1.0 and row 8.0. So the **AWAY** side was measured from its own goal
 * line correctly ({@code 8.0 - 2.0 = 6.0}, two cells from the goal) and the **HOME** side was not
 * ({@code <= 2.0} is one cell from the goal line: 14 m, and outside the band).
 *
 * <p><b>The two sides were not treated the same, and it showed.</b> Over 20 simulated matches:
 *
 * | | before | after |
 * |---|---|---|
 * | HOME TYPE B presses | 464 | 3,200 |
 * | AWAY TYPE B presses | 3,410 | 3,341 |
 *
 * A **7.4× difference** became a 4 % one, and the remainder is the home side attacking first.
 */
class PressDangerZoneIsTheSameForBothSidesTest {

    private static final double ZONE = ThreatOverrideEngine.DANGER_ZONE_ROW;

    /**
     * A state where one side has an isolated opponent 1.8 cells from its own goal line — inside the
     * danger zone the comment describes, outside the one the code was using.
     */
    private MatchState stateWhere(String pressingTeam, double presserRow, double opponentRow, double ballRow) {
        MatchState state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new java.util.ArrayList<>()));
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        boolean home = "HOME".equals(pressingTeam);

        Player presser = player(state, pressingTeam, "DL");
        presser.setPosition(new Position(presserRow, 3.0));

        Player opponent = player(state, home ? "AWAY" : "HOME", "STR");
        opponent.setPosition(new Position(opponentRow, 3.0));

        // Everybody else well out of the way: the pressing side's other men must not be closer to
        // the opponent than the presser, and the other side's men must not be in the danger zone.
        for (Player p : state.getPlayers()) {
            if (p == presser || p == opponent || p.isGoalkeeper()) continue;
            double row = home ? 4.5 : 3.5;
            p.setPosition(new Position(row, p.getPosition().getColumn()));
        }

        state.getBall().setPosition(new Position(ballRow, 3.0));
        return state;
    }

    private Player player(MatchState state, String team, String role) {
        return state.getPlayers().stream()
                .filter(p -> team.equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("HOME finds an opponent 1.8 cells from its own goal line")
    void homeFindsAnOpponentInItsOwnZone() {
        // HOME's goal line is row 1.0, so 1.8 cells into the danger zone is row 2.8.
        MatchState state = stateWhere("HOME", 2.4, 2.8, 2.0);
        Player presser = player(state, "HOME", "DL");

        new ThreatOverrideEngine().evaluate(state);

        assertNotNull(presser.getTarget(), "the HOME presser was not given a target at all");
        assertEquals(2.8, presser.getTarget().getRow(), 1e-9,
                "HOME did not press an opponent who is 1.8 cells from HOME's own goal line. The danger "
                        + "zone is measured from our own goal line, and HOME's was being measured from "
                        + "the row number instead — half the zone, and only on one side");
    }

    @Test
    @DisplayName("the control: AWAY finds the mirrored opponent")
    void awayFindsTheMirroredOpponent() {
        // AWAY's goal line is row 8.0, so 1.8 cells into the danger zone is row 6.2.
        MatchState state = stateWhere("AWAY", 5.6, 6.2, 7.0);
        Player presser = player(state, "AWAY", "DL");

        new ThreatOverrideEngine().evaluate(state);

        assertNotNull(presser.getTarget(), "the AWAY presser was not given a target at all");
        assertEquals(6.2, presser.getTarget().getRow(), 1e-9,
                "AWAY did not press an opponent who is 1.8 cells from AWAY's own goal line. This side "
                        + "was always measured correctly, and the test above is only meaningful if it "
                        + "still is");
    }

    @Test
    @DisplayName("the two sides' zones are the same distance from their own goal lines")
    void theTwoZonesAreTheSameDistance() {
        assertEquals(3.0, PitchEnvironment.HOME_GOAL_LINE + ZONE, 1e-9,
                "HOME's danger zone must reach two cells into the pitch from HOME's own goal line");
        assertEquals(6.0, PitchEnvironment.AWAY_GOAL_LINE - ZONE, 1e-9,
                "AWAY's danger zone must reach two cells into the pitch from AWAY's own goal line");
    }
}
