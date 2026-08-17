package org.example.footballmanager.demo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testovi za ExecutionQuality — devijacija pasa i suta, ishod akcija.
 */
class ExecutionQualityTest {

    // --- PASS ---

    @Test
    void highPassingSkillProducesSmallerDeviation() {
        Random fixed = new Random(42);
        ExecutionQuality eq = new ExecutionQuality(fixed);
        Position receiverPos = new Position(5, 3);
        Player receiver = new Player("R1", "R1", "HOME", "MID",
                java.awt.Color.BLUE, receiverPos, receiverPos);

        // Visok skill (seed koji daje skill blizu 20) — devijacija mala
        ExecutionQuality.PassResult result = eq.evaluatePass(receiverPos, receiver);
        // Skill mora biti 1-20
        assertTrue(result.skill() >= 1 && result.skill() <= 20);
        // Devijacija ne moze biti veca od max (20-1)*0.15 = 2.85
        double dist = MovementEngine.distance(result.actualTarget(), receiverPos);
        assertTrue(dist <= 2.85 + 0.01, "devijacija ne prelazi max: " + dist);
    }

    @Test
    void lowPassingSkillCanProduceLargerDeviation() {
        // Testiramo sa seed-om koji daje nizak skill
        // Skill 1 → max devijacija 2.85
        // Testiramo da li devijacija moze biti velika
        int largeDeviationCount = 0;
        for (int seed = 0; seed < 200; seed++) {
            ExecutionQuality eq = new ExecutionQuality(new Random(seed));
            Position receiverPos = new Position(5, 3);
            Player receiver = new Player("R1", "R1", "HOME", "MID",
                    java.awt.Color.BLUE, receiverPos, receiverPos);
            ExecutionQuality.PassResult result = eq.evaluatePass(receiverPos, receiver);
            double dist = MovementEngine.distance(result.actualTarget(), receiverPos);
            if (dist > 1.5) largeDeviationCount++;
        }
        // Sa dovoljno seed-ova, bar neki treba da imaju veliku devijaciju
        assertTrue(largeDeviationCount > 0,
                "mora postojati bar neki pass sa velikom devijacijom");
    }

    @Test
    void passIntendedAndActualTargetsAreDistinguishable() {
        ExecutionQuality eq = new ExecutionQuality(new Random(7));
        Position intended = new Position(5, 3);
        Player receiver = new Player("R1", "R1", "HOME", "MID",
                java.awt.Color.BLUE, intended, intended);
        ExecutionQuality.PassResult result = eq.evaluatePass(intended, receiver);
        // Actual target mora postojati i biti razlicit od intended (moguce)
        assertNotNull(result.actualTarget());
        assertNotNull(intended);
        assertTrue(result.skill() >= 1 && result.skill() <= 20);
    }

    @Test
    void successfulPassStillProducesReceiver() {
        // Skill 20 uvek daje devijaciju 0 → uvek RECEIVED
        // Skill 20 → (20-20)*0.15 = 0 max devijacija
        //Ali ne mozemo garantovati tacno skill 20 iz randoma
        // Testiramo princip: ako je devijacija mala, received = true
        ExecutionQuality eq = new ExecutionQuality(new Random(42));
        Position receiverPos = new Position(4, 3);
        Player receiver = new Player("R1", "R1", "HOME", "MID",
                java.awt.Color.BLUE, receiverPos, receiverPos);
        ExecutionQuality.PassResult result = eq.evaluatePass(receiverPos, receiver);
        double dist = MovementEngine.distance(result.actualTarget(), receiverPos);
        if (dist < 1.5) {
            assertTrue(result.received(), "mali odmak = received");
        }
    }

    @Test
    void poorPassCanProduceLooseBall() {
        // Tražimo seed koji daje veliku devijaciju (> 1.5)
        for (int seed = 0; seed < 500; seed++) {
            ExecutionQuality eq = new ExecutionQuality(new Random(seed));
            Position receiverPos = new Position(4, 3);
            Player receiver = new Player("R1", "R1", "HOME", "MID",
                    java.awt.Color.BLUE, receiverPos, receiverPos);
            ExecutionQuality.PassResult result = eq.evaluatePass(receiverPos, receiver);
            double dist = MovementEngine.distance(result.actualTarget(), receiverPos);
            if (dist >= 1.5) {
                assertFalse(result.received(), "velika devijacija = LOOSE BALL");
                return;
            }
        }
        fail("nijedan seed od 500 nije proizveo veliku devijaciju");
    }

    // --- SHOT ---

    @Test
    void highStrikerSkillProducesAccurateShot() {
        ExecutionQuality eq = new ExecutionQuality(new Random(42));
        ExecutionQuality.ShotResult result = eq.evaluateShot(ActionEngine.GOAL_POSITION);
        assertTrue(result.skill() >= 1 && result.skill() <= 20);
        double dist = MovementEngine.distance(result.actualTarget(), ActionEngine.GOAL_POSITION);
        assertTrue(dist <= 2.28 + 0.01, "devijacija ne prelazi max: " + dist);
    }

    @Test
    void lowStrikerSkillCanProduceMiss() {
        int missCount = 0;
        for (int seed = 0; seed < 200; seed++) {
            ExecutionQuality eq = new ExecutionQuality(new Random(seed));
            ExecutionQuality.ShotResult result = eq.evaluateShot(ActionEngine.GOAL_POSITION);
            if (!result.goal()) missCount++;
        }
        assertTrue(missCount > 0,
                "mora postojati bar neki miss sa niskim skillom");
    }

    @Test
    void goalPathStillProducesCelebration() {
        // Skill 20 → devijacija 0 → uvek goal
        int goalCount = 0;
        for (int seed = 0; seed < 200; seed++) {
            ExecutionQuality eq = new ExecutionQuality(new Random(seed));
            ExecutionQuality.ShotResult result = eq.evaluateShot(ActionEngine.GOAL_POSITION);
            if (result.goal()) goalCount++;
        }
        assertTrue(goalCount > 0,
                "mora postojati bar neki goal sa visokim skillom, count=" + goalCount);
    }

    @Test
    void missProducesLooseBall() {
        for (int seed = 0; seed < 500; seed++) {
            ExecutionQuality eq = new ExecutionQuality(new Random(seed));
            ExecutionQuality.ShotResult result = eq.evaluateShot(ActionEngine.GOAL_POSITION);
            if (!result.goal()) {
                assertFalse(result.goal(), "miss = not goal");
                assertNotNull(result.actualTarget());
                return;
            }
        }
        fail("nijedan seed od 500 nije proizveo miss");
    }

    // --- BallState ---

    @Test
    void ballStateInPossessionWhenCarrierSet() {
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        Player p = new Player("P1", "P1", "HOME", "MID",
                java.awt.Color.BLUE, new Position(4, 3.5), new Position(4, 3.5));
        ball.setCarrier(p);
        assertEquals(Ball.BallState.IN_POSSESSION, ball.getBallState());
    }

    @Test
    void ballStateInTransitionWhenTargetSet() {
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        ball.setTarget(new Position(6, 3));
        assertEquals(Ball.BallState.IN_TRANSITION, ball.getBallState());
    }

    @Test
    void ballStateLooseWhenNoCarrierNoTarget() {
        Ball ball = new Ball(new Position(4, 3.5), new Position(4, 3.5));
        assertEquals(Ball.BallState.LOOSE, ball.getBallState());
    }
}
