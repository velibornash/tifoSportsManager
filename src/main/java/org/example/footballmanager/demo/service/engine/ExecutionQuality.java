package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.*;

import java.util.Random;

/**
 * Execution quality for PASS and SHOT actions.
 * Identical logic to demo/ExecutionQuality but using service model.
 */
public class ExecutionQuality {

    private static final double PASS_DEVIATION_PER_SKILL_POINT = 0.10;
    private static final double SHOT_DEVIATION_PER_SKILL_POINT = 0.22;
    private static final double PASS_SUCCESS_THRESHOLD = 1.5;
    public static final double THRU_SUCCESS_THRESHOLD = 2.0;
    public static final double SHOT_GOAL_THRESHOLD = 0.25;

    private final Random random;

    public ExecutionQuality(Random random) {
        this.random = random;
    }

    public PassResult evaluatePass(Player passer, Position origin, Position intendedTarget,
                                   Player receiver, PassLength passLength, PassHeight passHeight) {
        int skill = Math.max(1, Math.min(20, (int) Math.round(passer.getSkills().passing())));
        return evaluatePassWithSkill(skill, origin, intendedTarget, receiver, passLength, passHeight);
    }

    public PassResult evaluatePass(Player passer, Position origin, Position intendedTarget, Player receiver) {
        return evaluatePass(passer, origin, intendedTarget, receiver, PassLength.SHORT, PassHeight.GROUND);
    }

    private PassResult evaluatePassWithSkill(int skill, Position origin, Position intendedTarget,
                                              Player receiver, PassLength passLength, PassHeight passHeight) {
        double dx = intendedTarget.getColumn() - origin.getColumn();
        double dy = intendedTarget.getRow() - origin.getRow();
        double length = Math.hypot(dx, dy);

        double lengthMultiplier = switch (passLength) {
            case SHORT -> 0.6;
            case LONG -> 0.8;
            case THRU -> 0.9;
        };

        // AIR passes are EASIER to execute (less deviation) — they fly over obstacles.
        // GROUND passes are harder — they must navigate through traffic.
        double heightMultiplier = passHeight == PassHeight.AIR ? 0.7 : 1.0;

        double maxDeviation = Math.min(
                (20 - skill) * PASS_DEVIATION_PER_SKILL_POINT * lengthMultiplier * heightMultiplier,
                Math.max(0.15, length * 0.30));

        double dirRow = length < 1e-9 ? 0 : dy / length;
        double dirCol = length < 1e-9 ? 1 : dx / length;
        double sideRow = -dirCol;
        double sideCol = dirRow;

        double longitudinal = (random.nextDouble() * 2 - 1) * maxDeviation;
        // Lateral deviation 3.5x — pushes passes past the sideline for throw-ins (15-25/match).
        double lateral = (random.nextDouble() * 2 - 1) * maxDeviation * 3.5;

        double actualRow = intendedTarget.getRow() + dirRow * longitudinal + sideRow * lateral;
        double actualCol = intendedTarget.getColumn() + dirCol * longitudinal + sideCol * lateral;
        // Allow ball to land OUTSIDE pitch boundaries for restarts.
        // Row clamp extends to 0.0-8.0 so determineRestart can detect end-line exits.
        // Column clamp extends to -0.5-8.5 so determineRestart can detect sideline exits.
        // determineRestart thresholds (0.5/7.5) ensure only balls CLEARLY past the line
        // trigger restarts — not balls landing just inside the playing area.
        Position actualTarget = new Position(
                SimUtils.clamp(actualRow, 0.0, 8.0),
                SimUtils.clamp(actualCol, -0.5, 8.5));

        boolean received;
        if (passLength == PassLength.THRU) {
            received = SimUtils.distance(actualTarget, receiver.getPosition()) < THRU_SUCCESS_THRESHOLD;
        } else {
            received = SimUtils.distance(actualTarget, receiver.getPosition()) < PASS_SUCCESS_THRESHOLD;
        }

        return new PassResult(skill, actualTarget, received, passLength, passHeight);
    }

    public ShotResult evaluateShot(Position goalPosition, int carrierStrikerSkill) {
        int skill = Math.max(1, Math.min(20, carrierStrikerSkill));
        double maxDeviation = (20 - skill) * SHOT_DEVIATION_PER_SKILL_POINT;
        double deviation = random.nextDouble() * maxDeviation;
        double angle = random.nextDouble() * 2 * Math.PI;

        double actualRow = goalPosition.getRow() + Math.sin(angle) * deviation;
        double actualCol = goalPosition.getColumn() + Math.cos(angle) * deviation;
        Position actualTarget = new Position(
                SimUtils.clamp(actualRow, 0, 8),
                SimUtils.clamp(actualCol, 1, 6));

        double distance = SimUtils.distance(actualTarget, goalPosition);
        boolean goal = distance < SHOT_GOAL_THRESHOLD;

        return new ShotResult(skill, actualTarget, goal);
    }

    public record PassResult(
            int skill,
            Position actualTarget,
            boolean received,
            PassLength passLength,
            PassHeight passHeight
    ) {
        public PassResult(int skill, Position actualTarget, boolean received) {
            this(skill, actualTarget, received, PassLength.SHORT, PassHeight.GROUND);
        }
    }

    public record ShotResult(int skill, Position actualTarget, boolean goal) {}
}
