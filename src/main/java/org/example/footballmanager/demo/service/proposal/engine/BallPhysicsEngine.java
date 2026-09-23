package org.example.footballmanager.demo.service.proposal.engine;

import org.example.footballmanager.demo.service.proposal.model.*;
import org.example.footballmanager.demo.service.proposal.util.SimUtils;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.HashMap;

/**
 * Ball physics engine — pure physics, no decision-making, no rules enforcement.
 * Ball moves with initial velocity, decelerates to 0, collides with posts/players,
 * detects goal plane crossing, and handles OOB 4-tick visible hold.
 * The engine NEVER knows who called the kick; it only reads carrier/pendingReceiver/
 * lastTouchTeam from MatchState.
 */
public class BallPhysicsEngine implements BallEngine {

    private static final Random RNG = new Random();

    /**
     * Once-per-pass read cache (P6). The demo/service javadoc contract says a
     * pass's intercept READ "was decided when the pass was struck" — but the old
     * code re-rolled INTERCEPT_R (2 m) lane defenders EVERY flight tick
     * (~10-25 ticks x 1-2 defenders, prob up to 0.45/tick). Cumulative
     * P(intercept) = 1-(1-p)^n ≈ 75-95% → passes died → 67% completion.
     * Instance is news'd once per match (MatchOrchestrator:67; per-match at 5
     * sites) so per-flight state is safe. Decided ONCE per defender per pass at
     * first lane contact, cached for the whole flight, cleared at launch() —
     * the strike boundary (each NEW pass re-decides from scratch).
     */
    private final Map<String, Boolean> passReadDecisions = new HashMap<>();

    /** Consecutive ticks a stopped loose ball has gone unpicked (DEAD-WATCH diag). */
    private int deadTickCount;

    // --- Physics constants (cells/tick @ 40 TPM = 1 tick = 1.5 s match time) ---
    public static final double MAX_BALL_SPEED = 1.5;      // 14 m/s
    public static final double MIN_LAUNCH_SPEED = 0.75;   // 7 m/s
    public static final double GROUND_DECEL = 0.35;       // ~2.2 m/s^2
    public static final double AIR_DECEL = 0.15;          // ~0.9 m/s^2
    public static final double STOP_SPEED = 0.02;
    public static final double LANDING_SPEED = 0.30;      // air -> ground transition
    public static final double FAST_CONTACT = 1.0;        // >= this: BLOCK (parry), <: INTERCEPT

    // --- Contact radii (cells) ---
    /**
     * RIGID RULE (user 2026-09-17): a player is "ON the ball" — allowed to
     * decide/execute PASS/SHOT/DRIBBLE/CLEAR — only when physically within
     * this distance of the ball's position. No action ever starts with the
     * ball teleported onto the player; the player walks onto the ball first.
     * 0.05 cells ≈ 0.7 m — visually the player dot is on the ball dot.
     */
    public static final double ON_BALL_EPS = 0.05;
    public static final double RECEIVE_R = 0.35;
    public static final double INTERCEPT_R = 0.14;   // 2 m — reading lane (probabilistic)
    public static final double DEFLECT_R = 0.05;    // 0.5 m — ball physically strikes the body
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

        // 0. OOB BALL IS ALWAYS DEAD (user 2026-09-23). The moment the ball is
        // physically outside the field — regardless of who "holds" it — play
        // stops and the referee restart is prepared. Previously the OOB check
        // only ran in the MOVING/no-carrier path, so a RECEIVE that landed just
        // outside the touchline (a pass deviation can land at col 0.8) gave
        // possession of an OOB ball. With players now field-clamped the carrier
        // could never step onto it (ON_BALL_EPS unreachable), so the re-decision
        // gate never fired and the carrier "held" the ball for the rest of the
        // half (AWAY-7 held 3443 ticks on an OOB ball). Centralizing the OOB
        // check at tick start makes possession impossible while the ball is out.
        if (state.getEnvironment().isOOB(ball.getPosition())) {
            state.setCarrier(null);
            state.setPendingReceiver(null);
            state.setReceivePoint(null);
            ball.stop();
            return oobHoldOrRestart(state);
        }

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
            // Pickup by nearest player. A PENDING receiver that physically reaches the
            // ball gets it first — the pass completed him (RIGID RULE: the ball is
            // never carried to him; he comes to the ball). If he never gets there,
            // the nearest available player picks the dead ball up as LOOSE.
            Player pending = state.getPendingReceiver();
            if (pending != null
                    && SimUtils.distance(pending.getPosition(), ball.getPosition()) <= PICKUP_R) {
                deadTickCount = 0;
                state.setCarrier(pending);
                state.setLastTouchTeam(pending.getTeam());
                state.setPendingReceiver(null);
                state.setReceivePoint(null);
                ball.stop();
                return BallStepResult.receive(pending.getLabel());
            }
            Player near = nearestPlayer(state, null, PICKUP_R);
            if (near != null) {
                deadTickCount = 0;
                state.setCarrier(near);
                state.setLastTouchTeam(near.getTeam());
                state.setPendingReceiver(null);
                state.setReceivePoint(null);
                ball.stop();
                return BallStepResult.loosePickup(near.getLabel());
            }
            // Ball is dead and nobody within reach can receive/pick it up this tick.
            // Drop any stale pending receiver NOW, or the loose-ball chase (which is
            // suppressed while a pending receiver exists) would never recover the
            // ball and the match would freeze for the rest of the half.
            state.setPendingReceiver(null);
            state.setReceivePoint(null);
            ball.stop();
            // DEAD-WATCH (diagnostic): a stopped loose ball with no one within pickup
            // reach must be recovered by the Movement Engine's chaser shortly. If it
            // stays dead for many ticks, log the exact suppression state every 30
            // ticks so the freeze root cause is visible in the event log.
            deadTickCount++;
            if (deadTickCount % 30 == 3) {
                Player taker = state.getRestartTaker();
                String nearest = nearest3(state);
                if (state.getActionLogger() != null) {
                    state.getActionLogger().log("BAL",
                            "DEAD-WATCH dead=" + deadTickCount + " carrier="
                                    + (state.getCarrier() == null ? "null" : state.getCarrier().getLabel())
                                    + " pending=" + (pending == null ? "null" : pending.getLabel())
                                    + " restartTaker=" + (taker == null ? "null" : taker.getLabel())
                                    + " oob=" + state.getOobPending()
                                    + " speed=" + String.format("%.3f", ball.getSpeed())
                                    + " | " + nearest);
                }
            }
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
            // RIGID RULE: keep the pending receiver — the pass physically stopped,
            // next tick the stopped-ball pickup either completes it (receiver
            // reached the ball) or it becomes a LOOSE recovery by the nearest player.
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
            // RIGID RULE (user 2026-09-23): the ball FREEZES at the crossing
            // point — it must not keep rolling along the OOB zone during the
            // visible hold. Previously the ball kept its velocity for the
            // 4-tick hold, sliding far outside and parallel to the touchline
            // (e.g. exit at (6.9,0.99) then 4 ticks later at (1.7,0.75)); the
            // restart spot was then computed from that DRIFTED position, so the
            // throw-in restarted up to 5 cells away from where the ball went
            // out — the "ball restarts backward, then flies forward along the
            // OOB zone" artifact the user reported.
            ball.stop();
            state.setPendingReceiver(null);
            state.setReceivePoint(null);
            String restart = env.oobRestartType(state.getLastTouchTeam(), curr);
            state.setOobPending(restart);
            state.setOobHoldTicks(OOB_HOLD_TICKS);
            return BallStepResult.oobEnter(restart);
        }
        if (state.getOobPending() != null) {
            return oobHoldOrRestart(state);
        }

        // Shared action logger — during an unattended flight (carrier null, ball
        // moving, no OOB) log the ball A→B so the "ball with no one on it" phase
        // is attributable: who it is aimed at (pendingReceiver), where it is and
        // how far the receiver still is from the landing point.
        if (state.getActionLogger() != null && state.getCarrier() == null && spd > STOP_SPEED) {
            Player recv = state.getPendingReceiver();
            Position land = state.getReceivePoint();
            state.getActionLogger().log("BAL",
                    "flight " + state.getActionLogger().p(ball.getPosition())
                            + " speed " + String.format("%.2f", ball.getSpeed())
                            + " airborne " + ball.isAirborne()
                            + (recv != null && land != null
                            ? " -> " + recv.getLabel() + "(" + recv.getRole() + ")"
                            + " land " + state.getActionLogger().p(land)
                            + " recvDist " + String.format("%.2f",
                            SimUtils.distance(ball.getPosition(), land))
                            : " (no receiver set)"));
        }

        return BallStepResult.flight();
    }

    /**
     * Shared OOB hold/restart flow (used by the centralized tick-start guard and
     * the moving-ball step-10 detection). Returns the pending-hold step each
     * tick until the hold expires, then fires the restart. Because the ball is
     * frozen at the crossing point (see step 10), the exit position stays the
     * true crossing point and never drifts down the touchline.
     */
    private BallStepResult oobHoldOrRestart(MatchState state) {
        String pending = state.getOobPending();
        if (pending == null) {
            String restart = state.getEnvironment()
                    .oobRestartType(state.getLastTouchTeam(), state.getBall().getPosition());
            state.setOobPending(restart);
            state.setOobHoldTicks(OOB_HOLD_TICKS);
            return BallStepResult.oobEnter(restart);
        }
        state.decrementOobHold();
        if (state.getOobHoldTicks() <= 0) {
            state.clearOobPending();
            return BallStepResult.oobRestart(pending);
        }
        return BallStepResult.oobHold(pending, state.getOobHoldTicks());
    }

    /** Launch the ball toward an aim point with given speed (cells/tick), air/ground, spin. */
    public void launch(Ball ball, Position origin, Position aim, double speed, boolean airborne, double spin) {
        deadTickCount = 0;
        ball.setPosition(origin);
        // P6: this is the STRIKE boundary. Each NEW pass flight re-decides the
        // read for every lane defender from scratch — never carries the previous
        // flight's (now stale) read cache into flight.
        passReadDecisions.clear();
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
            if (ev.equals("RECEIVE")) {
                state.setCarrier(hit);
                state.setLastTouchTeam(hit.getTeam());
                state.setPendingReceiver(null);
                state.setReceivePoint(null);
                // RIGID RULE (user 2026-09-17): the ball stays where it physically
                // stopped — no teleport onto the receiver. The receiver becomes the
                // carrier and the Movement Engine walks him onto the ball before any
                // action starts.
                state.getBall().stop();
                return BallStepResult.receive(hit.getLabel());
            }
            if (ev.equals("INTERCEPT")) {
                // Interception is a physical contact: the defender steps into the
                // flight lane and traps the ball at his body (ball may be at most
                // INTERCEPT_R from him along the segment).
                state.setCarrier(hit);
                state.setLastTouchTeam(hit.getTeam());
                state.setPendingReceiver(null);
                state.setReceivePoint(null);
                state.getBall().setPosition(new Position(hit.getPosition().getRow(), hit.getPosition().getColumn()));
                state.getBall().stop();
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
                // BLOCK or DEFLECT — reflect off player body. RIGID RULE (user
                // 2026-09-23): the deflection must be PHYSICAL. (a) The ball is
                // placed at the CONTACT POINT on the flight segment (where it
                // touched the body), not at the segment end — previously the
                // reflect used `curr`, so a teammate standing AT the launch
                // origin of a kick bounced the ball back the very first tick
                // with the ball visually never touching anyone ("DEFLECT off H1
                // from air"). (b) A contact at the very START of the segment (a
                // teammate co-located with the kicker) is NOT a deflection — the
                // ball is just leaving that player's feet — so it is skipped.
                Ball b = state.getBall();
                double contactT = bestT;
                Position contact = new Position(
                        prev.getRow() + (curr.getRow() - prev.getRow()) * contactT,
                        prev.getColumn() + (curr.getColumn() - prev.getColumn()) * contactT
                );
                double traveled = Math.abs(SimUtils.distance(contact, prev));
                if (traveled <= 0.05) {
                    // Ball hasn't reached the body — the contact is the launch
                    // origin (players stacked on the kicker). Let the ball fly.
                    return null;
                }
                b.setPosition(contact);
                double nr = hit.getPosition().getRow() - contact.getRow();
                double nc = hit.getPosition().getColumn() - contact.getColumn();
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
        // P6: decide ONCE per defender per pass. The read difficulty was decided
        // WHEN the pass was struck — never re-rolled per flight tick (that gave
        // cumulative 1-(1-p)^n ≈ 75-95% mid-flight → 67% completion). Keyed by
        // defender label so each nearest-lane defender gets ONE roll per pass;
        // cache cleared at launch() (the strike boundary) so each NEW flight
        // re-decides from scratch. Matches /demo/ (98%).
        String key = p.getLabel();
        Boolean cached = passReadDecisions.get(key);
        if (cached != null) return cached;
        boolean decision = RNG.nextDouble() < prob;
        passReadDecisions.put(key, decision);
        return decision;
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

    /** DEAD-WATCH aid: nearest 3 available players to the ball with distances. */
    private String nearest3(MatchState state) {
        List<Player> sorted = new java.util.ArrayList<>(state.getPlayers());
        sorted.removeIf(Player::isUnavailable);
        double bx = state.getBall().getPosition().getColumn();
        double by = state.getBall().getPosition().getRow();
        sorted.sort(java.util.Comparator.comparingDouble(
                p -> Math.hypot(p.getPosition().getRow() - by, p.getPosition().getColumn() - bx)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            Player p = sorted.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(p.getLabel()).append("(").append(p.getRole()).append(")")
                    .append(p.isLocked() ? "[LOCK]" : "")
                    .append(" d=").append(String.format("%.2f", Math.hypot(
                    p.getPosition().getRow() - by, p.getPosition().getColumn() - bx)));
        }
        return sb.toString();
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