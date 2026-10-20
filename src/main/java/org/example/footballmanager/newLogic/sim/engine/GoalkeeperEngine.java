package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.GoalPhysical;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.PitchEnvironment;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.util.SimUtils;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

/**
 * Goalkeeper model: how he POSITIONS himself and how he SAVES.
 *
 * This replaces two things that were both wrong:
 *
 * 1. <b>Positioning</b> was a static tactical anchor — the keeper stood on a
 *    fixed cell from {@code TacticsRules} and never reacted to the ball. A real
 *    keeper stands on the line between the ball and the middle of his goal, so
 *    he makes himself hard to beat, and he advances off his line as the ball gets
 *    closer — further for a one-on-one, barely at all for a shot from 30 m.
 *
 * 2. <b>Saving</b> was a hard geometric radius: if the ball's flight segment came
 *    within {@code GK_SAVE_R} of the keeper's body, it was saved, full stop.
 *    A fixed radius cannot express shot placement, shot power or keeper skill,
 *    which is why 61% of shots on target became goals — the model had no way to
 *    say "that was in the top corner".
 *
 * The save is now GRADED: the keeper has a reach that grows with his skill and
 * shrinks with the ball's speed (less time to react), and once the ball is
 * within reach the chance of actually stopping it falls off with the distance
 * from his body and with how far it is from where he was standing.
 */
public class GoalkeeperEngine {

    // ── Positioning ──────────────────────────────────────────────────────────

    /** Distance off his own goal line when the ball is far away (just off the line). */
    private static final double ADVANCE_FAR = 0.35;
    /** Ball distance (cells) at which he starts advancing meaningfully. */
    private static final double ADVANCE_FROM = 4.0;
    /** How far he comes for a one-on-one inside the box. */
    private static final double RUSH_ADVANCE = 2.6;
    /** He never leaves his own penalty area by more than this. */
    private static final double MAX_ADVANCE = 2.8;
    /** Extra margin outside the posts he is allowed to cover. */
    private static final double POST_MARGIN = 0.9;

    /**
     * Where the keeper should stand this tick.
     *
     * He stands on the bisector between the ball and the centre of the goal —
     * the spot that makes the shooting angle as narrow as possible — pushed off
     * his line by an amount that grows as the ball approaches, and pushed much
     * further when the carrier is alone in the box (a real keeper comes out for
     * that). He is clamped to the goal-mouth span plus a margin, and can never
     * stray so far that he abandons his goal.
     */
    public Position targetPosition(MatchState state, Player gk) {
        PitchEnvironment env = state.getEnvironment();
        boolean home = "HOME".equals(gk.getTeam());
        double goalLine = home ? PitchEnvironment.HOME_GOAL_LINE : PitchEnvironment.AWAY_GOAL_LINE;
        double goalCentreCol = (GoalPhysical.MOUTH_LEFT + GoalPhysical.MOUTH_RIGHT) / 2.0;
        double direction = home ? 1.0 : -1.0;   // +row for HOME, -row for AWAY

        Position ball = state.getBall().getPosition();
        double ballRow = ball.getRow();
        double ballCol = ball.getColumn();

        // Distance of the ball from the keeper's goal line, in cells.
        double ballDistance = Math.abs(ballRow - goalLine);

        // How far off his line to stand.
        double advance = ADVANCE_FAR;
        if (ballDistance < ADVANCE_FROM) {
            // 4 cells -> ADVANCE_FAR, 0 cells -> ~1.5 cells. Linear ramp.
            double t = 1.0 - ballDistance / ADVANCE_FROM;
            advance = ADVANCE_FAR + t * 1.15;
        }
        if (ballDistance < 2.0 && isOneOnOne(state, gk)) {
            advance = RUSH_ADVANCE;
        }
        // He can never advance PAST the ball — that is not a position, that is
        // being past the attacker. Without this clamp `t` exceeds 1 and the
        // bisector point extrapolates beyond the goal centre, which threw the
        // keeper to the OPPOSITE side of the pitch whenever he closed a striker
        // down inside a cell (and made almost every close-range shot unsaveable).
        advance = Math.min(Math.min(advance, MAX_ADVANCE), Math.max(ballDistance, ADVANCE_FAR));

        // Bisector: a point on the segment from the ball to the goal centre, at
        // `advance` cells from the goal line.
        double span = ballDistance;
        double t = span < 1e-6 ? 0 : SimUtils.clamp(advance / span, 0.0, 1.0);
        double targetCol = ballCol + (goalCentreCol - ballCol) * t;
        double targetRow = goalLine + direction * advance;

        // He covers the goal mouth plus a margin, and nothing beyond it.
        targetCol = SimUtils.clamp(targetCol,
                GoalPhysical.MOUTH_LEFT - POST_MARGIN, GoalPhysical.MOUTH_RIGHT + POST_MARGIN);
        // Sanity: never behind his own goal line.
        if (home) targetRow = Math.max(targetRow, goalLine);
        else targetRow = Math.min(targetRow, goalLine);

        if (env != null) {
            targetRow = SimUtils.clamp(targetRow, PitchEnvironment.HOME_GOAL_LINE,
                    PitchEnvironment.AWAY_GOAL_LINE);
            targetCol = SimUtils.clamp(targetCol, PitchEnvironment.OOB_COL_MIN,
                    PitchEnvironment.OOB_COL_MAX);
        }
        return new Position(targetRow, targetCol);
    }

    /** Carrier alone with the ball inside our penalty area, with no defender near him. */
    private boolean isOneOnOne(MatchState state, Player gk) {
        Player carrier = state.getCarrier();
        if (carrier == null || carrier.getTeam().equals(gk.getTeam())) return false;
        double goalLine = "HOME".equals(gk.getTeam())
                ? PitchEnvironment.HOME_GOAL_LINE : PitchEnvironment.AWAY_GOAL_LINE;
        double distance = Math.abs(carrier.getPosition().getRow() - goalLine);
        if (distance > 2.5) return false;
        for (Player p : state.getPlayers()) {
            if (p.getTeam().equals(gk.getTeam()) || p == gk) continue;
            if (p.isUnavailable()) continue;
            if (SimUtils.distance(p.getPosition(), carrier.getPosition()) < 1.0) return false;
        }
        return true;
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Reach on a ball he can get a hand to, in cells, at zero ball speed.
     * The mouth is 1.0 cell wide, so a keeper standing centrally must reach
     * ~0.4-0.5 to cover it; below that every shot into the far corner beats him
     * (the first pass of this model gave 85% conversion for that reason).
     */
    private static final double REACH_BASE = 0.34;
    /** Extra reach at keeper skill 20 (vs skill 1). */
    private static final double REACH_SKILL = 0.32;
    /** A maximum-speed shot removes this share of his reach (no time to react). */
    private static final double REACH_SPEED_PENALTY = 0.40;

    /**
     * Chance of stopping a ball that passes right through his body position.
     * Above 1.0 on purpose: the graded fall-off below means a ball is usually
     * NOT at his body, and the product has to average out near a realistic
     * ~70% of on-target shots being held.
     */
    private static final double SAVE_BASE = 1.10;

    /**
     * How sharply the save chance falls off with distance from his body. 3.0
     * makes a shot at the edge of his reach far harder than one at his chest,
     * which is what puts corners away instead of saving everything geometrically
     * inside a radius.
     */
    private static final double PROXIMITY_EXPONENT = 3.0;

    /**
     * How close the ball's flight segment passes to the keeper's body, in cells.
     */
    public double distanceToSegment(Position keeper, Position from, Position to) {
        double ax = keeper.getColumn(), ay = keeper.getRow();
        double bx = from.getColumn(), by = from.getRow();
        double cx = to.getColumn(), cy = to.getRow();
        double dx = cx - bx, dy = cy - by;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-12) return Math.hypot(ax - bx, ay - by);
        double t = SimUtils.clamp(((ax - bx) * dx + (ay - by) * dy) / lenSq, 0.0, 1.0);
        return Math.hypot(ax - (bx + t * dx), ay - (by + t * dy));
    }

    /**
     * The keeper's effective reach for a ball travelling at {@code speed}.
     * Skill widens it, pace narrows it — a 14 m/s shot gives him well under a
     * tick to set himself, a slow roller gives him many.
     */
    public double effectiveReach(Player gk, double speed) {
        int skill = (int) Math.round(gk.getSkills().keeper());
        double reach = REACH_BASE + (skill / 20.0) * REACH_SKILL;
        double speedFactor = SimUtils.clamp(speed / BallPhysicsEngine.MAX_BALL_SPEED, 0.0, 1.0);
        return reach * (1.0 - speedFactor * REACH_SPEED_PENALTY);
    }

    /**
     * Graded save attempt.
     *
     * @param distance how close the flight segment passes to the keeper, in cells
     * @return true if he stops it
     */
    public boolean trySave(Player gk, double distance, double speed) {
        double reach = effectiveReach(gk, speed);
        if (distance > reach) {
            // He cannot get a hand to it at all — it beats him.
            return false;
        }
        // Proximity: right on top of him is nearly certain, at the very edge of
        // his reach it is a coin flip. The exponent makes the fall-off steep near
        // the limit, which is what puts shots in the corner away.
        double proximity = 1.0 - Math.pow(distance / reach, PROXIMITY_EXPONENT);
        int skill = (int) Math.round(gk.getSkills().keeper());
        double skillFactor = 0.70 + (skill / 20.0) * 0.50;
        double chance = SAVE_BASE * skillFactor * proximity;
        return SimulationRandom.nextDouble() < chance;
    }
}
