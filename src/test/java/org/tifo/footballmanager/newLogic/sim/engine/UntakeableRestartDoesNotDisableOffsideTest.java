package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.tifo.footballmanager.newLogic.sim.restarts.RestartManager;
import org.tifo.footballmanager.newLogic.sim.rules.OffsideService;
import org.tifo.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.tifo.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A restart nobody can take must not switch the offside check off for the rest of the match.
 *
 * <p><b>What was wrong (found 2026-10-19, E-4).</b> A set piece suspends the offside check:
 * {@code OffsideService.checkOffside} skips every check while a set piece is pending, and the only
 * path that hands a restart ball to a player is the <b>designated taker's</b>. So a restart that no
 * player can take left the match with offside switched off <b>for the rest of it</b> — and the ball
 * sitting on the spot, going nowhere.
 *
 * <p>Nobody can take a restart when every player of the taking team is unavailable, or locked — and
 * a free-kick wall is made of locked players, which is exactly what {@code findNearestPlayerOfTeam}
 * filters on.
 *
 * <p><b>The observable is the flag, not the state.</b> The offside rules flag a receiver, and the
 * whistle fires when the flagged player touches the ball. If the set piece is still pending, the
 * check is skipped and no flag is ever raised — so a flag appearing is proof that the offside check
 * is live again.
 */
class UntakeableRestartDoesNotDisableOffsideTest {

    private MatchState state;
    private RestartManager restarts;
    private OffsideService offside;
    private Player receiver;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20261019L);
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new java.util.ArrayList<>()));
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        restarts = new RestartManager(new TacticsRules());
        offside = new OffsideService(state, null, new MatchRecorder(), restarts);

        // AWAY's back four sit on rows 6.5 and 7.0, so the offside line — the second-to-last
        // defender — is row 7.0. A HOME runner on row 7.6 is 0.6 cells beyond it: clearly offside.
        receiver = homePlayer("STL");
        receiver.setPosition(new Position(7.6, 3.0));
    }

    private Player homePlayer(String role) {
        return state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && role.equals(p.getRole()))
                .findFirst().orElseThrow();
    }

    /** Every player of this team is in a wall, or out of the game. */
    private void nobodyOfThisTeamCanTake(String team) {
        for (Player p : state.getPlayers()) {
            if (team.equals(p.getTeam())) {
                p.setLocked(true);
            }
        }
    }

    /** The ball is played from here — a wide HOME player in the final third. */
    private Position passOrigin() {
        return new Position(6.0, 1.0);
    }

    @Test
    @DisplayName("a restart nobody can take is not created, and the offside check stays live")
    void anUntakeableRestartIsNotCreated() {
        nobodyOfThisTeamCanTake("AWAY");

        restarts.handleRestart(state, "THROW_IN_AWAY", new Position(4.5, 8.0));

        assertNull(state.getSetPieceType(),
                "a set piece was created for a restart nobody can take. The offside check is skipped "
                        + "while a set piece is pending, so the match would go the rest of the way "
                        + "with offside switched off");

        offside.checkOffside(receiver, passOrigin(), state);

        assertSame(receiver, state.getOffsideFlaggedReceiver(),
                "the offside check is still switched off. The flag is what the whistle hangs on, so a "
                        + "match in this state has no offside at all");
    }

    @Test
    @DisplayName("a restart somebody CAN take still suspends the offside check")
    void aTakeableRestartStillSuspendsTheOffsideCheck() {
        // The control. A restart in progress legitimately suspends the offside check — that is the
        // rule, not a bug — so this test proves the one above is measuring the right thing.
        restarts.handleRestart(state, "THROW_IN_HOME", new Position(4.5, 8.0));

        assertNotNull(state.getSetPieceType(),
                "precondition: the restart was set up, with a taker who can take it");

        offside.checkOffside(receiver, passOrigin(), state);

        assertNull(state.getOffsideFlaggedReceiver(),
                "the offside check ran during a set piece. It must not: the check is skipped while a "
                        + "set piece is pending, and the control above is only meaningful if that "
                        + "skip really happens");
    }

    @Test
    @DisplayName("a free kick nobody can take is not created either")
    void anUntakeableFreeKickIsNotCreated() {
        nobodyOfThisTeamCanTake("HOME");

        restarts.handleFreeKick(state, new Position(4.0, 3.0), "HOME");

        assertNull(state.getSetPieceType(),
                "a free kick was created for a team whose every player is locked. The offside check "
                        + "would be skipped for the rest of the match");
    }

    /**
     * The other way round: the restart was fine, and then the taker was lost on his way to the ball.
     *
     * <p>{@code RestartManager} refuses to create a set piece nobody can take, so the taker disappearing
     * afterwards is the case that has to be caught while the match is running: the ball engine releases
     * the restart when the taker goes off, and from that tick on nobody is going to take it.
     */
    @Test
    @DisplayName("a taker who is sent off on his way to the ball does not leave the offside check off")
    void aTakerWhoDisappearsMidWalkDoesNotDisableTheOffsideCheck() {
        restarts.handleRestart(state, "THROW_IN_HOME", new Position(4.5, 8.0));
        assertNotNull(state.getSetPieceType(), "precondition: the restart was set up");
        Player taker = state.getRestartTaker();
        assertNotNull(taker, "precondition: the restart has a taker who can take it");

        taker.setSentOff(true);

        MatchOrchestrator orch = new MatchOrchestrator(state);
        orch.tick();

        assertNull(state.getSetPieceType(),
                "the restart survived the taker's disappearance, so the offside check stayed switched "
                        + "off for the rest of the match");
    }
}
