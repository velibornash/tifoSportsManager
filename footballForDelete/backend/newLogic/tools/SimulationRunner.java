package org.example.footballmanager.newLogic.tools;

import org.example.footballmanager.newLogic.engine.MatchMetrics;
import org.example.footballmanager.newLogic.engine.ZonePositionCalculator;
import org.example.footballmanager.newLogic.model.MatchResult;
import org.example.footballmanager.newLogic.model.TacticRules;
import org.example.footballmanager.newLogic.service.MatchOrchestrator;
import org.example.footballmanager.newLogic.store.MatchStore;
import org.example.footballmanager.newLogic.util.analysis.MatchAnalyzer;

import java.util.List;
import java.util.Map;

public class SimulationRunner {
    public static void main(String[] args) {
        MatchStore store = new MatchStore();
        MatchOrchestrator orch = new MatchOrchestrator(store);

        System.out.println("Starting synthetic match (SimulationRunner)");

        // Load default tactics with proper slot keys
        List<String> homeSlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        List<String> awaySlots = ZonePositionCalculator.buildSlotKeys("4-4-2", null);
        TacticRules homeTactics = TacticRules.createDefault(homeSlots);
        TacticRules awayTactics = TacticRules.createDefault(awaySlots);

        long id = orch.startMatch("Runner FC", "Opponents FC", homeTactics, homeSlots, awayTactics, awaySlots);
        System.out.println("Created matchId=" + id);

        MatchResult result = orch.simulate(id);
        System.out.println("Simulation finished. Building analysis...");

        Map<String, Object> metrics = MatchAnalyzer.analyze(orch.getMatch(id), orch.getResult(id));
        System.out.println("--- Analyzer Result ---");
        metrics.forEach((k,v) -> System.out.println(k + ": " + v));
        System.out.println("--- End ---");

        // Match Health HUD
        System.out.println("\n=== Match Health HUD ===");
        MatchMetrics matchMetrics = orch.getSimulator().getMetrics();
        if (matchMetrics != null) {
            System.out.println("Shots............." + matchMetrics.getShots());
            System.out.println("Shots On Target..." + matchMetrics.getShotsOnTarget());
            System.out.println("Goals............." + matchMetrics.getGoals());
            System.out.println("Passes............" + matchMetrics.getPasses());
            System.out.println("Carries..........." + matchMetrics.getCarries());
            System.out.println("Dribbles.........." + matchMetrics.getDribbles());
            System.out.println("Crosses..........." + matchMetrics.getCrosses());
            System.out.println("Through Balls....." + matchMetrics.getThroughBalls());
            System.out.println("Tackles..........." + matchMetrics.getTackles());
            System.out.println("Interceptions....." + matchMetrics.getInterceptions());
            System.out.println("Clearances........" + matchMetrics.getClearances());
            System.out.println("Fouls............." + matchMetrics.getFouls());
            System.out.println("Throw-ins........" + matchMetrics.getThrowIns());
            System.out.println("Corners..........." + matchMetrics.getCorners());
            System.out.println("Goal Kicks........" + matchMetrics.getGoalKicks());
            System.out.println("Offsides.........." + matchMetrics.getOffsides());
            System.out.println("Duels............." + matchMetrics.getDuels());

            // Warnings for unrealistic metrics
            System.out.println("\nWarnings:");
            if (matchMetrics.getShots() < 10) {
                System.out.println("⚠️  Very low shot count (" + matchMetrics.getShots() + ")");
            }
            if (matchMetrics.getThrowIns() < 15) {
                System.out.println("⚠️  Low throw-in count (" + matchMetrics.getThrowIns() + ")");
            }
            if (matchMetrics.getCorners() < 5) {
                System.out.println("⚠️  Low corner count (" + matchMetrics.getCorners() + ")");
            }
            if (matchMetrics.getGoalKicks() < 10) {
                System.out.println("⚠️  Low goal kick count (" + matchMetrics.getGoalKicks() + ")");
            }
            if (matchMetrics.getCarries() > matchMetrics.getPasses()) {
                System.out.println("⚠️  More carries than passes - unrealistic");
            }
            if (matchMetrics.getDribbles() == 0) {
                System.out.println("⚠️  No dribbles detected");
            }
        }
        System.out.println("========================\n");
    }
}
