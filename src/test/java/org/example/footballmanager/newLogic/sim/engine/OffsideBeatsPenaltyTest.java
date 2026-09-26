package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.PlayerSkills;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.tactics.TacticsRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offside takes precedence over a penalty (user rule 2026-09-26).
 *
 * <p>If the ball was played to an attacker while he was in an offside position, the phase is a
 * prohibited action and a penalty for a foul on him does not stand — the offside is called and play
 * restarts with an indirect free kick.
 *
 * <p>This is the root cause of the seed-123 penalty defect: a pass was played to a flagged
 * receiver, a box foul was called on the following tick, and the offside indirect free kick then
 * landed on top of the penalty one tick later and silently erased it. Suppressing the penalty at
 * the award removes the collision instead of papering over it downstream.
 */
class OffsideBeatsPenaltyTest {

    private MatchState state;
    private RestartManager restarts;

    private Player attacker;
    private Player defender;

    private static Player player(String id, String team, String role, double row, double col) {
        return new Player(id, id, team, role,
                new Position(row, col), new Position(row, col), PlayerSkills.neutral(), 0);
    }

    @BeforeEach
    void setUp() {
        state = new MatchState();
        state.setActionLogger(new ActionLogService(state, new java.util.ArrayList<>()));
        restarts = new RestartManager(new TacticsRules());
        for (int i = 0; i < 11; i++) {
            state.getPlayers().add(player("H" + i, "HOME", i == 0 ? "GK" : "CMR", 3.0, 2.0 + i * 0.3));
            state.getPlayers().add(player("A" + i, "AWAY", i == 0 ? "GK" : "CMR", 6.0, 2.0 + i * 0.3));
        }
        attacker = state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && p.getRole().equals("CMR")).findFirst().orElseThrow();
        defender = state.getPlayers().stream()
                .filter(p -> "AWAY".equals(p.getTeam()) && p.getRole().equals("CMR")).findFirst().orElseThrow();
        attacker.setPosition(new Position(7.0, 3.0));
        defender.setPosition(new Position(7.0, 3.4));
        state.getBall().setPosition(new Position(7.0, 3.0));
    }

    /** Mirrors the guard in DuelService.evaluateDiscipline. */
    private boolean penaltyWouldBeAwarded() {
        Player flagged = state.getOffsideFlaggedReceiver();
        return flagged == null || flagged != attacker;
    }

    @Test
    @DisplayName("a flagged attacker who is fouled does not get a penalty")
    void flaggedAttackerGetsNoPenalty() {
        state.setOffsideFlaggedReceiver(attacker);
        assertFalse(penaltyWouldBeAwarded(),
                "an attacker flagged offside must not be awarded a penalty when fouled");
    }

    @Test
    @DisplayName("an onside attacker who is fouled still gets his penalty")
    void onsideAttackerStillGetsThePenalty() {
        state.setOffsideFlaggedReceiver(null);
        assertTrue(penaltyWouldBeAwarded(),
                "an onside attacker must still be awarded the penalty");
    }

    @Test
    @DisplayName("a different player being flagged does not suppress this attacker's penalty")
    void someoneElsesOffsideDoesNotSuppress() {
        Player other = state.getPlayers().stream()
                .filter(p -> "HOME".equals(p.getTeam()) && p != attacker && p.getRole().equals("CMR"))
                .findFirst().orElseThrow();
        state.setOffsideFlaggedReceiver(other);
        assertTrue(penaltyWouldBeAwarded(),
                "another player's offside must not cancel this attacker's penalty");
    }

    @Test
    @DisplayName("an offside free kick supersedes a penalty pending for the same incident")
    void offsideSupersedesPendingPenalty() {
        restarts.handlePenalty(state, "HOME");
        assertTrue(state.isPenaltyPending(), "precondition: a penalty is pending");

        restarts.handleOffsideFreeKick(state, new Position(7.0, 3.0));

        assertFalse(state.isPenaltyPending(),
                "offside has priority, so the pending penalty must be cleared");
    }

    @Test
    @DisplayName("an ordinary free kick does NOT supersede a pending penalty")
    void ordinaryFreeKickDoesNotSupersede() {
        restarts.handlePenalty(state, "HOME");
        double penaltySpotRow = state.getBall().getPosition().getRow();

        restarts.handleFreeKick(state, new Position(5.0, 3.0), "HOME");

        assertTrue(state.isPenaltyPending(),
                "a plain free kick must not quietly erase a penalty the referee awarded");
        assertEquals(penaltySpotRow, state.getBall().getPosition().getRow(), 0.01,
                "the ball must still be on the penalty spot");
    }
}
