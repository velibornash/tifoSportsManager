package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchPhase;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimTeamFactory;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-UI (backlog): "restart krece pas iako nema igraca na lopti iako bi trebalo da
 * udu" — a restart must not start an action before the taker is physically ON the
 * ball, and no other player may steal the restart while he is still walking.
 *
 * These are the tick-level tests the suite was missing: every other restart test
 * only asserted restart SETUP (ball spot, taker identity, carrier == null).
 */
class RestartTakerArrivalTest {

    private static final double ON_BALL_EPS = BallPhysicsEngine.ON_BALL_EPS;

    private MatchState state;
    private MatchOrchestrator orch;

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(20260917L);
        state = new MatchState();
        SimTeamFactory.addTeam(state, "HOME");
        SimTeamFactory.addTeam(state, "AWAY");
        orch = new MatchOrchestrator(state);
    }

    /**
     * The ball has left play on the HOME left touchline -> HOME throw-in.
     * The taker is deliberately placed 1.5 cells away (below the 4.0-cell
     * teleport fast-path) so he has to WALK.
     */
    @Test
    void noActionExecutesBeforeTheTakerReachesTheBall() {
        orch.getRestartManager().handleRestart(state, "THROW_IN_HOME", new Position(4.0, 1.0));

        Player taker = state.getRestartTaker();
        assertNotNull(taker, "restart must designate a taker");
        assertNull(state.getCarrier(), "restart ball belongs to nobody until the taker is on it");
        assertEquals("SET_PIECE", state.getPhase().name());

        taker.setPosition(new Position(5.5, 1.0));

        int logMark = orch.getEventLog().size();
        int ticks = 0;
        while (state.getRestartTaker() != null && ticks < 120) {
            orch.tick();
            ticks++;
            // No action may be executed while the taker is still walking.
            for (String line : new java.util.ArrayList<>(orch.getEventLog().subList(
                    logMark, orch.getEventLog().size()))) {
                assertTrue(!line.contains("|EXE]"), "action executed before taker arrival: " + line);
            }
            logMark = orch.getEventLog().size();
        }

        assertNull(state.getRestartTaker(), "taker must have claimed the restart ball");
        assertEquals(taker.getId(), state.getCarrier().getId(), "the TAKER claims the ball");
        assertTrue(ticks > 0 && ticks < 120, "taker must claim within the walk budget, took " + ticks);
        assertTrue(state.getSetPieceType() == null || state.getSetPieceType().isEmpty(),
                "set piece must be cleared once the restart is played");
        assertEquals(MatchPhase.OPEN_PLAY, state.getPhase(),
                "phase must return to OPEN_PLAY once the restart is consumed");
    }

    /**
     * Pickup race: an opponent standing within PICKUP_R of the restart ball must
     * NOT be able to play the restart awarded to the other team. The taker still
     * claims it after his walk.
     */
    @Test
    void opponentInsidePickupRangeCannotStealTheRestart() {
        orch.getRestartManager().handleRestart(state, "THROW_IN_HOME", new Position(4.0, 1.0));

        Player taker = state.getRestartTaker();
        assertNotNull(taker);
        taker.setPosition(new Position(5.5, 1.0));

        // Park an available AWAY player right on the ball.
        Player thief = firstAvailable("AWAY");
        Position ballSpot = state.getBall().getPosition();
        thief.setPosition(new Position(ballSpot.getRow() + 0.15, ballSpot.getColumn()));

        int ticks = 0;
        while (state.getRestartTaker() != null && ticks < 120) {
            orch.tick();
            ticks++;
            // While the taker is still walking, nobody may hold the ball...
            if (state.getRestartTaker() != null) {
                assertNull(state.getCarrier(),
                        "no player may hold the restart ball while the taker is walking (tick " + ticks + ")");
            }
        }
        // ...and when the restart is finally played, it is the TAKER who plays it.
        assertNotNull(state.getCarrier(), "the restart must be played");
        assertEquals(taker.getId(), state.getCarrier().getId(),
                "only the designated taker may play the restart");
        assertTrue(!thief.getId().equals(state.getCarrier().getId()),
                "an opponent must never play a restart awarded to the other team");
    }

    /** Corner taker must be the winger on the corner's own side, not always ML/DL. */
    @Test
    void cornerTakerIsTheWingerOnTheCornerFlagSide() {
        orch.getRestartManager().handleRestart(state, "CORNER_HOME", new Position(8.0, 7.0));
        Player taker = state.getRestartTaker();
        assertNotNull(taker, "corner must have a taker");
        assertTrue(List.of("MR", "DR").contains(taker.getRole()),
                "right-flag corner must be taken by a RIGHT winger, got " + taker.getRole());
    }

    /** Left-flag corner mirror case. */
    @Test
    void leftFlagCornerIsTakenByALeftWinger() {
        orch.getRestartManager().handleRestart(state, "CORNER_HOME", new Position(8.0, 1.0));
        Player taker = state.getRestartTaker();
        assertNotNull(taker);
        assertTrue(List.of("ML", "DL").contains(taker.getRole()),
                "left-flag corner must be taken by a LEFT winger, got " + taker.getRole());
    }

    /** A restart with no available taker must not leave a stale carrier behind. */
    @Test
    void restartClearsAnyStaleCarrierEvenWithoutATaker() {
        Player stale = firstAvailable("HOME");
        state.setCarrier(stale);
        state.getBall().setPosition(new Position(4.0, 1.0));
        state.getBall().stop();

        for (Player p : List.copyOf(state.getPlayers())) {
            p.setSentOff(true);
        }
        orch.getRestartManager().handleRestart(state, "THROW_IN_HOME", new Position(4.0, 1.0));

        assertNull(state.getCarrier(),
                "the restart ball must never stay 'held' by a stale open-play carrier");
    }

    private Player firstAvailable(String team) {
        return state.getPlayers().stream()
                .filter(p -> p.getTeam().equals(team) && !p.isUnavailable())
                .findFirst()
                .orElseThrow();
    }
}
