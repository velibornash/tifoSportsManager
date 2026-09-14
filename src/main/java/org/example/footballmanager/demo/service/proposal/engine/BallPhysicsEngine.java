package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;

import java.util.List;
import java.util.Random;

/**
 * Ball physics engine — pure physics, no decision-making, no rules enforcement.
 * Ball moves with initial velocity, decelerates to 0, collides with posts/players,
 * detects goal plane crossing, and handles OOB 4-tick visible hold.
 * The engine NEVER knows who called the kick; it only reads carrier/pendingReceiver/
 * lastTouchTeam from MatchState.
 */
public class BallPhysicsEngine implements BallEngine {

    private static final Random RNG = new Random();

    // --- Physics constants (cells/tick @ 40 TPM = 1 tick = 1.5 s match time) ---
    public static final double MAX_BALL_SPEED = 1.5;      // 14 m/s
    public static final double MIN_LAUNCH_SPEED = 0.75;   // 7 m/s
    public static final double GROUND_DECEL = 0.35;       // ~2.2 m/s^2
    public static final double AIR_DECEL = 0.15;          // ~0.9 m/s^2
    public static final double STOP_SPEED = 0.02;
    public static final double LANDING_SPEED = 0.30;      // air -> ground transition
    public static final double FAST_CONTACT = 1.0;        // >= this: BLOCK (parry), <: INTERCEPT

    // --- Contact radii (cells) ---
    public static final double RECEIVE_R = 0.35;
    public static final double INTERCEPT_R = 0.14;   // 2 m — reading lane (probabilistic)
    public static final double DEFLECT_R = 0.035;    // 0.5 m — ball physically strikes the body
    public static final double PICKUP_R = 0.35;
    public static final double GK_SAVE_R = 0.75;   // goalkeeper reach on fast balls
    public static final double PICKUP_DISTANCE = PICKUP_R; // alias for orchestrator
    public static final double BALL_R = 0.015;

    // --- OOB hold ---
    public static final int OOB_HOLD_TICKS = 4;
    public static final double BOUNCE_DAMP = 0.5;
    public static final double SPIN_LAT = 0.05;

    /**
     * One physics step. Returns a BallStepResult describing what happened.
     * Does NOT mutate MatchState except the ball itself; the orchestrator
     * applies carrier/restart changes based on the result.
     */
    public BallStepResult stepBall(MatchState state) {
        Ball ball = state.getBall();

        // 1. Possession — ball glued to carrier (orchestrator snaps position)
        if (state.getCarrier() != null) {
            return BallStepResult.flight();
        }

        // 2. Stopped loose ball: OOB hold countdown continues, then pickup check
        if (ball.getSpeed() <= STOP_SPEED) {
            if (state.getOobPending() != null) {
                state.decrementOobHold();
                if (state.getOobHoldTicks() <= 0) {
                    String due = state.getOobPending();
                    state.clearOobPending();
                    return BallStepResult.oobRestart(due);
                }
                return BallStepResult.oobHold(state.getOobPending(), state.getOobHoldTicks());
            }
            // Pickup by nearest player
            Player near = nearestPlayer(state, null, PICKUP_R);
            if (near != null) {
                state.setCarrier(near);
                state.setLastTouchTeam(near.getTeam());
                ball.stop();
                return BallStepResult.loosePickup(near.getLabel());
            }
            ball.stop();
            return BallStepResult.stopped();
        }

        Position prev = ball.getPosition();
        double spd = ball.getSpeed();

        // 3. Spin -> lateral bend
        if (Math.abs(ball.getSpin()) > 1e-9) {
            double a = ball.getSpin() * SPIN_LAT;
            double nx = ball.getVelX() * Math.cos(a) - ball.getVelY() * Math.sin(a);
            double ny = ball.getVelX() * Math.sin(a) + ball.getVelY() * Math.cos(a);
            ball.setVelocity(nx, ny);
        }

        // 4. Deceleration (ground vs air), preserve heading
        spd = Math.max(0, spd - (ball.isAirborne() ? AIR_DECEL : GROUND_DECEL));
        if (spd <= STOP_SPEED) {
            ball.stop();
            ball.setAirborne(false);
            state.setPendingReceiver(null);
            state.setReceivePoint(null);
            return BallStepResult.stopped();
        }
        double oldSpd = ball.getSpeed();
        ball.setVelocity(ball.getVelX() * spd / oldSpd, ball.getVelY() * spd / oldSpd);

        // 5. Move
        Position curr = new Position(
                prev.getRow() + ball.getVelY(),
                prev.getColumn() + ball.getVelX()
        );
        ball.setPosition(curr);

        // 6. Landing (air -> ground)
        if (ball.isAirborne() && spd < LANDING_SPEED) {
            ball.setAirborne(false);
        }

        // 7. Post hit (before goal plane — posts sit exactly on the line)
        if (postHit(state, prev, curr)) {
            reflectFromPost(state, ball, curr);
            ball.setAirborne(false);
            return BallStepResult.postHit();
        }

        // 8. Player contact — earliest along segment wins; pendingReceiver tie priority
        BallStepResult contact = playerContact(state, prev, curr, spd);
        if (contact != null) return contact;

        // 9. Goal plane crossing (only if a team last touched)
        if (state.getLastTouchTeam() != null) {
            String scorer = goalCrossing(state, prev, curr);
            if (scorer != null) {
                state.clearOobPending();
                ball.stop();
                return BallStepResult.goal(scorer);
            }
        }

        // 10. OOB zone — visible 4-tick hold, NO instant teleport
        PitchEnvironment env = state.getEnvironment();
        boolean out = env.isOOB(curr);
        if (out && state.getOobPending() == null) {
            state.setPendingReceiver(null);
            state.setReceivePoint(null);
            String restart = env.oobRestartType(state.getLastTouchTeam(), curr);
            state.setOobPending(restart);
            state.setOobHoldTicks(OOB_HOLD_TICKS);
            return BallStepResult.oobEnter(restart);
        }
        if (state.getOobPending() != null) {
            if (!out) {
                state.clearOobPending();
                return BallStepResult.oobCancel();
            }
            state.decrementOobHold();
            if (state.getOobHoldTicks() <= 0) {
                String due = state.getOobPending();
                state.clearOobPending();
                return BallStepResult.oobRestart(due);
            }
            return BallStepResult.oobHold(state.getOobPending(), state.getOobHoldTicks());
        }

        return BallStepResult.flight();
    }

    /** Launch the ball toward an aim point with given speed (cells/tick), air/ground, spin. */
    public void launch(Ball ball, Position origin, Position aim, double speed, boolean airborne, double spin) {
        ball.setPosition(origin);
        double dx = aim.getColumn() - origin.getColumn();
        double dy = aim.getRow() - origin.getRow();
        double len = Math.hypot(dx, dy);
        if (len < 1e-9) {
            ball.setVelocity(0, 0);
        } else {
            ball.setVelocity(dx / len * speed, dy / len * speed);
        }
        ball.setAirborne(airborne);
        ball.setSpin(spin);
        ball.setLaunchSpeed(speed);
    }

    // --- helpers ---

    private boolean postHit(MatchState state, Position prev, Position curr) {
        PitchEnvironment env = state.getEnvironment();
        return env.getHomeGoal().segmentHitsPost(prev, curr, BALL_R)
                || env.getAwayGoal().segmentHitsPost(prev, curr, BALL_R);
    }

    private void reflectFromPost(MatchState state, Ball ball, Position curr) {
        PitchEnvironment env = state.getEnvironment();
        // Find nearest post to current position
        GoalPhysical hp = env.getHomeGoal();
        GoalPhysical ap = env.getAwayGoal();
        double dHome = hp.distToNearestPost(curr.getRow(), curr.getColumn());
        double dAway = ap.distToNearestPost(curr.getRow(), curr.getColumn());
        GoalPhysical gp = dHome <= dAway ? hp : ap;
        // Normal from post to ball
        double nr = curr.getRow() - gp.getGoalLineRow();
        double nc = (curr.getColumn() < gp.getMouthLeft() + (gp.getMouthRight() - gp.getMouthLeft()) / 2)
                ? curr.getColumn() - gp.getMouthLeft()
                : curr.getColumn() - gp.getMouthRight();
        double len = Math.hypot(nr, nc);
        if (len < 1e-9) { ball.setVelocity(-ball.getVelX(), -ball.getVelY()); return; }
        nr /= len; nc /= len;
        double dot = ball.getVelX() * nc + ball.getVelY() * nr;
        ball.setVelocity(
                (ball.getVelX() - 2 * dot * nc) * BOUNCE_DAMP,
                (ball.getVelY() - 2 * dot * nr) * BOUNCE_DAMP
        );
    }

    private String goalCrossing(MatchState state, Position prev, Position curr) {
        PitchEnvironment env = state.getEnvironment();
        // HOME attacks AWAY goal (row 8.0): prev < 8.0 && curr >= 8.0
        if (prev.getRow() < env.getAwayGoal().getGoalLineRow()
                && curr.getRow() >= env.getAwayGoal().getGoalLineRow()) {
            double col = crossingCol(prev, curr, env.getAwayGoal().getGoalLineRow());
            if (env.getAwayGoal().isInsideMouth(col)) {
                return "HOME";
            }
        }
        // AWAY attacks HOME goal (row 1.0): prev > 1.0 && curr <= 1.0
        if (prev.getRow() > env.getHomeGoal().getGoalLineRow()
                && curr.getRow() <= env.getHomeGoal().getGoalLineRow()) {
            double col = crossingCol(prev, curr, env.getHomeGoal().getGoalLineRow());
            if (env.getHomeGoal().isInsideMouth(col)) {
                return "AWAY";
            }
        }
        return null;
    }

    private double crossingCol(Position p1, Position p2, double lineRow) {
        double dr = p2.getRow() - p1.getRow();
        if (Math.abs(dr) < 1e-9) return p1.getColumn();
        double t = (lineRow - p1.getRow()) / dr;
        return p1.getColumn() + (p2.getColumn() - p1.getColumn()) * t;
    }

    private BallStepResult playerContact(MatchState state, Position prev, Position curr, double spd) {
        boolean fast = spd >= FAST_CONTACT;
        Player pending = state.getPendingReceiver();
        double bestT = Double.MAX_VALUE;
        String ev = null;
        Player hit = null;

        // pending receiver (same team as lastTouch) — RECEIVE if within radius
        if (pending != null) {
            double d = pointSegmentDist(pending.getPosition().getRow(), pending.getPosition().getColumn(), prev, curr);
            if (d <= RECEIVE_R) {
                bestT = approachT(pending.getPosition().getRow(), pending.getPosition().getColumn(), prev, curr);
                ev = "RECEIVE";
                hit = pending;
            }
        }

        // other players
        Player lastToucher = state.getLastTouchPlayer();
        for (Player p : state.getPlayers()) {
            if (p == pending || p.isUnavailable()) continue;
            // Exclude the player who kicked the ball from immediate teammate deflection
            if (p == lastToucher) continue;

            // --- GOALKEEPER SAVE: a ball heading toward his own goal within reach is
            // saved regardless of current speed — GK reach GK_SAVE_R applies to a
            // fast shot AND a decelerated bobble, else shots bleed in. ---
            if (p.isGoalkeeper() && isTowardOwnGoal(state, p)) {
                double d = pointSegmentDist(p.getPosition().getRow(), p.getPosition().getColumn(), prev, curr);
                double t = approachT(p.getPosition().getRow(), p.getPosition().getColumn(), prev, curr);
                if (d <= GK_SAVE_R && t < bestT) {
                    bestT = t; ev = "SAVE"; hit = p;
                }
                continue;
            }

            double d = pointSegmentDist(p.getPosition().getRow(), p.getPosition().getColumn(), prev, curr);
            double t = approachT(p.getPosition().getRow(), p.getPosition().getColumn(), prev, curr);
            if (t >= bestT) continue;

            boolean sameTeam = p.getTeam().equals(state.getLastTouchTeam());
            if (sameTeam) {
                // teammate body -> deflection only
                if (d <= DEFLECT_R) {
                    bestT = t; ev = "DEFLECT"; hit = p;
                }
            } else {
                // Opponent body. A defender within 2 m of the line can only READ
                // the ball (probabilistic, pm+def gated); the ball physically
                // strikes them only within 1 m (guaranteed deflection). This is
                // the demo/service model — a fast pass past a mid-distance
                // defender normally sails by, it is not auto-intercepted.
                if (d <= INTERCEPT_R) {
                    if (d <= DEFLECT_R) {
                        // Physical contact — ball strikes the body.
                        if (readIntercept(state, p, spd)) {
                            bestT = t; ev = "INTERCEPT"; hit = p;
                        } else {
                            bestT = t; ev = "DEFLECT"; hit = p;
                        }
                    } else if (readIntercept(state, p, spd)) {
                        // 1-2 m off the line: only a genuine read beats the ball.
                        bestT = t; ev = "INTERCEPT"; hit = p;
                    }
                    // else: too far to touch / no read — ball sails by.
                }
            }
        }

        if (ev != null) {
            if (ev.equals("RECEIVE") || ev.equals("INTERCEPT")) {
                state.setCarrier(hit);
                state.setLastTouchTeam(hit.getTeam());
                state.setPendingReceiver(null);
                state.setReceivePoint(null);
                state.getBall().setPosition(new Position(hit.getPosition().getRow(), hit.getPosition().getColumn()));
                state.getBall().stop();
                if (ev.equals("RECEIVE")) return BallStepResult.receive(hit.getLabel());
                return BallStepResult.intercept(hit.getLabel());
            } else if (ev.equals("SAVE")) {
                // Goalkeeper saves the shot — he holds the ball (distribution follows)
                state.setCarrier(hit);
                state.setLastTouchTeam(hit.getTeam());
                state.setPendingReceiver(null);
                state.setReceivePoint(null);
                state.getBall().setPosition(new Position(hit.getPosition().getRow(), hit.getPosition().getColumn()));
                state.getBall().stop();
                return BallStepResult.save(hit.getLabel());
            } else {
                // BLOCK or DEFLECT — reflect off player body
                Ball b = state.getBall();
                double nr = hit.getPosition().getRow() - curr.getRow();
                double nc = hit.getPosition().getColumn() - curr.getColumn();
                double len = Math.hypot(nr, nc);
                if (len > 1e-9) {
                    nr /= len; nc /= len;
                    double dot = b.getVelX() * nc + b.getVelY() * nr;
                    b.setVelocity(
                            (b.getVelX() - 2 * dot * nc) * BOUNCE_DAMP,
                            (b.getVelY() - 2 * dot * nr) * BOUNCE_DAMP
                    );
                }
                if (ev.equals("BLOCK")) return BallStepResult.block(hit.getLabel());
                return BallStepResult.deflect(hit.getLabel());
            }
        }
        return null;
    }

    private boolean isTowardOwnGoal(MatchState state, Player gk) {
        double velY = state.getBall().getVelY();
        return "HOME".equals(gk.getTeam()) ? velY < 0 : velY > 0;
    }

    /**
     * Reading interception — demo/service model. A defender within 1-2 m of the
     * flight line can only beat the ball if he READS it: playmaking+defending
     * (reading skill) vs a speed-gated probability. Fast passes give almost no
     * reaction time; slow ones let good readers step in. No read = ball sails by.
     */
    private boolean readIntercept(MatchState state, Player p, double spd) {
        // Use the LAUNCH speed, not the current decelerated speed (demo/service
        // model): a reception-speed ball that has slowed near the receiver must
        // not suddenly become interceptable — the read difficulty was decided
        // when the pass was struck.
        double effective = Math.max(spd, state.getBall().getLaunchSpeed());
        double speedFactor = Math.max(0.2, 1.0 - (effective - MIN_LAUNCH_SPEED)
                        / (MAX_BALL_SPEED - MIN_LAUNCH_SPEED));
        int pm = (int) Math.round(p.getSkills().playmaking());
        int def = (int) Math.round(p.getSkills().defender());
        if (pm + def <= 18) return false;   // can't read the pass
        double prob = Math.min(0.45, (0.25 + (pm + def - 18) / 30.0) * speedFactor);
        return RNG.nextDouble() < prob;
    }

    private Player nearestPlayer(MatchState state, String exceptTeam, double maxR) {
        Player best = null; double bestD = maxR;
        for (Player p : state.getPlayers()) {
            if (exceptTeam != null && p.getTeam().equals(exceptTeam)) continue;
            if (p.isUnavailable()) continue;
            double d = Math.hypot(p.getPosition().getRow() - state.getBall().getPosition().getRow(),
                    p.getPosition().getColumn() - state.getBall().getPosition().getColumn());
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }

    // --- geometry utils ---
    private double approachT(double r, double c, Position p1, Position p2) {
        double dr = p2.getRow() - p1.getRow(), dc = p2.getColumn() - p1.getColumn();
        double len2 = dr * dr + dc * dc;
        if (len2 < 1e-9) return 0;
        return Math.max(0, Math.min(1, ((r - p1.getRow()) * dr + (c - p1.getColumn()) * dc) / len2));
    }

    private double pointSegmentDist(double r, double c, Position p1, Position p2) {
        double t = approachT(r, c, p1, p2);
        double rr = p1.getRow() + (p2.getRow() - p1.getRow()) * t;
        double cc = p1.getColumn() + (p2.getColumn() - p1.getColumn()) * t;
        return Math.hypot(r - rr, c - cc);
    }
}