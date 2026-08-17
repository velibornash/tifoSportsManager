package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DuelResolutionTest {

    @Test
    void resolverUsesRelevantSkillAndSmallRandom() {
        Player attacker = player("A", "HOME", new Position(4, 3),
                new PlayerSkills(2.0, 1, 1, 1, 1, 2.0, 1, 1));
        Player defender = player("D", "AWAY", new Position(4, 3.4),
                new PlayerSkills(1.0, 1, 1, 1, 1, 1.0, 1, 1));
        Duel duel = new Duel(attacker, defender, attacker.getPosition(), DuelType.DRIBBLE);

        DuelResult result = new DuelResolver(new Random(1)).resolve(duel);

        assertSame(attacker, result.winner());
        assertEquals(DuelOutcome.ATTACKER_WINS, result.outcome());
        assertSame(attacker, result.possession());
        assertEquals(Ball.BallState.IN_POSSESSION, result.ballState());
    }

    @Test
    void detectionUsesContinuousRadiusAndDoesNotDuplicateActiveDuel() {
        Player attacker = player("A", "HOME", new Position(4, 3), PlayerSkills.neutral());
        Player defender = player("D", "AWAY", new Position(4, 3.49), PlayerSkills.neutral());
        SimulationState state = state(attacker, defender);
        Action action = new Action(Action.Type.CARRY, attacker);
        state.setAction(action);
        DuelEngine engine = new DuelEngine(state, 0.5);

        engine.update(action);
        Duel first = engine.getActiveDuel();
        engine.update(action);

        assertSame(first, engine.getActiveDuel());
        assertEquals(DuelType.DRIBBLE, first.getType());
        defender.setPosition(new Position(4, 4));
        engine.update(action);
        assertNull(engine.getActiveDuel());
    }

    @Test
    void sameTeamPlayerIsNeverAContestant() {
        Player attacker = player("A", "HOME", new Position(4, 3), PlayerSkills.neutral());
        Player teammate = player("T", "HOME", new Position(4, 3.2), PlayerSkills.neutral());
        SimulationState state = state(attacker, teammate);
        Action action = new Action(Action.Type.CARRY, attacker);
        DuelEngine engine = new DuelEngine(state);

        engine.update(action);

        assertNull(engine.getActiveDuel());
    }

    @Test
    void receiveDuelWinnerCanBecomeCarrier() {
        Player passer = player("P", "HOME", new Position(3, 2), PlayerSkills.neutral());
        Player receiver = player("R", "HOME", new Position(3, 3),
                new PlayerSkills(1, 1, 1, 1, 1, 1, 2.0, 1));
        Player defender = player("D", "AWAY", new Position(3, 3.4), PlayerSkills.neutral());
        SimulationState state = state(passer, receiver, defender);
        state.setCarrier(passer);
        state.getBall().setCarrier(null);
        Action action = new Action(Action.Type.PASS, passer);
        action.setTargetPlayer(receiver);
        state.setAction(action);
        DuelEngine engine = new DuelEngine(state, 0.5);
        engine.update(action);

        DuelResult result = engine.resolveActiveDuel(new DuelResolver(new Random(4)));

        assertEquals(DuelOutcome.ATTACKER_WINS, result.outcome());
        state.getBall().setCarrier(result.winner());
        state.setCarrier(result.winner());
        assertSame(receiver, state.getBall().getCarrier());
        assertSame(receiver, state.getCarrier());
    }

    private static SimulationState state(Player... players) {
        Ball ball = new Ball(new Position(4, 3), new Position(4, 3));
        return new SimulationState(List.of(players), ball, TacticsRules.defaults(), new Random(2));
    }

    private static Player player(String label, String team, Position position, PlayerSkills skills) {
        return new Player(label, label, team, "ST", Color.BLUE, position, position, skills);
    }
}
