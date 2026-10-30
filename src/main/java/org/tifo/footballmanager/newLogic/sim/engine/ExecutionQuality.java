package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.*;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

/**
 * Execution quality — only calculates execution quality. Does NOT override decisions.
 * Returns launch parameters (aim point, speed, spin, onTarget for shots).
 * NO target clamping — ball physics decides where it stops.
 */
public class ExecutionQuality {

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

        double longitudinal = (SimulationRandom.nextDouble() * 2 - 1) * maxDeviation;
        double lateral = (SimulationRandom.nextDouble() * 2 - 1) * maxDeviation * 2.5;

        double actualRow = intendedTarget.getRow() + dirRow * longitudinal + sideRow * lateral;
        double actualCol = intendedTarget.getColumn() + dirCol * longitudinal + sideCol * lateral;

        Position actualTarget = new Position(actualRow, actualCol);
        // speed clamped to launch limits
        double speed = Math.max(BallPhysicsEngine.MIN_LAUNCH_SPEED, Math.min(BallPhysicsEngine.MAX_BALL_SPEED, desiredSpeed));
        // Owner (2026-10-30): no spin — spin causes lateral bend that pushes
        // short passes off target. All passes are straight.
        double spin = 0.0;

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
        return evaluateShot(goalPosition, carrierStrikerSkill, pressure, shotOrigin, true);
    }

    /**
     * @param attacksTowardHigherRows true when the shooting team attacks row 8.0
     *                                 (HOME), false when it attacks row 1.0 (AWAY).
     *                                 Needed because a miss must fall SHORT of the
     *                                 target goal line on BOTH sides — the shot
     *                                 direction is not symmetric in row space.
     */
    public static ShotResult evaluateShot(Position goalPosition,
                                          int carrierStrikerSkill,
                                          double pressure,
                                          Position shotOrigin,
                                          boolean attacksTowardHigherRows) {
        return evaluateShot(goalPosition, carrierStrikerSkill, pressure, shotOrigin,
                attacksTowardHigherRows, null);
    }

    /**
     * The same evaluation, but aware of the shooter's confidence.
     *
     * <p>Confidence scales the striker's finishing rather than the chance of attempting the shot: a
     * player who cannot believe in himself does not hit the target less often so much as he hits it
     * worse. That is why it is folded into the skill term and not added to the on-target
     * probability, which would have turned morale into a shot-volume knob.
     *
     * @param shooter the player taking the shot, or null for a confidence-free evaluation
     */
    public static ShotResult evaluateShot(Position goalPosition,
                                          int carrierStrikerSkill,
                                          double pressure,
                                          Position shotOrigin,
                                          boolean attacksTowardHigherRows,
                                          org.tifo.footballmanager.newLogic.sim.model.Player shooter) {
        int skill = carrierStrikerSkill;
        double dist = shotOrigin == null ? 4.0
                : Math.hypot(shotOrigin.getRow() - goalPosition.getRow(),
                             shotOrigin.getColumn() - goalPosition.getColumn());

        // On-target probability based on skill, distance, pressure.
        // Striker skill drives finishing; distance cuts it; pressure reduces it.
        // Calibrated so a good finisher at close range puts the ball on frame the
        // majority of the time (real football), while long-range/stressed shots
        // degrade heavily. Even a sitter is never a guaranteed on-frame shot.
        // Calibrated down from 0.12 + skill*0.028 with a 0.20 close-range lift:
        // that put 57% of all shots on target (real football: ~33%) and, once the
        // keeper's reach was corrected, turned over half of them into goals.
        // Recalibrated 2026-09-26 (S1.1b) against the fresh 50-match baseline, once the keeper
        // trajectory gate made the statistics trustworthy: 33.1 shots, 38.0% on target, 3.8 goals.
        // Shot VOLUME used to be a deliberate owner decision (SHOT_FREQUENCY_GATE 0.30, "30-40
        // shots is fine"); that gate was removed on 2026-10-23 (ME-3, "SKLONI TO PRAVILO"), so
        // volume is now an OUTPUT of the decision while conversion is still tuned instead. Real
        // football: ~33% on
        // target. Lowering skillBase and the close-range lift pulls SOT toward that and drags
        // goals down with it, since goals are a function of on-target shots, not of attempts.
        // Confidence scales the striker's finishing, not the shot's distance or chance of being
        // struck. A player who cannot believe in himself does not hit the target less often so much
        // as he hits it worse, so the effect is applied to the quality of the attempt.
        double confidence = shooter != null ? shooter.confidenceModifier() : 1.0;
        skill = Math.max(1, Math.min(20, (int) Math.round(skill * confidence)));

        double skillBase = 0.06 + skill * 0.015;               // skill 1..20 -> 0.075..0.36
        double distFactor = Math.max(0.25, 1.0 - dist / 9.0);  // close = 1.0, 9+ cells = 0.25
        double onTargetProb = skillBase * distFactor;
        if (dist < 2.0) onTargetProb += 0.08;                  // close-range lift (inside ~4 m)
        onTargetProb *= (1.0 - pressure / 200.0);
        onTargetProb = Math.min(onTargetProb, 0.85);

        boolean onTarget = onTargetProb > SimulationRandom.nextDouble();

        // Determine actual target (deviated)
        double actualRow, actualCol;
        if (onTarget) {
            // On target: the aim is spread across the whole goal mouth, not
            // clustered in the middle. It used to be ±0.3 of the 1.0-cell
            // mouth, so every on-target shot arrived within a third of a cell of
            // the centre — exactly where a keeper stands, and therefore
            // unsaveable-looking on paper and unsaveable in practice. Real shots
            // on frame go to corners. Spread covers the full mouth, clamped
            // strictly inside the posts.
            actualRow = goalPosition.getRow();
            double mouthLeft = GoalPhysical.MOUTH_LEFT + 0.05;
            double mouthRight = GoalPhysical.MOUTH_RIGHT - 0.05;
            actualCol = mouthLeft
                    + SimulationRandom.nextDouble() * (mouthRight - mouthLeft);
        } else {
            // Off target — MUST cross the goal line OUTSIDE the mouth.
            //
            // What was here, and why it was wrong: the aim was placed at some column beyond the
            // post AND short of the goal line, on the reasoning that a shot short of the line cannot
            // be a goal. But the ball is launched airborne at 0.75-1.5 cells/tick against AIR_DECEL
            // 0.03, so its range is v^2/(2a) — up to 37 CELLS. It flies straight through the aim
            // point and keeps going. The aim was therefore only ever a DIRECTION, and a direction that
            // points a little wide of the right post, taken from far out on the left, crosses the goal
            // line well INSIDE the mouth. Enumerating the shipped formula over the pitch grid found 37
            // origin/aim combinations that a shot the engine called off target scored as a goal.
            //
            // The fix is not to make the ball stop at the aim — the goal is decided at the crossing
            // point, so range is irrelevant to it — it is to make the aim mean what it says. The aim
            // IS the point on the goal line the ball will cross, so choosing a column outside the
            // mouth makes the crossing a column outside the mouth, exactly, from any origin.
            //
            // What this costs: a miss can no longer be modelled as falling short, because the engine's
            // MIN_LAUNCH_SPEED puts a floor of ~3.5 cells of travel on any struck ball, so from inside
            // the box there is no speed at which the ball falls short of the line. Every miss is now
            // wide, which is the honest consequence of the launch-speed floor rather than a choice.
            boolean left = SimulationRandom.nextBoolean();
            double clearance = 0.3 + SimulationRandom.nextDouble() * 1.0;   // 0.3-1.3 cells past the post
            actualCol = left
                    ? GoalPhysical.MOUTH_LEFT - clearance
                    : GoalPhysical.MOUTH_RIGHT + clearance;
            // ON the goal line, so the direction from the origin points at exactly that column.
            actualRow = goalPosition.getRow();
        }

        Position actualTarget = new Position(actualRow, actualCol);
        double speed = ballSpeedForSkill(skill);
        double spin = SimulationRandom.nextDouble() * 0.3; // shots can have more spin

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