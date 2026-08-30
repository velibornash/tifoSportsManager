package org.example.footballmanager.demo.service;

import org.example.footballmanager.demo.service.engine.ActionEngine;
import org.example.footballmanager.demo.service.engine.ExecutionQuality;
import org.example.footballmanager.demo.service.engine.PlayerSelectionEngine;
import org.example.footballmanager.demo.service.model.Ball;
import org.example.footballmanager.demo.service.model.Player;
import org.example.footballmanager.demo.service.model.PlayerSkills;
import org.example.footballmanager.demo.service.model.Position;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.tactics.TacticsRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controlled shot-quality test (user request): attacker placed in the shot zone
 * (last two rows), goalkeeper positioned left/centre/right in front of goal (and
 * one "out of goal" case), no defenders. For 1000 shots varying striker skill
 * 10-20, measure on-target, goal and save rates.
 *
 * Drives the real ActionEngine.executeShot() and replicates the exact
 * handleShotArrival() resolution (on-target = actualTarget within 0.5 of goal;
 * save vs goal when actualTarget within SHOT_GOAL_THRESHOLD of goal).
 */
class ShotQualityTest {

    private static final double SHOT_GOAL_THRESHOLD = ExecutionQuality.SHOT_GOAL_THRESHOLD; // 0.40

    private static final class Outcome {
        int total, onTarget, nearGoal, goal, save, miss;
    }

    private Player attacker;
    private Player keeper;
    private MatchState state;
    private ActionEngine engine;

    private void setup(Position attackerPos, Position keeperPos, int strikerSkill, int keeperSkill, long seed) {
        Random random = new Random(seed);
        PlayerSkills strikerSkills = new PlayerSkills(
                10, 10, 10, 10, 10, 10, strikerSkill, 10);
        attacker = new Player("H-ST", "Home ST", "HOME", "STL",
                attackerPos, attackerPos, strikerSkills);
        PlayerSkills keeperSkills = new PlayerSkills(
                10, 10, keeperSkill, 10, 10, 10, 10, 10);
        keeper = new Player("A-GK", "Away GK", "AWAY", "GK",
                keeperPos, keeperPos, keeperSkills);

        List<Player> players = new ArrayList<>();
        players.add(attacker);
        players.add(keeper);
        Ball ball = new Ball(attackerPos, attackerPos);
        TacticsRules tactics = new TacticsRules();
        MatchRecorder recorder = new MatchRecorder();
        state = new MatchState(players, ball, tactics, random, recorder);
        state.setCarrier(attacker);
        PlayerSelectionEngine selection = new PlayerSelectionEngine(state);
        ExecutionQuality eq = new ExecutionQuality(random);
        engine = new ActionEngine(state, selection, eq, recorder);
    }

    private Outcome runShots(Position attackerPos, Position keeperPos, int keeperSkill, long seedBase) {
        Outcome o = new Outcome();
        Random rng = new Random(seedBase);
        for (int i = 0; i < 1000; i++) {
            int strikerSkill = 10 + rng.nextInt(11); // vary 10..20
            long shotSeed = rng.nextLong();
            setup(attackerPos, keeperPos, strikerSkill, keeperSkill, shotSeed);
            boolean inFlight = engine.executeShot();
            if (!inFlight) {
                o.miss++; // blocked/edge case — no defenders here, so shouldn't happen
                o.total++;
                continue;
            }
            Position actualTarget = state.getAction().getActualTarget();
            Position goal = ActionEngine.goalPositionFor("HOME");
            double distToGoal = org.example.footballmanager.demo.service.engine.SimUtils
                    .distance(actualTarget, goal);
            o.total++;
            boolean onTarget = distToGoal < 0.5;
            if (onTarget) o.onTarget++;
            if (distToGoal < SHOT_GOAL_THRESHOLD) {
                o.nearGoal++;
                // Replicate handleShotArrival save check (new model)
                Player gk = new PlayerSelectionEngine(state).anyGoalkeeper("AWAY");
                if (gk != null) {
                    double gkInLane = Math.max(0.05, state.getAction().getGkInLane());
                    double shotSkill = state.getAction().getSkill();
                    double angleFactor = state.getAction().getAngleFactor();
                    double saveChance = 0.30 + gk.getSkills().keeper() / 20.0 * 0.35;
                    saveChance *= (1.0 - shotSkill / 20.0 * 0.25);
                    saveChance *= 0.80 + (1.0 - angleFactor) * 0.9;
                    saveChance *= gkInLane;
                    double keeperDist = org.example.footballmanager.demo.service.engine.SimUtils
                            .distance(gk.getPosition(), actualTarget);
                    saveChance *= Math.max(0.35, 1.0 - Math.max(0.0, keeperDist - 1.0) * 0.45);
                    saveChance = org.example.footballmanager.demo.service.engine.SimUtils
                            .clamp(saveChance, 0.03, 0.85);
                    if (new Random(shotSeed + 999_999).nextDouble() < saveChance) {
                        o.save++;
                        continue;
                    }
                }
                o.goal++;
            } else {
                o.miss++;
            }
        }
        return o;
    }

    @Test
    void shotQualityWithAttackerInShotZoneKeeperInGoal() {
        // Attacker central ~10-16m out (row 6.0, central col 3.5), keeper central in goal
        Outcome o = runShots(new Position(6.0, 3.5), new Position(7.0, 3.5), 15, 5000L);
        print("KEEPER CENTRE IN GOAL, attacker row6 central", o);
    }

    @Test
    void shotQualityKeeperLeftPost() {
        Outcome o = runShots(new Position(6.0, 3.5), new Position(7.0, 2.2), 15, 6000L);
        print("KEEPER LEFT POST, attacker row6 central", o);
    }

    @Test
    void shotQualityKeeperRightPost() {
        Outcome o = runShots(new Position(6.0, 3.5), new Position(7.0, 4.8), 15, 7000L);
        print("KEEPER RIGHT POST, attacker row6 central", o);
    }

    @Test
    void shotQualityKeeperOutOfGoal() {
        // User's complaint case: keeper out of goal, attacker ~10m — must stay on goal
        Outcome o = runShots(new Position(6.0, 3.5), new Position(6.5, 1.8), 15, 8000L);
        print("KEEPER OUT OF GOAL (6.5,1.8), attacker row6 central (must stay on frame)", o);
    }

    @Test
    void shotQualityKeeperFarOutOfShotLane() {
        // User's key case: keeper clearly AWAY from the shot lane, nobody on the
        // path to goal — the ball must go in (save ~ 0), NOT get "saved".
        Outcome o = runShots(new Position(6.0, 3.5), new Position(6.0, 1.2), 15, 8500L);
        print("KEEPER FAR OUT OF LANE (6.0,1.2), nobody on path -> must be GOAL", o);
    }

    @Test
    void shotQualitySharperAngle() {
        // Winger attacker from the flank (acute angle), keeper centre
        Outcome o = runShots(new Position(6.2, 2.0), new Position(7.0, 3.5), 15, 9000L);
        print("KEEPER CENTRE, attacker ANGLE (6.2,2.0)", o);
    }

    private void print(String label, Outcome o) {
        double onTargetPct = 100.0 * o.onTarget / Math.max(1, o.total);
        double goalPct = 100.0 * o.goal / Math.max(1, o.total);
        double savePct = 100.0 * o.save / Math.max(1, o.total);
        double missPct = 100.0 * o.miss / Math.max(1, o.total);
        System.out.printf("%n=== %s ===%n", label);
        System.out.printf("  Total shots: %d%n", o.total);
        System.out.printf("  On target (dist<0.5): %5.1f%%   (%d)%n", onTargetPct, o.onTarget);
        System.out.printf("  Goal:  %5.1f%%   (%d)%n", goalPct, o.goal);
        System.out.printf("  Save:  %5.1f%%   (%d)%n", savePct, o.save);
        System.out.printf("  Miss (whole goal): %5.1f%%   (%d)%n", missPct, o.miss);
        System.out.printf("  NearGoal(<=0.40): %d / %d%n", o.nearGoal, o.total);
    }
}
