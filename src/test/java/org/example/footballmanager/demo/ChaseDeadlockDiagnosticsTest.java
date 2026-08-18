package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Diagnostic full-match run for chase-deadlock verification report. */
class ChaseDeadlockDiagnosticsTest {

    @Test
    void seed7FullMatchGameplayCounters() {
        SimulationEngine engine = new SimulationEngine(
                TacticalGridDemo.createPlayers(new Random(7)),
                new Ball(new Position(4, 3.5), new Position(4, 3.5)),
                TacticsRules.defaults(),
                new Random(7));
        engine.startMatchSimulation();

        int maxTicks = (SimulationState.REGULATION_MINUTES + SimulationState.EXTRA_TIME_MINUTES)
                * SimulationState.MATCH_TICKS_PER_MINUTE * 3;

        for (int tick = 0; tick < maxTicks && !engine.isMatchFinished(); tick++) {
            if (engine.isHalfTime()) engine.startSecondHalf();
            if (engine.isCelebrating()) engine.reset();
            if (engine.isRoundComplete() && !engine.isCelebrating() && !engine.isHalfTime()) {
                engine.step();
            }
            engine.advance();
        }

        assertTrue(engine.isMatchFinished());

        int passAttempts = engine.getPassAttempts() + engine.getAwayPassAttempts();
        int passCompletions = engine.getPassCompletions() + engine.getAwayPassCompletions();
        double passCompletionRate = passAttempts == 0 ? 0.0
                : 100.0 * passCompletions / passAttempts;

        System.out.println("=== FULL MATCH RESULT (seed=7) ===");
        System.out.println("Score HOME " + engine.getGoalCount() + " - " + engine.getAwayGoalCount() + " AWAY");
        System.out.println("Clock: " + engine.getMatchClockLabel());
        System.out.println("Actions: " + engine.getActionCount());
        System.out.println("Passes attempted: " + passAttempts);
        System.out.println("Passes completed: " + passCompletions);
        System.out.printf("Pass completion rate: %.1f%%%n", passCompletionRate);
        System.out.println("Shots: " + engine.getShotCount());
        System.out.println("Shots on target: " + engine.getShotsOnTarget() + engine.getAwayShotsOnTarget());
        System.out.println("Goals: " + (engine.getGoalCount() + engine.getAwayGoalCount()));
        System.out.println("Chases: " + engine.getChaseCount());
        System.out.println("Chase resolutions: " + engine.getChaseResolutionCount());
        System.out.println("Chase timeouts: " + engine.getChaseTimeoutCount());
        System.out.println("Match finished: " + engine.isMatchFinished());
    }
}
