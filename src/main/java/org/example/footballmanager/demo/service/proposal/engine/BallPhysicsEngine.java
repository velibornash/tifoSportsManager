package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;

import java.util.List;

/**
 * Ball physics engine — pure physics, no decision-making, no rules enforcement.
 * Ball moves with initial velocity, decelerates to 0, collides with posts/players,
 * detects goal plane crossing, and handles OOB 4-tick visible hold.
 * The engine NEVER knows who called the kick; it only reads carrier/pendingReceiver/
 * lastTouchTeam from MatchState.
 */
public class BallPhysicsEngine implements BallEngine {

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
    public static final double INTERCEPT_R = 0.30;
    public static final double DEFLECT_R = 0.18;
    public static final double PICKUP_R = 0.35;
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
                // opponent body
                if (d <= INTERCEPT_R && !fast) {
                    bestT = t; ev = "INTERCEPT"; hit = p;
                } else if (d <= (fast ? DEFLECT_R : INTERCEPT_R)) {
                    bestT = t; ev = "BLOCK"; hit = p;
                } else if (d <= DEFLECT_R) {
                    bestT = t; ev = "DEFLECT"; hit = p;
                }
            }
        }

        if (ev != null) {
            if (ev.equals("RECEIVE") || ev.equals("INTERCEPT")) {
                state.setCarrier(hit);
                state.setLastTouchTeam(hit.getTeam());
                state.setPendingReceiver(null);
                state.getBall().setPosition(new Position(hit.getPosition().getRow(), hit.getPosition().getColumn()));
                state.getBall().stop();
                if (ev.equals("RECEIVE")) return BallStepResult.receive(hit.getLabel());
                return BallStepResult.intercept(hit.getLabel());
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