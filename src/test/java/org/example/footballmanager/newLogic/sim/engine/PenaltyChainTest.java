package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 1.7b — a penalty must always be taken.
 *
 * <p>The bug this exists for: a foul that is both a penalty and an offside in the same incident
 * produced an offside free kick, which overwrote {@code setPieceType} on the next tick. The penalty
 * was re-derived from the set-piece type at the moment the taker reached the ball, saw
 * {@code FREE_KICK}, and downgraded itself — the penalty was never taken, the ball was restarted as
 * a free kick, and the only symptom in the aggregate was one fewer goal.
 *
 * <p>Found on seed 123: awarded at 66:19, became a throw-in by 66:22, no kick event at all.
 */
class PenaltyChainTest {

    private MatchState state;
    private RestartManager restarts;

    private static Player player(String id, String team, String role, double row) {
        return new Player(id, id, team, role,
                new Position(row, 3.5), new Position(row, 3.5), PlayerSkills.neutral(), 0);
    }

    @BeforeEach
    void setUp() {
        SimulationRandom.seed(4242L);
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new java.util.ArrayList<>()));
        restarts = new RestartManager(new TacticsRules());
        for (int i = 0; i < 11; i++) {
            state.getPlayers().add(player("H" + i, "HOME", i == 0 ? "GK" : "STL", 5.0));
            state.getPlayers().add(player("A" + i, "AWAY", i == 0 ? "GK" : "STL", 3.0));
        }
    }

    @Test
    @DisplayName("awarding a penalty latches it, so it survives losing the set-piece type")
    void awardLatchesThePenalty() {
        restarts.handlePenalty(state, "HOME");
        assertTrue(state.isPenaltyPending(), "handlePenalty must latch the penalty");
    }

    @Test
    @DisplayName("an offside free kick on the same incident cannot displace a pending penalty")
    void freeKickCannotDisplaceAPendingPenalty() {
        restarts.handlePenalty(state, "HOME");
        Position spot = state.getBall().getPosition();
        assertEquals(7.2, spot.getRow(), 0.01, "ball starts on the penalty spot");

        // Same incident, same tick: the offside path asks for a free kick at the spot.
        restarts.handleFreeKick(state, new Position(6.0, 3.0), "HOME");

        assertTrue(state.isPenaltyPending(), "the penalty must still be pending");
        assertEquals("PENALTY_HOME", state.getSetPieceType(),
                "the set-piece type must still be the penalty, not the free kick");
        assertEquals(7.2, state.getBall().getPosition().getRow(), 0.01,
                "the ball must not have been moved off the spot");
    }

    @Test
    @DisplayName("a free kick before any penalty is awarded still works normally")
    void freeKickAloneIsUnaffected() {
        restarts.handleFreeKick(state, new Position(6.0, 3.0), "HOME");
        assertFalse(state.isPenaltyPending(), "no penalty was awarded");
        assertEquals("FREE_KICK", state.getSetPieceType());
    }

    @Test
    @DisplayName("taking the kick clears the latch")
    void takingTheKickClearsTheLatch() {
        restarts.handlePenalty(state, "HOME");
        Player taker = state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && !p.isUnavailable()
                        && !p.getRole().startsWith("GK"))
                .findFirst().orElseThrow();

        PenaltyEngine engine = new PenaltyEngine(state, new MatchRecorder(),
                new ProposalStatsCollector("H", "A"),
                new ActionLogService(state, new java.util.ArrayList<>()), restarts);
        engine.execute(taker, state.getPlayers().stream()
                .filter(p -> "AWAY".equals(p.getTeam()) && p.getRole().startsWith("GK"))
                .findFirst().orElse(null));

        assertFalse(state.isPenaltyPending(), "the latch must be cleared once the kick is taken");
    }
}
