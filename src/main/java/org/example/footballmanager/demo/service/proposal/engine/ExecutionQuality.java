package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import java.util.Random;

/**
 * Execution quality — only calculates execution quality. Does NOT override decisions.
 * Returns launch parameters (aim point, speed, spin, onTarget for shots).
 * NO target clamping — ball physics decides where it stops.
 */
public class ExecutionQuality {

    private static final Random RNG = new Random();

    /** Result for a pass execution. */
    public static class PassResult {
        private final Position actualTarget;   // deviated aim point
        private final double speed;            // cells/tick
        private final double spin;             // 0..1
        private final int skill;               // passer passing skill
        private final double deviation;        // max deviation applied

        public PassResult(Position actualTarget, double speed, double spin,
                          int skill, double deviation) {
            this.actualTarget = actualTarget;
            this.speed = speed;
            this.spin = spin;
            this.skill = skill;
            this.deviation = deviation;
        }

        public Position getActualTarget() { return actualTarget; }
        public double getSpeed() { return speed; }
        public double getSpin() { return spin; }
        public int getSkill() { return skill; }
        public double getDeviation() { return deviation; }
    }

    /** Result for a shot execution. */
    public static class ShotResult {
        private final Position actualTarget;   // deviated aim point
        private final boolean onTarget;        // was the aim on target (for stats)
        private final double speed;            // cells/tick
        private final double spin;             // 0..1

        public ShotResult(Position actualTarget, boolean onTarget,
                          double speed, double spin) {
            this.actualTarget = actualTarget;
            this.onTarget = onTarget;
            this.speed = speed;
            this.spin = spin;
        }

        public Position getActualTarget() { return actualTarget; }
        public boolean isOnTarget() { return onTarget; }
        public double getSpeed() { return speed; }
        public double getSpin() { return spin; }
    }

    /**
     * Evaluate a pass based on passer skill and desired speed.
     * Returns a deviated aim point + launch speed + spin.
     * NO clamping of the target — ball physics handles OOB/stop.
     */
    public static PassResult evaluatePass(Player passer, Position origin,
                                          Position intendedTarget,
                                          Player receiver,
                                          double desiredSpeed) {
        int skill = (int) passer.getSkills().passing();
        double maxSpeedForSkill = ballSpeedForSkill(skill); // 7-14 m/s -> 0.75-1.5 c/t
        double overspeed = Math.max(0.0, desiredSpeed - maxSpeedForSkill);
        double maxDeviation = 0.02 + overspeed * 2.0;

        // Direction from origin to intended target
        double dx = intendedTarget.getColumn() - origin.getColumn();
        double dy = intendedTarget.getRow() - origin.getRow();
        double len = Math.sqrt(dx * dx + dy * dy);

        double dirRow = len < 1e-9 ? 0 : dy / len;
        double dirCol = len < 1e-9 ? 1 : dx / len;
        double sideRow = -dirCol;  // perpendicular left
        double sideCol = dirRow;

        double longitudinal = (RNG.nextDouble() * 2 - 1) * maxDeviation;
        double lateral = (RNG.nextDouble() * 2 - 1) * maxDeviation * 2.5;

        double actualRow = intendedTarget.getRow() + dirRow * longitudinal + sideRow * lateral;
        double actualCol = intendedTarget.getColumn() + dirCol * longitudinal + sideCol * lateral;

        Position actualTarget = new Position(actualRow, actualCol);
        // speed clamped to launch limits
        double speed = Math.max(BallPhysicsEngine.MIN_LAUNCH_SPEED, Math.min(BallPhysicsEngine.MAX_BALL_SPEED, desiredSpeed));
        // small spin for ground passes (0..0.2)
        double spin = RNG.nextDouble() * 0.2;

        return new PassResult(actualTarget, speed, spin, skill, maxDeviation);
    }

    /**
     * Evaluate a shot based on striker skill and pressure.
     * Returns a deviated aim point + launch speed + spin + onTarget flag.
     */
    public static ShotResult evaluateShot(Position goalPosition,
                                          int carrierStrikerSkill,
                                          double pressure,
                                          Position shotOrigin) {
        int skill = carrierStrikerSkill;
        double dist = shotOrigin == null ? 4.0
                : Math.hypot(shotOrigin.getRow() - goalPosition.getRow(),
                             shotOrigin.getColumn() - goalPosition.getColumn());

        // On-target probability based on skill, distance, pressure.
        // Striker skill drives finishing; distance cuts it; pressure reduces it.
        // A point-blank chance is still not a guaranteed on-frame shot — a
        // defender/GK covering, angle, and composure all intervene.
        double onTargetProb = 0.03 + skill * 0.006;              // skill 1..20 -> 0.04..0.15 base
        onTargetProb *= Math.max(0.20, 1.0 - dist / 7.0);        // far = much worse
        onTargetProb *= (1.0 - pressure / 200.0);
        if (dist < 1.5) onTargetProb += 0.05;                    // close-range lift (cap below)
        onTargetProb = Math.min(onTargetProb, 0.40);

        boolean onTarget = onTargetProb > RNG.nextDouble();

        // Determine actual target (deviated)
        double actualRow, actualCol;
        if (onTarget) {
            // Aim for goal mouth center with small spread
            actualRow = goalPosition.getRow();
            actualCol = goalPosition.getColumn() + (RNG.nextDouble() - 0.5) * 0.6; // within mouth
        } else {
            // Off target — MUST not cross the goal line inside the mouth. A shot
            // aimed PAST the line "wide of the post" still crosses the line at a
            // column between origin and aim — from a central origin that lands in
            // the mouth. So off-target shots always land SHORT of the line and
            // wide of the mouth: the flight segment never reaches goal-line height
            // inside 3.5-4.5.
            double mouthLeft = goalPosition.getColumn() - 0.5;
            double mouthRight = goalPosition.getColumn() + 0.5;
            if (RNG.nextBoolean()) {
                actualCol = mouthLeft - (0.5 + RNG.nextDouble() * 1.0);   // wide of left post
            } else {
                actualCol = mouthRight + (0.5 + RNG.nextDouble() * 1.0);  // wide of right post
            }
            // Always short of the line: the ball stops well before the goal.
            actualRow = goalPosition.getRow() - (0.3 + RNG.nextDouble() * 0.9);
        }

        Position actualTarget = new Position(actualRow, actualCol);
        double speed = ballSpeedForSkill(skill);
        double spin = RNG.nextDouble() * 0.3; // shots can have more spin

        return new ShotResult(actualTarget, onTarget, speed, spin);
    }

    /**
     * Calculate ball launch speed (cells/tick @ 40 TPM) based on skill 1..20.
     * Maps linearly from 7 m/s (skill 1) to 14 m/s (skill 20).
     */
    public static double ballSpeedForSkill(double skill) {
        double ms = 7.0 + (skill / 20.0) * 7.0;   // 7..14 m/s
        return (ms / 14.0) * BallPhysicsEngine.MAX_BALL_SPEED;      // 0.75..1.5 cells/tick
    }
}