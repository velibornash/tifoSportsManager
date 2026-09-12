package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;
import java.util.Random;

/**
 * Execution quality - determines if a pass/shot succeeds based on skills.
 * 
 * CORE PRINCIPLE: Only calculates execution quality. Does NOT override decisions.
 */
public class ExecutionQuality {

    public static class PassResult {
        private final Position actualTarget;
        private final boolean received;
        private final double speed;
        private final int skill;
        private final double deviation;

        public PassResult(Position actualTarget, boolean received,
                            double speed,
                            int skill, double deviation) {
            this.actualTarget = actualTarget;
            this.received = received;
            this.speed = speed;
            this.skill = skill;
            this.deviation = deviation;
        }

        public Position getActualTarget() { return actualTarget; }
        public boolean isReceived() { return received; }
        public double getSpeed() { return speed; }
        public int getSkill() { return skill; }
        public double getDeviation() { return deviation; }
    }

    public static class ShotResult {
        private final Position actualTarget;
        private final boolean onTarget;
        private final boolean saved;
        private final boolean goal;
        private final double speed;

        public ShotResult(Position actualTarget, boolean onTarget,
                            boolean saved, boolean goal, double speed) {
            this.actualTarget = actualTarget;
            this.onTarget = onTarget;
            this.saved = saved;
            this.goal = goal;
            this.speed = speed;
        }

        public Position getActualTarget() { return actualTarget; }
        public boolean isOnTarget() { return onTarget; }
        public boolean isSaved() { return saved; }
        public boolean isGoal() { return goal; }
        public double getSpeed() { return speed; }
    }

    /**
     * Evaluate a pass based on passer skill and desired speed.
     */
    public static PassResult evaluatePass(Player passer, Position origin,
                                            Position intendedTarget,
                                            Player receiver,
                                            double desiredSpeed) {
        int skill = (int) passer.getSkills().passing();
        double maxSpeedForSkill = ballSpeedForSkill(skill);
        double overspeed = Math.max(0.0, desiredSpeed - maxSpeedForSkill);
        double maxDeviation = 0.02 + overspeed * 2.0;

        // Calculate actual target with deviation
        double dx = intendedTarget.getColumn() - origin.getColumn();
        double dy = intendedTarget.getRow() - origin.getRow();
        double length = Math.sqrt(dx * dx + dy * dy);

        double dirRow = length < 1e-9 ? 0 : dy / length;
        double dirCol = length < 1e-9 ? 1 : dx / length;
        double sideRow = -dirCol;
        double sideCol = dirRow;

        double longitudinal = (Math.random() * 2 - 1) * maxDeviation;
        double lateral = (Math.random() * 2 - 1) * maxDeviation * 2.5;

        double actualRow = intendedTarget.getRow() + dirRow * longitudinal + sideRow * lateral;
        double actualCol = intendedTarget.getColumn() + dirCol * longitudinal + sideCol * lateral;

        Position actualTarget = new Position(
            SimUtils.clamp(actualRow, 1.0, 7.0),
            SimUtils.clamp(actualCol, 1.0, 6.9)
        );

        boolean received = SimUtils.distance(actualTarget, receiver.getPosition()) < 2.0;
        double speed = Math.max(0.5, Math.min(1.5, desiredSpeed));
        return new PassResult(actualTarget, received, speed, skill, maxDeviation);
    }

    /**
     * Evaluate a shot based on striker skill and desired speed.
     */
    public static ShotResult evaluateShot(Position goalPosition,
                                            int carrierStrikerSkill,
                                            double pressure,
                                            Position shotOrigin) {
        int skill = carrierStrikerSkill;
        double dist = shotOrigin == null ? 4.0 : SimUtils.distance(shotOrigin, goalPosition);

        // On-target probability based on skill, distance, pressure
        double onTargetProb = 0.18 + skill / 20.0 * 0.30;   // 0.30 (skill 8) .. 0.48 (skill 20)
        onTargetProb *= Math.max(0.30, 1.0 - dist / 7.0);   // closer shots land on target more
        onTargetProb *= (1.0 - pressure / 200.0);

        // Short range always on target
        if (dist <= 1.2) {
            onTargetProb = 1.0;
        }

        // Determine actual target
        double actualRow;
        double actualCol;
        if (onTargetProb > Math.random()) {
            // On target - aim for goal
            actualRow = goalPosition.getRow();
            actualCol = goalPosition.getColumn() + (Math.random() - 0.5) * 1.0;
        } else {
            // Off target - scatter around goal
            actualRow = goalPosition.getRow() + (Math.random() - 0.5) * 4.0;
            actualCol = goalPosition.getColumn() + (Math.random() - 0.5) * 3.0;
        }

        Position actualTarget = new Position(
            SimUtils.clamp(actualRow, -0.5, 8.5),
            SimUtils.clamp(actualCol, -0.5, 7.5)
        );

        boolean onTarget = SimUtils.distance(actualTarget, goalPosition) < 1.0;
        double speed = ballSpeedForSkill(skill);

        return new ShotResult(actualTarget, onTarget, false, onTarget, speed);
    }

    /**
     * Calculate ball speed based on skill.
     */
    public static double ballSpeedForSkill(double skill) {
        double ms = 7.0 + (skill / 20.0) * 7.0; // 7-14 m/s
        return (ms / 14.0) * 1.5; // convert to cells/tick @ 40 TPM
    }
}