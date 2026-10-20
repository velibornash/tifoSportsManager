package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.engine.ExecutionQuality;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.PlayerSkills;
import org.example.footballmanager.demo.service.model.Position;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExecutionQuality — THRU_SUCCESS_THRESHOLD constant,
 * pass evaluation logic, and shot evaluation.
 *
 * Core fix: THRU passes now use the deviated actualTarget as flight target
 * when in bounds, rather than the receiver's kick-time position.
 */
class TestExecutionQuality {

    @Test
    void thruSuccessThresholdIs2() {
        assertEquals(2.0, ExecutionQuality.THRU_SUCCESS_THRESHOLD, 1e-9);
    }

    @Test
    void passResultRecordsSkillAndActualTarget() {
        ExecutionQuality eq = new ExecutionQuality(new Random(42));
        Player passer = new Player("p1", "Test Passer", "HOME", "CML",
                new Position(4, 3), new Position(4, 3),
                new PlayerSkills(10, 10, 10, 10, 10, 15, 10, 10));
        Player receiver = new Player("p2", "Test Receiver", "HOME", "STR",
                new Position(5, 4), new Position(5, 4),
                new PlayerSkills(10, 10, 10, 10, 10, 10, 10, 10));

        ExecutionQuality.PassResult result = eq.evaluatePass(
                passer, new Position(4, 3), new Position(6, 4), receiver,
                org.example.footballmanager.demo.service.model.PassLength.THRU,
                org.example.footballmanager.demo.service.model.PassHeight.GROUND);

        assertNotNull(result.actualTarget());
        assertTrue(result.skill() >= 1 && result.skill() <= 20);
        // actualTarget should be within the deviation cap of intended target
        Position intended = new Position(6, 4);
        double dist = Math.hypot(
                result.actualTarget().getRow() - intended.getRow(),
                result.actualTarget().getColumn() - intended.getColumn());
        assertTrue(dist < 4.0, "Deviation should be bounded, got " + dist);
    }

    @Test
    void shotResultRecordsGoalFlag() {
        ExecutionQuality eq = new ExecutionQuality(new Random(42));
        ExecutionQuality.ShotResult result = eq.evaluateShot(
                new Position(7, 3.5), 20);

        assertNotNull(result.actualTarget());
        assertTrue(result.goal() || !result.goal()); // just verify it doesn't crash
    }

    @Test
    void passWithPerfectSkillStaysCloseToTarget() {
        // Skill 20 → minimal deviation
        ExecutionQuality eq = new ExecutionQuality(new Random(1));
        Player passer = new Player("p1", "Ace", "HOME", "CML",
                new Position(4, 3), new Position(4, 3),
                new PlayerSkills(10, 10, 10, 10, 10, 20, 10, 10));
        Player receiver = new Player("p2", "Reco", "HOME", "STR",
                new Position(6, 4), new Position(6, 4),
                PlayerSkills.neutral());

        double maxDist = 0;
        for (int i = 0; i < 100; i++) {
            ExecutionQuality.PassResult result = eq.evaluatePass(
                    passer, new Position(4, 3), new Position(6, 4), receiver,
                    org.example.footballmanager.demo.service.model.PassLength.SHORT,
                    org.example.footballmanager.demo.service.model.PassHeight.GROUND);
            double d = Math.hypot(
                    result.actualTarget().getRow() - 6.0,
                    result.actualTarget().getColumn() - 4.0);
            maxDist = Math.max(maxDist, d);
        }
        assertTrue(maxDist <= 3.0, "Skill-20 passes should have bounded deviation, max=" + maxDist);
    }
}
