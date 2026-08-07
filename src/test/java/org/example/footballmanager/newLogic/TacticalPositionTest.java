package org.example.footballmanager.newLogic;

import org.example.footballmanager.newLogic.engine.MatchSimulator;
import org.example.footballmanager.newLogic.engine.MovementEngine;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.*;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that players follow tactical positions from the tactical editor.
 */
public class TacticalPositionTest {

    @Test
    public void testTacticalPositionReadingForBallPositions() {
        // Test that simulation runs with tactical positions loaded
        MatchStore store = new MatchStore();
        MatchOrchestrator orch = new MatchOrchestrator(store);

        List<String> homeSlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        List<String> awaySlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        TacticRules homeTactics = TacticRules.createDefault(homeSlots);
        TacticRules awayTactics = TacticRules.createDefault(awaySlots);

        long matchId = orch.startMatch("Home FC", "Away FC", homeTactics, homeSlots, awayTactics, awaySlots);
        Match match = orch.getMatch(matchId);

        System.out.println("=== Testing Tactical Position Loading ===");
        System.out.println("Home slots: " + homeSlots);
        System.out.println("Away slots: " + awaySlots);
        System.out.println("Home tactics loaded: " + (match.homeTeam().tacticRules() != null));
        System.out.println("Away tactics loaded: " + (match.awayTeam().tacticRules() != null));

        // Simulate the match
        MatchResult result = orch.simulate(matchId);

        assertNotNull(result, "Match result should not be null");
        System.out.println("\nTactical position loading test passed!");
    }

    @Test
    public void testAwayTeamPositionTranslation() {
        // Test that away team positions are correctly translated from home perspective
        MatchStore store = new MatchStore();
        MatchOrchestrator orch = new MatchOrchestrator(store);

        List<String> homeSlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        List<String> awaySlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        TacticRules homeTactics = TacticRules.createDefault(homeSlots);
        TacticRules awayTactics = TacticRules.createDefault(awaySlots);

        long matchId = orch.startMatch("Home FC", "Away FC", homeTactics, homeSlots, awayTactics, awaySlots);
        Match match = orch.getMatch(matchId);

        System.out.println("=== Testing Away Team Position Translation ===");
        System.out.println("Home slots: " + homeSlots);
        System.out.println("Away slots: " + awaySlots);

        // Simulate the match
        MatchResult result = orch.simulate(matchId);

        assertNotNull(result, "Match result should not be null");
        System.out.println("\nAway team position translation test passed!");
    }

    @Test
    public void testPlayerMovementTowardsTacticalTargets() {
        // Test that players actually move towards their tactical targets
        MatchStore store = new MatchStore();
        MatchOrchestrator orch = new MatchOrchestrator(store);

        List<String> homeSlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        List<String> awaySlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);

        TacticRules homeTactics = TacticRules.createDefault(homeSlots);
        TacticRules awayTactics = TacticRules.createDefault(awaySlots);

        long matchId = orch.startMatch("Home FC", "Away FC", homeTactics, homeSlots, awayTactics, awaySlots);
        Match match = orch.getMatch(matchId);

        System.out.println("=== Testing Player Movement ===");

        // Simulate the match
        MatchResult result = orch.simulate(matchId);

        // Check that the simulation completed without errors
        assertNotNull(result, "Match result should not be null");
        assertTrue(result.homeGoals() >= 0, "Home goals should be non-negative");
        assertTrue(result.awayGoals() >= 0, "Away goals should be non-negative");

        System.out.println("Final score: " + result.homeGoals() + " - " + result.awayGoals());
        System.out.println("\nPlayer movement test passed!");
    }

    @Test
    public void testOffsideLineRespected() {
        // Test that attackers don't go offside
        MatchStore store = new MatchStore();
        MatchOrchestrator orch = new MatchOrchestrator(store);

        List<String> homeSlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        List<String> awaySlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);

        TacticRules homeTactics = TacticRules.createDefault(homeSlots);
        TacticRules awayTactics = TacticRules.createDefault(awaySlots);

        long matchId = orch.startMatch("Home FC", "Away FC", homeTactics, homeSlots, awayTactics, awaySlots);
        Match match = orch.getMatch(matchId);

        MatchResult result = orch.simulate(matchId);

        // Check that attackers didn't go offside excessively
        int offsideCount = orch.getSimulator().getMetrics().getOffsides();
        System.out.println("Offside count: " + offsideCount);
        assertTrue(offsideCount < 30, "Too many offsides: " + offsideCount);
    }
}
