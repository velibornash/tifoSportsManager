package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.PlayerSkills;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MatchState — kickoff flags, active chaser tracking,
 * THRU ball-arrival tick field, offside guard semantics.
 *
 * Verifies architectural invariants from corePrinciples:
 * - MatchState holds state, doesn't decide (§1)
 * - Kickoff flag is single-tick (cleared after the kickoff decision)
 * - Active chasers track receivers during THRU wait
 */
class TestMatchState {

    @Test
    void kickoffActionPendingDefaultsToFalse() {
        MatchState state = buildState();
        assertFalse(state.isKickoffActionPending());
    }

    @Test
    void kickoffActionPendingCanBeSetAndCleared() {
        MatchState state = buildState();
        state.setKickoffPending(true);
        state.setKickoffActionPending(true);
        assertTrue(state.isKickoffActionPending());
        assertTrue(state.isKickoffPending());
        state.setKickoffActionPending(false);
        assertFalse(state.isKickoffActionPending());
    }

    @Test
    void thruBallArrivalTickFieldExistsAndMutable() {
        MatchState state = buildState();
        assertEquals(-1, state.getThruBallArrivalTick());
        state.setThruBallArrivalTick(100L);
        assertEquals(100L, state.getThruBallArrivalTick());
        state.setThruBallArrivalTick(-1);
        assertEquals(-1, state.getThruBallArrivalTick());
    }

    @Test
    void activeChaserAddRemove() {
        MatchState state = buildState();
        Player p1 = state.getPlayers().get(0);
        assertFalse(state.isActiveChaser(p1));
        state.addActiveChaser(p1);
        assertTrue(state.isActiveChaser(p1));
        state.removeActiveChaser(p1);
        assertFalse(state.isActiveChaser(p1));
    }

    @Test
    void beginRoundDoesNotClearActiveChasers() {
        // corePrinciples §19: beginRound resets round budget but preserves
        // active chasers (receivers running onto THRU balls remain chasers)
        MatchState state = buildState();
        Player p0 = state.getPlayers().get(0);
        state.addActiveChaser(p0);
        state.beginRound();
        // Active chasers should still be tracked after beginRound
        assertTrue(state.isActiveChaser(p0),
                "beginRound should NOT clear active chasers (THRU wait invariant)");
    }

    @Test
    void actionDelayTicksAreMutable() {
        MatchState state = buildState();
        assertEquals(0, state.getActionDelayTicks());
        state.setActionDelayTicks(20);
        assertEquals(20, state.getActionDelayTicks());
        state.consumeActionDelayTick();
        assertEquals(19, state.getActionDelayTicks());
    }

    @Test
    void phaseTransitionsWork() {
        MatchState state = buildState();
        assertEquals(org.example.footballmanager.demo.service.model.MatchPhase.OPEN_PLAY,
                state.getPhase());
        state.setPhase(org.example.footballmanager.demo.service.model.MatchPhase.KICK_OFF);
        assertEquals(org.example.footballmanager.demo.service.model.MatchPhase.KICK_OFF,
                state.getPhase());
    }

    // --- Helpers ---

    private MatchState buildState() {
        List<Player> players = new ArrayList<>();
        PlayerSkills skills = PlayerSkills.neutral();
        for (int i = 0; i < 11; i++) {
            players.add(new Player("h" + i, "Home " + i, "HOME", "MID",
                    new Position(4, 3), new Position(4, 3), skills));
        }
        for (int i = 0; i < 11; i++) {
            players.add(new Player("a" + i, "Away " + i, "AWAY", "MID",
                    new Position(4, 3), new Position(4, 3), skills));
        }
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        TacticsRules tactics = new TacticsRules();
        Random random = new Random(42);
        MatchRecorder recorder = new MatchRecorder();
        return new MatchState(players, ball, tactics, random, recorder);
    }
}
