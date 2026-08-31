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
        // Goes into save difficulty and the miss scatter (tight angle = easier for
        // the keeper / wider miss). It does NOT decide whether the ball stays on
        // frame — a keeper sitting in the lane means a save, not a whiff.
        double colOffset = shotOrigin == null ? 0.0 : Math.abs(shotOrigin.getColumn() - 3.5);
        double angleFactor = SimUtils.clamp(1.0 - colOffset * 0.185, 0.42, 1.0); // 3.5→1.0, 2/5→0.72, 1/6→0.45

        // ---- ON-TARGET (in-frame) probability ----
        // A near-goal clear look stays on frame regardless of skill: a poor
        // finisher misses, but a close-range open goal must NOT fly wide. Only
        // distance, angle and pressure (real "shot quality") pull it off frame.
        // The goalkeeper's position does NOT reduce on-target — a keeper in the
        // line produces a SAVE, not a whiff (that separation is made downstream).
        //
        // Base on-frame chance by finisher skill (1..20 → 0.50..0.73 for a clean,
        // close, central, unpressured look — few shots are genuinely this clean).
        double onTargetFactor = 0.28 + skill / 20.0 * 0.45;        // 0.305..0.73 clean look
        // Distance: sharp falloff past ~1.3 cells (real football: most shot
        // attempts are wide / high from range). Around the box (~1.2 cells)
        // still on frame; from deeper they drop fast.
        double distOnFrame = dist <= 1.3 ? 1.0 : Math.max(0.0, 1.0 - (dist - 1.3) / 1.6);
        // Tight angle makes the shot far easier to put off the frame.
        double angleOnFrame = SimUtils.clamp(1.0 - colOffset * 0.30, 0.12, 1.0);
        // Pressure (defenders hurrying the shot) drags it off frame.
        double pressureFactor = 1.0 - SimUtils.clamp(pressure / 50.0, 0, 1) * 0.60;
        double onTargetProb = SimUtils.clamp(
                onTargetFactor * distOnFrame * angleOnFrame * pressureFactor, 0.05, 0.90);

        // ---- Goalkeeper beaten / open-goal guarantee ----
        // If the keeper is NOT in the shot lane and no opponent blocks the path,
        // nobody is between the shooter and goal — the shot MUST stay on frame
        // (close-range open-goal look). gkInLaneFactor ~ 0 means open goal.
        double gkInLane = gkInLaneFactor(shotOrigin, goalPosition, goalkeeper);
        if (gkInLane <= 0.2 && dist <= 2.0) {
            onTargetProb = Math.max(onTargetProb, 0.90);
        }

        boolean onTarget = random.nextDouble() < onTargetProb;

        Position actualTarget = goalPosition;
        if (onTarget) {
            // Ball reaches the goal mouth — aim at the FAR post relative to the
            // GK position (or the centre if no GK). A well-positioned GK on
            // the near post means the far post is open — the player naturally
            // aims there. Without this, every on-target shot goes to the
            // centre and any GK on the near post can save it.
            // Goal width is 1 cell (col 3.0 to col 4.0, centre col 3.5) — the
            // far-post aim stays within the actual goal mouth.
            double targetCol = goalPosition.getColumn();
            if (goalkeeper != null && shotOrigin != null) {
                double gkCol = goalkeeper.getPosition().getColumn();
                double gkColOffset = gkCol - goalPosition.getColumn();
                if (Math.abs(gkColOffset) > 0.5) {
                    // Far post: opposite column from GK, clamped to goal mouth
                    // (col 3.0 to col 4.0 — goal width is 1 cell, ~10m).
                    double farCol = goalPosition.getColumn() - gkColOffset;
                    targetCol = SimUtils.clamp(farCol, 3.0, 4.0);
                }
            }
            actualTarget = new Position(goalPosition.getRow(), targetCol);
            // Scattered miss (wide / over the bar) — a genuine bad finish.
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

        return new ShotResult(skill, actualTarget, onTarget, power, gkInLane, angleFactor);
    }

    /**
     * How much the goalkeeper sits in the shot lane (between shooter and goal).
     * Returns [0,1]: 1.0 = keeper fully covering the shot (on/near the goal line
     * and centred on the shot); ~0.05 = keeper clearly out of the lane (nobody on
     * the path to goal), i.e. effectively an open goal / near-zero save chance.
     */
    private double gkInLaneFactor(Position shotOrigin, Position goalPosition, Player goalkeeper) {
        if (goalkeeper == null || shotOrigin == null) return 0.05;
        Position gkPos = goalkeeper.getPosition();

        // Project the keeper onto the shooter -> goal line.
        double dx = goalPosition.getColumn() - shotOrigin.getColumn();
        double dy = goalPosition.getRow() - shotOrigin.getRow();
        double len = Math.hypot(dx, dy);
        double t;
        double perpDist;
        if (len < 1e-6) {
            t = 1.0;
            perpDist = Math.abs(gkPos.getColumn() - shotOrigin.getColumn());
        } else {
            t = ((gkPos.getColumn() - shotOrigin.getColumn()) * dx
                    + (gkPos.getRow() - shotOrigin.getRow()) * dy) / (len * len);
            double projCol = shotOrigin.getColumn() + t * dx;
            double projRow = shotOrigin.getRow() + t * dy;
            perpDist = SimUtils.distance(gkPos, new Position(projRow, projCol));
        }

        // onLine: 1 if keeper on the shot line, 0 if >= ~1.6 cells off it.
        double onLine = SimUtils.clamp(1.0 - perpDist / 1.6, 0, 1);
        // coverage: keeper must sit between the shooter (t ~ 0.2) and the goal
        // (t ~ 1.0), i.e. in front of the shot, to actually cover it. Behind the
        // shooter (t < 0.2) or clearly past the goal (t > 1.0) means little cover.
        double coverage = SimUtils.clamp(t, 0.2, 1.0);
        // near enough to react — a keeper glued to the ball covers best.
        double gkDistToShot = SimUtils.distance(gkPos, shotOrigin);
        double close = SimUtils.clamp(1.0 - gkDistToShot / 4.0, 0, 1);
        return SimUtils.clamp(onLine * coverage * (0.7 + 0.3 * close), 0.05, 1.0);
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

    public record ShotResult(int skill, Position actualTarget, boolean goal, double power,
                             double gkInLane, double angleFactor) {
        public ShotResult(int skill, Position actualTarget, boolean goal) {
            this(skill, actualTarget, goal, 0.5, 1.0, 1.0);
        }
    }
}
