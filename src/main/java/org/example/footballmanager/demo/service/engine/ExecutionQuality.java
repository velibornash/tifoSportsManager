package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.model.*;

import java.util.Random;

/**
 * Execution quality for PASS and SHOT actions.
 * Identical logic to demo/ExecutionQuality but using service model.
 */
public class ExecutionQuality {

    private static final double PASS_DEVIATION_PER_SKILL_POINT = 0.075;
    private static final double SHOT_DEVIATION_PER_SKILL_POINT = 0.18;
    private static final double PASS_SUCCESS_THRESHOLD = 2.0;
    public static final double THRU_SUCCESS_THRESHOLD = 2.0;
    public static final double SHOT_GOAL_THRESHOLD = 0.40;

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
                Math.max(0.15, length * 0.22));
        // Minimum base deviation — even world-class passers misplace some passes
        // Reduced floor to allow better pass accuracy at lower skills
        maxDeviation = Math.max(maxDeviation, 0.03);

        double dirRow = length < 1e-9 ? 0 : dy / length;
        double dirCol = length < 1e-9 ? 1 : dx / length;
        double sideRow = -dirCol;
        double sideCol = dirRow;

        double longitudinal = (random.nextDouble() * 2 - 1) * maxDeviation;
        // Skill-scaled lateral: poor passers (skill 1-5) are more erratic laterally (3.5-4.0x),
        // good passers (skill 15-20) are tighter (1.5-2.0x). More realistic and creates more
        // sideline OOB from low-skill passers.
        double lateralSkillFactor = 2.5 + (10 - skill) * 0.15; // skill 1→3.85, skill 10→2.5, skill 20→1.0
        double lateral = (random.nextDouble() * 2 - 1) * maxDeviation * lateralSkillFactor;

        double actualRow = intendedTarget.getRow() + dirRow * longitudinal + sideRow * lateral;
        double actualCol = intendedTarget.getColumn() + dirCol * longitudinal + sideCol * lateral;
        // Allow ball to land PAST pitch boundaries for visible OOB in the viewer.
        // Field: rows 1-7, cols 1-6. OOB zone: row <1 or >7, col <1 or >6.
        // Clamp to [-0.5, 8.5] so the ball visibly crosses the line (not sitting on it).
        // determineRestart thresholds (col <1.0 / >6.0, row <1.0 / >7.0) still catch all cases.
        Position actualTarget = new Position(
                SimUtils.clamp(actualRow, -0.5, 8.5),
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
        return evaluateShot(goalPosition, carrierStrikerSkill, 0);
    }

    public ShotResult evaluateShot(Position goalPosition, int carrierStrikerSkill, double pressure) {
        return evaluateShot(goalPosition, carrierStrikerSkill, pressure, null);
    }

    public ShotResult evaluateShot(Position goalPosition, int carrierStrikerSkill, double pressure,
                                   Position shotOrigin) {
        return evaluateShot(goalPosition, carrierStrikerSkill, pressure, shotOrigin, null);
    }

    public ShotResult evaluateShot(Position goalPosition, int carrierStrikerSkill, double pressure,
                                   Position shotOrigin, Player goalkeeper) {
        int skill = Math.max(1, Math.min(20, carrierStrikerSkill));
        double dist = shotOrigin == null ? 4.0 : SimUtils.distance(shotOrigin, goalPosition);

        // ---- Angle factor: shooter column vs goal centre column (3.5). ----
        // Straight down the middle (cols 3-4) is easy; the wings are increasingly
        // acute (col 2/5 moderate, col 1/6 very hard). A winger shooting from col 1
        // or 6 should rarely beat the keeper cleanly. With no origin recorded the
        // look is treated as central (penalty-style kick, colOffset = 0).
        double colOffset = shotOrigin == null ? 0.0 : Math.abs(shotOrigin.getColumn() - 3.5);
        double angleFactor = SimUtils.clamp(1.0 - colOffset * 0.185, 0.42, 1.0); // 3.5→1.0, 2/5→0.72, 1/6→0.45

        // ---- Distance factor: closer = far easier to finish. ----
        // 1 cell ≈ 10m. <16m → close range (generally a finish), 16-25m, then long.
        double distanceFactor;
        if (dist <= 1.6) distanceFactor = 1.0;
        else if (dist <= 2.5) distanceFactor = 0.78;
        else distanceFactor = 0.55;

        // ---- Goalkeeper obstruction: how much the keeper sits in the shot lane. ----
        // 1.0 = open goal / keeper way off the lane, 0.25 = keeper fully in the way.
        double gkObstruction = computeGkObstruction(shotOrigin, goalPosition, goalkeeper, dist);

        // ---- Pressure (defenders closing) ----
        double pressureFactor = 1.0 - SimUtils.clamp(pressure / 50.0, 0, 1) * 0.35;

        // ---- Striker skill ----
        double skillFactor = 0.55 + skill / 20.0 * 0.45; // skill1→0.575, skill20→1.0

        // Clear-path close-range guarantee: with the goal path clear and distance
        // small, even a poor finisher must not fluff an open goal.
        boolean clearPathClose = dist <= 1.6 && gkObstruction >= 0.85;
        double goalProb;
        if (clearPathClose) {
            goalProb = 0.90 + skill / 20.0 * 0.10; // never below 0.90
        } else {
            goalProb = skillFactor * angleFactor * distanceFactor * gkObstruction * pressureFactor;
            goalProb = SimUtils.clamp(goalProb, 0.03, 0.9);
        }

        boolean goal = random.nextDouble() < goalProb;

        // Build the actual target — its geometry drives the goal/miss verdict.
        Position actualTarget;
        if (goal) {
            actualTarget = goalPosition;
        } else {
            // Miss deviation is scaled by distance and aggravated by a tight angle
            // (an acute-angle finish that misses goes wide more easily).
            double missMax = (0.35 + (20 - skill) * 0.05)
                    * (1.0 + (1.0 - angleFactor))
                    * (0.5 + Math.min(2.0, dist) / 2.0);
            missMax = Math.max(missMax, 0.5);
            double missDist = 0.45 + random.nextDouble() * missMax;
            double angle = random.nextDouble() * 2 * Math.PI;
            double actualRow = goalPosition.getRow() + Math.sin(angle) * missDist;
            double actualCol = goalPosition.getColumn() + Math.cos(angle) * missDist;
            actualTarget = new Position(
                    SimUtils.clamp(actualRow, -0.5, 8.5),
                    SimUtils.clamp(actualCol, -0.5, 8.5));
            // Safety: guarantee it is geometrically a miss.
            if (SimUtils.distance(actualTarget, goalPosition) < SHOT_GOAL_THRESHOLD) {
                double rowDir = actualRow >= goalPosition.getRow() ? 0.5 : -0.5;
                double colDir = actualCol >= goalPosition.getColumn() ? 0.5 : -0.5;
                actualTarget = new Position(
                        SimUtils.clamp(goalPosition.getRow() + rowDir, -0.5, 8.5),
                        SimUtils.clamp(goalPosition.getColumn() + colDir, -0.5, 8.5));
            }
        }

        // Power: higher skill → higher power (0.0–1.0), affects save difficulty
        double power = SimUtils.clamp(skill / 20.0 + (random.nextDouble() - 0.5) * 0.3, 0.1, 1.0);

        return new ShotResult(skill, actualTarget, goal, power);
    }

    /**
     * Goalkeeper obstruction multiplier on a shot.
     * Returns a value in [0.25, 1.0]: 1.0 = keeper not in the way (open look),
     * lower = keeper is in the shot lane / close to the ball and hard to beat.
     */
    private double computeGkObstruction(Position shotOrigin, Position goalPosition,
                                        Player goalkeeper, double dist) {
        if (goalkeeper == null) return 1.0;
        if (shotOrigin == null) return 1.0; // no origin recorded (penalty-style): keeper not an obstacle
        Position gkPos = goalkeeper.getPosition();
        double gkDistToShot = SimUtils.distance(gkPos, shotOrigin);

        // Is the keeper positioned between shooter and goal (in front of the shot)?
        boolean home = goalPosition.getRow() > shotOrigin.getRow();
        boolean gkInFront = home
                ? (gkPos.getRow() > shotOrigin.getRow() && gkPos.getRow() < goalPosition.getRow())
                : (gkPos.getRow() < shotOrigin.getRow() && gkPos.getRow() > goalPosition.getRow());

        if (!gkInFront) {
            // Keeper is idle, off to a side, or wandering — barely an obstacle unless
            // they happen to be right on top of the ball.
            double closePenalty = SimUtils.clamp(1.0 - gkDistToShot / 2.0, 0, 1) * 0.30;
            return SimUtils.clamp(1.0 - closePenalty, 0.25, 1.0);
        }

        // Perpendicular distance from the keeper to the shot line (shooter -> goal).
        double dx = goalPosition.getColumn() - shotOrigin.getColumn();
        double dy = goalPosition.getRow() - shotOrigin.getRow();
        double len = Math.hypot(dx, dy);
        double perpDist;
        if (len < 1e-6) {
            perpDist = Math.abs(gkPos.getColumn() - shotOrigin.getColumn());
        } else {
            double t = ((gkPos.getColumn() - shotOrigin.getColumn()) * dx
                    + (gkPos.getRow() - shotOrigin.getRow()) * dy) / (len * len);
            t = SimUtils.clamp(t, 0, 1);
            double projCol = shotOrigin.getColumn() + t * dx;
            double projRow = shotOrigin.getRow() + t * dy;
            perpDist = SimUtils.distance(gkPos, new Position(projRow, projCol));
        }

        // onLine: 1 if keeper on the shot line, 0 if ≥1.5 cells off it.
        double onLine = SimUtils.clamp(1.0 - perpDist / 1.5, 0, 1);
        // nearBall: 1 if keeper close to the shooter, decays with distance.
        double nearBall = SimUtils.clamp(1.0 - gkDistToShot / 3.0, 0, 1);
        double block = 0.35 * onLine + 0.30 * nearBall;
        return SimUtils.clamp(1.0 - block, 0.25, 1.0);
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

    public record ShotResult(int skill, Position actualTarget, boolean goal, double power) {
        public ShotResult(int skill, Position actualTarget, boolean goal) {
            this(skill, actualTarget, goal, 0.5);
        }
    }
}
