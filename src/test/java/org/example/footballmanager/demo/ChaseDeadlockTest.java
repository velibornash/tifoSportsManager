package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for loose-ball CHASE lifecycle deadlocks.
 */
class ChaseDeadlockTest {

    @Test
    void shouldResolveChaseWhenPlayersAreAlreadyCloseToBall() {
        SimulationEngine engine = engineWithConvergedChasers();
        engine.step();
        assertNotNull(engine.getCurrentAction());
        assertTrue(engine.getCurrentAction().getType() == Action.Type.CHASE);

        int ticks = 0;
        while (engine.isActionInProgress() && ticks < 200) {
            engine.advance();
            ticks++;
        }

        assertFalse(engine.isActionInProgress(),
                "CHASE must resolve when chasers are already near the ball, ticks=" + ticks);
        assertNotNull(engine.getCarrier(), "winner must receive possession");
        assertTrue(engine.isRoundComplete(), "round must complete after chase resolution");
    }

    @Test
    void shouldNotDeadlockWhenChasersConvergeOnLooseBall() {
        SimulationEngine engine = engineWithConvergedChasers();
        engine.step();

        int ticks = 0;
        while (engine.isActionInProgress() && ticks < 500) {
            engine.advance();
            ticks++;
        }

        assertFalse(engine.isActionInProgress(), "converged chasers must not deadlock");

        engine.step();
        assertTrue(engine.isActionInProgress() || engine.isRoundComplete(),
                "next football action must be schedulable after chase");
    }

    @Test
    void fullMatchReachesFinalWhistleWithoutChaseDeadlock() {
        SimulationEngine engine = new SimulationEngine(
                TacticalGridDemo.createPlayers(new Random(7)),
                new Ball(new Position(4, 3.5), new Position(4, 3.5)),
                TacticsRules.defaults(),
                new Random(7));
        engine.startMatchSimulation();

        int maxTicks = (SimulationState.REGULATION_MINUTES + SimulationState.EXTRA_TIME_MINUTES)
                * SimulationState.MATCH_TICKS_PER_MINUTE * 3;
        int stuckChaseTicks = 0;
        Action.Type lastActionType = null;

        for (int tick = 0; tick < maxTicks && !engine.isMatchFinished(); tick++) {
            if (engine.isHalfTime()) {
                engine.startSecondHalf();
            }
            if (engine.isCelebrating()) {
                engine.reset();
            }
            if (engine.isRoundComplete() && !engine.isCelebrating() && !engine.isHalfTime()) {
                engine.step();
            }
            engine.advance();

            Action current = engine.getCurrentAction();
            if (current != null && current.getType() == Action.Type.CHASE) {
                if (lastActionType == Action.Type.CHASE) {
                    stuckChaseTicks++;
                } else {
                    stuckChaseTicks = 0;
                }
                assertTrue(current.getChaseTicks() < ActionEngine.CHASE_MAX_TICKS,
                        "CHASE must not exceed safety bound");
                assertTrue(stuckChaseTicks < ActionEngine.CHASE_MAX_TICKS,
                        "same CHASE action must not run forever");
            } else {
                stuckChaseTicks = 0;
            }
            lastActionType = current == null ? null : current.getType();
        }

        assertTrue(engine.isMatchFinished(), "full match must reach final whistle");
        assertTrue(engine.getChaseCount() > 0, "match must contain at least one CHASE");
    }

    private static SimulationEngine engineWithConvergedChasers() {
        List<Player> players = TacticalGridDemo.createPlayers(new Random(99));
        Position ballPos = new Position(4.0, 3.5);
        Ball ball = new Ball(ballPos, ballPos);

        Player home = players.stream()
                .filter(p -> SimulationState.TEAM_HOME.equals(p.getTeam()))
                .filter(p -> "HDL".equals(p.getLabel()))
                .findFirst()
                .orElseThrow();
        Player away = players.stream()
                .filter(p -> "AWAY".equals(p.getTeam()))
                .filter(p -> "ASTR".equals(p.getLabel()))
                .findFirst()
                .orElseThrow();

        home.setPosition(new Position(4.0, 3.2));
        away.setPosition(new Position(4.0, 3.8));
        home.setTarget(null);
        away.setTarget(null);

        SimulationEngine engine = new SimulationEngine(players, ball, TacticsRules.defaults(), new Random(99));
        engine.startMatchSimulation();
        return engine;
    }
}
