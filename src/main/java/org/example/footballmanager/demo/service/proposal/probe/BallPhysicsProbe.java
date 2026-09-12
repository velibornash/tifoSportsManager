package org.example.footballmanager.demo.service.proposal.probe;

import org.example.footballmanager.demo.service.proposal.model.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * TEMP standalone physics probe (per user workflow: implement in a temp class,
 * test + analyze callers, then transplant into the real engine).
 *
 * Mirrors the planned BallPhysicsEngine design for the new ball model:
 *   - ball is launched with an AIM point + SPEED (7-14 m/s match time,
 *     0.75-1.5 cells/tick at 40 TPM); it strives TOWARD the aim,
 *     decelerating gradually to a stop (it may never reach the aim).
 *   - direction = aim (user rule: "lopta mora da ima smer/pravac/bar cilj
 *     ka kojem tezi, bez obzira hoce li stici tamo").
 *   - per tick: spin -> decel -> move -> landing -> post hit -> player
 *     contact (receive/intercept/deflect, earliest-along-segment wins) ->
 *     goal plane -> OOB (visible 4-tick hold, no instant teleport).
 *   - the ball engine never knows who called the kick; it only reads
 *     carrier / pendingReceiver / lastTouchTeam from the calling state.
 *
 * Run: mvn exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.probe.BallPhysicsProbe
 */
public class BallPhysicsProbe {

    // ---- authoritative physics constants (cells/tick @ 40 TPM: 1 tick = 1.5 s) ----
    static final double MAX_SPEED = 1.5;          // 14 m/s
    static final double MIN_SPEED = 0.75;         // 7 m/s
    static final double GROUND_DECEL = 0.35;      // ~2.2 m/s^2
    static final double AIR_DECEL = 0.15;         // ~0.9 m/s^2
    static final double STOP_SPEED = 0.02;
    static final double LANDING_SPEED = 0.30;     // air ball lands below this
    static final double RECEIVE_R = 0.35;
    static final double INTERCEPT_R = 0.30;
    static final double DEFLECT_R = 0.18;
    static final double FAST_CONTACT = 1.0;      // faster balls get BLOCKed (parry), not possessed
    static final double PICKUP_R = 0.35;
    static final double BALL_R = 0.015;
    static final double POST_R = 0.03;
    static final int OOB_HOLD_TICKS = 4;
    static final double BOUNCE_DAMP = 0.5;
    static final double SPIN_LAT = 0.05;

    // ---- pitch geometry (authoritative) ----
    static final double HOME_GOAL_LINE = 1.0;
    static final double AWAY_GOAL_LINE = 8.0;
    static final double MOUTH_LEFT = 3.5;
    static final double MOUTH_RIGHT = 4.5;
    static final double OOB_ROW_MIN = 0.99;
    static final double OOB_ROW_MAX = 8.01;
    static final double OOB_COL_MIN = 0.99;
    static final double OOB_COL_MAX = 7.01;

    // ---- minimal scene objects keeping the probe self-contained ----
    static class P { // player lite
        final String name; final String team; final double row, col;
        P(String n, String t, double r, double c) { name = n; team = t; row = r; col = c; }
    }

    static class B { // ball lite: physics-only (pos + velocity + spin + air/ground)
        Position pos; double vx, vy, spin; boolean airborne;
        double speed() { return Math.hypot(vx, vy); }
        void stop() { vx = 0; vy = 0; }
    }

    static class H { // hold state for OOB (mirrors planned MatchState fields)
        String pending = null;  // restart type while the ball is visibly OOB
        int ticks = 0;
    }

    static class L { // scene lite (mirrors MatchState fields the ball engine reads)
        final B ball = new B();
        P carrier; P pendingReceiver;
        String lastTouchTeam;
        final List<P> players = new ArrayList<>();
        final H oob = new H();
    }

    static class Step {
        final String event; final String detail;
        Step(String e, String d) { event = e; detail = d; }
    }

    // ---- geometry helpers ----
    static boolean inMouth(double col) { return col >= MOUTH_LEFT && col <= MOUTH_RIGHT; }
    static boolean hitsPost(double row, double col) {
        return postSegmentHit(new Position(row - 0.01, col), new Position(row + 0.01, col));
    }
    static double dist(double r1, double c1, double r2, double c2) { return Math.hypot(r2 - r1, c2 - c1); }

    /** Parameter t (0..1) of the closest point on segment (p1->p2) to (r,c). */
    static double approachT(double r, double c, Position p1, Position p2) {
        double dr = p2.getRow() - p1.getRow(), dc = p2.getColumn() - p1.getColumn();
        double len2 = dr * dr + dc * dc;
        if (len2 < 1e-9) return 0;
        return Math.max(0, Math.min(1, ((r - p1.getRow()) * dr + (c - p1.getColumn()) * dc) / len2));
    }

    static double pointSegmentDist(double r, double c, Position p1, Position p2) {
        double t = approachT(r, c, p1, p2);
        return dist(r, c, p1.getRow() + (p2.getRow() - p1.getRow()) * t,
                    p1.getColumn() + (p2.getColumn() - p1.getColumn()) * t);
    }

    static boolean postSegmentHit(Position p1, Position p2) {
        for (double line : new double[]{HOME_GOAL_LINE, AWAY_GOAL_LINE}) {
            for (double postCol : new double[]{MOUTH_LEFT, MOUTH_RIGHT}) {
                if (pointSegmentDist(line, postCol, p1, p2) <= POST_R + BALL_R) return true;
            }
        }
        return false;
    }

    static double[] postNormal(double row, double col) {
        double br = -1, bc = -1, best = Double.MAX_VALUE;
        for (double line : new double[]{HOME_GOAL_LINE, AWAY_GOAL_LINE}) {
            for (double postCol : new double[]{MOUTH_LEFT, MOUTH_RIGHT}) {
                double d = dist(row, col, line, postCol);
                if (d < best) { best = d; br = row - line; bc = col - postCol; }
            }
        }
        return new double[]{br, bc};
    }

    static double[] reflect(double vx, double vy, double nr, double nc) {
        double len = Math.hypot(nr, nc);
        if (len < 1e-9) return new double[]{-vx, -vy};
        nr /= len; nc /= len;
        double dot = vx * nc + vy * nr;
        return new double[]{vx - 2 * dot * nc, vy - 2 * dot * nr};
    }

    static boolean isOOB(Position p) {
        return p.getRow() <= OOB_ROW_MIN || p.getRow() >= OOB_ROW_MAX
                || p.getColumn() <= OOB_COL_MIN || p.getColumn() >= OOB_COL_MAX;
    }

    static Position crossingPos(Position p1, Position p2, double lineRow) {
        double dr = p2.getRow() - p1.getRow();
        if (Math.abs(dr) < 1e-9) return p1;
        double t = (lineRow - p1.getRow()) / dr;
        return new Position(lineRow, p1.getColumn() + (p2.getColumn() - p1.getColumn()) * t);
    }

    /** Returns the attacking label if the segment crosses a goal mouth; else null. */
    static String goalCrossing(Position p1, Position p2) {
        if (p1.getRow() < AWAY_GOAL_LINE && p2.getRow() >= AWAY_GOAL_LINE) {
            Position c = crossingPos(p1, p2, AWAY_GOAL_LINE);
            if (inMouth(c.getColumn())) return "AWAY"; // goal at row 8 (defended by AWAY)
        }
        if (p1.getRow() > HOME_GOAL_LINE && p2.getRow() <= HOME_GOAL_LINE) {
            Position c = crossingPos(p1, p2, HOME_GOAL_LINE);
            if (inMouth(c.getColumn())) return "HOME"; // goal at row 1 (defended by HOME)
        }
        return null;
    }

    static String oobRestartType(String lastTouch, Position p) {
        boolean behindHome = p.getRow() <= OOB_ROW_MIN;
        boolean behindAway = p.getRow() >= OOB_ROW_MAX;
        if (behindHome) return "HOME".equals(lastTouch) ? "CORNER_AWAY" : "GOAL_KICK_HOME";
        if (behindAway) return "HOME".equals(lastTouch) ? "GOAL_KICK_AWAY" : "CORNER_HOME";
        return "HOME".equals(lastTouch) ? "THROW_IN_AWAY" : "THROW_IN_HOME";
    }

    // ---- bare-physics sweep: NO players, one sender. Verify pure deceleration.
    static String flyTo(L s, Position aim, double speed, boolean air) {
        Step last = new Step("FLIGHT", "");
        for (int t = 0; t < 120; t++) {
            last = step(s);
            if (last.event.equals("STOPPED") || last.event.equals("IDLE_STOP")
                    || last.event.equals("GOAL") || last.event.equals("OOB_RESTART")
                    || last.event.equals("POST_HIT") && s.ball.speed() <= STOP_SPEED
                    || last.event.equals("FLIGHT") && s.ball.speed() <= STOP_SPEED) {
                break;
            }
        }
        return "arrive " + last.event + " at " + fmt(s.ball.pos) + " v=" + fmt(s.ball.speed());
    }

    static void flyTable(String title, Position origin, double speed,
                         double[][] aims, boolean air) {
        System.out.println("\n=== " + title + " (speed " + speed + ") ===");
        for (double[] a : aims) {
            L s = new L();
            s.lastTouchTeam = "HOME";
            launch(s, origin, new Position(a[0], a[1]), speed, air, 0);
            System.out.printf("  aim (%.1f,%.1f) -> %s%n", a[0], a[1], flyTo(s, null, 0, false));
        }
    }
    static Step step(L s) {
        B b = s.ball;

        // 1. possession
        if (s.carrier != null) return new Step("POSSESS", "ball glued to carrier");

        // 2. stopped loose ball -> pickup only (OOB hold keeps counting down!)
        if (b.speed() <= STOP_SPEED) {
            if (s.oob.pending != null) {
                s.oob.ticks--;
                if (s.oob.ticks <= 0) { String due = s.oob.pending; s.oob.pending = null; return new Step("OOB_RESTART", due); }
                return new Step("OOB_HOLD", s.oob.pending + " t=" + s.oob.ticks);
            }
            P near = nearest(s, null, PICKUP_R);
            if (near != null) { s.carrier = near; s.lastTouchTeam = near.team; return new Step("LOOSE_PICKUP", near.name); }
            b.stop(); return new Step("IDLE_STOP", "loose at rest");
        }

        Position prev = b.pos;

        // 3. spin -> lateral bend of the heading
        if (Math.abs(b.spin) > 1e-9) {
            double a = b.spin * SPIN_LAT;
            double nx = b.vx * Math.cos(a) - b.vy * Math.sin(a);
            double ny = b.vx * Math.sin(a) + b.vy * Math.cos(a);
            b.vx = nx; b.vy = ny;
        }

        // 4. decel (ground vs air), heading preserved, stop at 0
        double spd = b.speed();
        spd = Math.max(0, spd - (b.airborne ? AIR_DECEL : GROUND_DECEL));
        if (spd <= STOP_SPEED) { b.stop(); b.airborne = false; return new Step("STOPPED", b.pos + " v=0"); }
        double old = b.speed();
        b.vx *= spd / old; b.vy *= spd / old;

        // 5. move
        b.pos = new Position(b.pos.getRow() + b.vy, b.pos.getColumn() + b.vx);

        // 6. landing
        if (b.airborne && spd < LANDING_SPEED) b.airborne = false;

        // 7. post hit BEFORE goal plane (post sits exactly on the line)
        if (postSegmentHit(prev, b.pos)) {
            double[] n = postNormal(b.pos.getRow(), b.pos.getColumn());
            double[] v = reflect(b.vx, b.vy, n[0], n[1]);
            b.vx = v[0] * BOUNCE_DAMP; b.vy = v[1] * BOUNCE_DAMP;
            b.airborne = false;
            return new Step("POST_HIT", "deflect slow=" + fmt(b.speed()));
        }

        // 8. player contact - earliest along the traversed segment wins;
        //    pending receiver has tie priority (intended first touch);
        //    fast balls (>FAST_CONTACT) can't be possessed -> BLOCK/deflect
        //    (real: power shots are parried, not caught/controlled).
        {
            double bestT = Double.MAX_VALUE; String ev = null; P p1 = null;
            boolean fast = spd >= FAST_CONTACT;
            if (s.pendingReceiver != null
                    && pointSegmentDist(s.pendingReceiver.row, s.pendingReceiver.col, prev, b.pos) <= RECEIVE_R) {
                bestT = approachT(s.pendingReceiver.row, s.pendingReceiver.col, prev, b.pos);
                ev = "RECEIVE"; p1 = s.pendingReceiver;
            }
            for (P p : s.players) {
                if (p == s.pendingReceiver) continue;
                if (p.team.equals(s.lastTouchTeam)) { // teammate body -> deflection only
                    if (pointSegmentDist(p.row, p.col, prev, b.pos) <= DEFLECT_R
                            && approachT(p.row, p.col, prev, b.pos) < bestT) {
                        bestT = approachT(p.row, p.col, prev, b.pos);
                        ev = "DEFLECT"; p1 = p;
                    }
                } else { // opponent body
                    double d = pointSegmentDist(p.row, p.col, prev, b.pos);
                    double t = approachT(p.row, p.col, prev, b.pos);
                    if (t >= bestT) continue;
                    if (d <= INTERCEPT_R && !fast) {
                        bestT = t; ev = "INTERCEPT"; p1 = p;
                    } else if (d <= (fast ? DEFLECT_R : INTERCEPT_R)) {
                        bestT = t; ev = "BLOCK"; p1 = p;
                    } else if (d <= DEFLECT_R) {
                        bestT = t; ev = "DEFLECT"; p1 = p;
                    }
                }
            }
            if (ev != null) {
                if (ev.equals("RECEIVE") || ev.equals("INTERCEPT")) {
                    s.carrier = p1; s.lastTouchTeam = p1.team; s.pendingReceiver = null;
                    b.stop(); b.pos = new Position(p1.row, p1.col);
                } else {
                    double[] n = { p1.row - b.pos.getRow(), p1.col - b.pos.getColumn() };
                    double[] v = reflect(b.vx, b.vy, n[0], n[1]);
                    b.vx = v[0] * BOUNCE_DAMP; b.vy = v[1] * BOUNCE_DAMP;
                }
                return new Step(ev, p1.name);
            }
        }

        // 9. goal plane crossing (only if a side last touched the ball)
        String goal = goalCrossing(prev, b.pos);
        if (goal != null && s.lastTouchTeam != null) {
            s.oob.pending = null; s.oob.ticks = 0;
            b.stop();
            return new Step("GOAL", s.lastTouchTeam + " score (crossed " + goal + " mouth)");
        }

        // 10. OOB zone - visible hold, NO instant teleport
        boolean out = isOOB(b.pos);
        if (out && s.oob.pending == null) {
            s.oob.pending = oobRestartType(s.lastTouchTeam, b.pos);
            s.oob.ticks = OOB_HOLD_TICKS;
            return new Step("OOB_ENTER", s.oob.pending + " hold=" + OOB_HOLD_TICKS + ", ball stays visible");
        }
        if (s.oob.pending != null) {
            if (!out) { s.oob.pending = null; s.oob.ticks = 0; return new Step("OOB_CANCEL", "rolled back in"); }
            s.oob.ticks--;
            if (s.oob.ticks <= 0) { String due = s.oob.pending; s.oob.pending = null; return new Step("OOB_RESTART", due); }
            return new Step("OOB_HOLD", s.oob.pending + " t=" + s.oob.ticks);
        }

        return new Step("FLIGHT", b.pos + " v=" + fmt(b.speed()) + (b.airborne ? " AIR" : " gnd"));
    }

    static P nearest(L s, String exceptTeam, double maxR) {
        P best = null; double bestD = maxR;
        for (P p : s.players) {
            if (p.team.equals(exceptTeam)) continue;
            double d = dist(p.row, p.col, s.ball.pos.getRow(), s.ball.pos.getColumn());
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }

    static void launch(L s, Position origin, Position aim, double speed, boolean air, double spin) {
        B b = s.ball;
        b.pos = origin;
        double dx = aim.getColumn() - origin.getColumn();
        double dy = aim.getRow() - origin.getRow();
        double len = Math.hypot(dx, dy);
        b.vx = len < 1e-9 ? 0 : dx / len * speed;
        b.vy = len < 1e-9 ? 0 : dy / len * speed;
        b.airborne = air;
        b.spin = spin;
    }

    static void run(String name, L s, int maxTicks, String stopEvent) {
        System.out.println("\n=== " + name + " ===");
        for (int t = 0; t < maxTicks; t++) {
            Step st = step(s);
            System.out.printf("t%02d  ball=%-14s v%-5s %-12s %s%n",
                    t, fmt(s.ball.pos), fmt(s.ball.speed()), st.event, st.detail);
            if (st.event.equals(stopEvent)) break;
        }
        System.out.println("END " + name + " -> " + s.ball.pos + " oob=" + s.oob.pending);
    }

    static String fmt(Position p) { return String.format("(%5.2f,%5.2f)", p.getRow(), p.getColumn()); }
    static String fmt(double v) { return String.format("%.2f", v); }

    public static void main(String[] args) {
        // S1: ground pass into receiver's stride
        L s1 = new L();
        P r1 = new P("R1", "HOME", 5.5, 4.0);
        s1.players.add(r1);
        s1.lastTouchTeam = "HOME"; s1.pendingReceiver = r1;
        launch(s1, new Position(4.5, 4.0), new Position(5.5, 4.0), 0.85, false, 0);
        run("S1 pass->RECEIVE", s1, 20, "RECEIVE");

        // S2: far-post shot with GK hugging the near post -> GOAL into the open corner
        L s2 = new L();
        s2.players.add(new P("GK", "AWAY", 7.9, 3.3)); // wrong post
        s2.lastTouchTeam = "HOME";
        launch(s2, new Position(6.5, 4.0), new Position(8.0, 4.3), 1.30, true, 0);
        run("S2 shot->GOAL far post", s2, 15, "GOAL");
        // S2b: power shot straight at a well-positioned GK -> BLOCK (parry)
        L s2b = new L();
        s2b.players.add(new P("GK", "AWAY", 7.9, 4.0));
        s2b.lastTouchTeam = "HOME";
        launch(s2b, new Position(6.5, 4.0), new Position(8.0, 4.0), 1.30, true, 0);
        run("S2b shot->GK BLOCK", s2b, 20, "BLOCK");

        // S3: shot straight at the left post -> POST_HIT bounce, rolls away
        L s3 = new L();
        s3.lastTouchTeam = "HOME";
        launch(s3, new Position(6.5, 3.5), new Position(8.0, 3.5), 1.30, true, 0);
        run("S3 post->bounce", s3, 40, "STOPPED");

        // S4: clearance (air, spin) from deep -> lands and rolls into midfield
        L s4 = new L();
        s4.lastTouchTeam = "AWAY";
        launch(s4, new Position(7.0, 4.0), new Position(1.5, 6.0), 1.45, true, 0.3);
        run("S4 clearance lands", s4, 20, "STOPPED");

        // S4b: overhit ball aimed beyond the away corner -> crosses plane outside
        //      the mouth -> OOB visible HOLD (4 ticks) -> OOB_RESTART (no teleport)
        L s4b = new L();
        s4b.lastTouchTeam = "AWAY";
        launch(s4b, new Position(6.0, 4.0), new Position(10.0, 6.8), 1.45, true, 0.2);
        run("S4b OOB hold->restart", s4b, 20, "OOB_RESTART");

        // S5: interception - defender sits on the pass lane before the receiver
        L s5 = new L();
        P r5 = new P("R5", "HOME", 5.5, 4.0);
        s5.players.add(r5);
        s5.players.add(new P("D5", "AWAY", 5.1, 4.0));
        s5.lastTouchTeam = "HOME"; s5.pendingReceiver = r5;
        launch(s5, new Position(4.5, 4.0), new Position(5.5, 4.0), 0.85, false, 0);
        run("S5 intercept-on-lane", s5, 20, "INTERCEPT");

        // S6: slow loose ball meanders and stops -> nearest picks it up
        L s6 = new L();
        s6.players.add(new P("L6A", "HOME", 6.2, 4.2));
        s6.players.add(new P("L6B", "AWAY", 5.0, 4.2));
        s6.ball.pos = new Position(6.0, 4.0);
        s6.ball.vx = 0.05; s6.ball.vy = -0.03;
        s6.lastTouchTeam = "AWAY";
        run("S6 loose->pickup", s6, 30, "LOOSE_PICKUP");

        // S7a: bare physics - NO players, vary DIRECTION only (fixed speed 0.9)
        //      aim points are a circle around origin (4.5, 4.0) at 1.5 cell radius
        double[][] dirs = {
                {5.95, 4.0}, {5.95, 5.06}, {4.5, 5.5}, {3.05, 5.06},
                {3.05, 4.0}, {3.05, 2.94}, {4.5, 2.5}, {5.95, 2.94}
        };
        flyTable("S7a vary DIRECTION (speed 0.9 ground)", new Position(4.5, 4.0), 0.90, dirs, false);

        // S7b: bare physics - vary SPEED only (fixed direction: straight toward AWAY goal at col 4.0)
        //      origin (4.5, 4.0), aim straight up to (10.0, 4.0) to cross mouth at row 8
        double[] speeds = {0.75, 0.90, 1.05, 1.20, 1.35, 1.50};
        double[][] fixedAim = {{10.0, 4.0}};
        System.out.println("\n=== S7b vary SPEED (straight toward AWAY goal col 4.0) ===");
        for (double sp : speeds) {
            L s = new L();
            s.lastTouchTeam = "HOME";
            launch(s, new Position(4.5, 4.0), new Position(10.0, 4.0), sp, false, 0);
            String r = flyTo(s, null, 0, false);
            System.out.printf("  speed %.2f -> %s%n", sp, r);
        }
    }
}