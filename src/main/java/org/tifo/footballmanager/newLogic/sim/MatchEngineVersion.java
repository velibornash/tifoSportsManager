package org.tifo.footballmanager.newLogic.sim;

import org.tifo.footballmanager.newLogic.sim.engine.BallPhysicsEngine;
import org.tifo.footballmanager.newLogic.sim.engine.DuelEngine;
import org.tifo.footballmanager.newLogic.sim.engine.MovementEngine;
import org.tifo.footballmanager.newLogic.sim.engine.StoppageClock;
import org.tifo.footballmanager.newLogic.sim.engine.ThreatOverrideEngine;
import org.tifo.footballmanager.newLogic.sim.engine.decision.CleanDecisionEngine;
import org.tifo.footballmanager.newLogic.sim.rules.DisciplineService;
import org.tifo.footballmanager.newLogic.sim.rules.OffsideService;
import org.tifo.footballmanager.newLogic.sim.tactics.DefensiveShape;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which rules produced a match (owner, 2026-10-18).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The engine is deterministic: {@code SimulationRandom.seed(fixture.getId())} means the same
 * fixture played under the same build always gives the same football. That is a strength until
 * somebody changes a constant. Then the determinism becomes a liability — nothing in the database
 * can say which tuning produced a stored result, so:
 *
 * <ul>
 *   <li>a manager asking "why did my 4-3-3 concede from that corner?" cannot be answered;</li>
 *   <li>re-simulating a fixture after a rule change quietly produces a <em>different</em> match
 *       rather than an error;</li>
 *   <li>a calibration document and a season of stored results cannot be held against each other,
 *       because one of them names a ruleset and the other does not.</li>
 * </ul>
 *
 * <h2>Why a fingerprint and not only a version string</h2>
 *
 * <p>A hand-written version goes stale the moment a constant moves without it, and a stale version
 * is worse than none — it is confidently wrong. So this class reads the constants that actually move
 * the output and hashes them. The fingerprint changes when the tuning changes whether or not anyone
 * remembered to bump {@link #VERSION}.
 *
 * <p><b>The rule is: bump {@code VERSION} when a change is deliberate and user-visible; the
 * fingerprint tells you when it happened anyway.</b> A row carrying the same version as the code but
 * a different fingerprint is the signal that a constant moved without a decision being recorded.
 *
 * <p>The list is deliberately the <em>output-moving</em> constants — the gates, the radii, the
 * physics, the home advantage. It is not every number in the engine: a constant that cannot change a
 * scoreline does not need to be in a season's audit trail. Adding one is a one-line change here and
 * nothing else, because {@link #rules()} is a plain map.
 *
 * <p>The constants are read live rather than copied. A copied list is a second record of the same
 * numbers, and the two would disagree the first time someone tuned one without remembering the
 * other — which is the failure this class exists to prevent.
 */
public final class MatchEngineVersion {

    private MatchEngineVersion() {
    }

    /**
     * Manually bumped when a change is deliberate and user-visible. Format {@code YYYY.MM.DD-n}
     * where {@code n} counts the changes that day.
     *
     * <p><b>-2, 2026-10-18:</b> every decision option moved onto one 0..100 scale, the pass learned
     * who it was being passed to, the shot gate was armed on arrival and stopped gating a good
     * chance, the "lane jammed" veto became a corner test that ignores the goalkeeper, and the
     * final-two-rows rule was removed as dead code. The football changed; the scoreline changed with
     * it, so a stored match from -1 must never be read as -2.
     *
     * <p><b>-3, 2026-10-18:</b> the two threat-override rules rewritten to the owner's specification —
     * press a <em>free</em> carrier in an adjacent cell from any outfield position, and cover a
     * goal-side attacker in the danger zone — plus the engine fingerprint finally covering the
     * defending rules at all. Match shots 41.0 -> 34.6 from that change alone.
     *
     * <p><b>-1, 2026-10-19:</b> five rule changes, all of which a scoreline can see, and all of
     * which were found by reading the rules against the code rather than by watching a match.
     * <b>The offside rule:</b> a goal by a player who was left in the marginal band now goes to VAR
     * before it counts (the deferred check had no writer and could never fire), and a cross or a
     * centre is now ruled offside like any other delivery (it bypassed the check entirely, so a
     * runner a metre beyond the second-to-last defender received it normally and could score).
     * <b>The press:</b> the danger zone is now measured from each side's own goal line, and HOME's
     * was half the size — over 20 matches, HOME got 464 presses and AWAY 3,410, which is not a game.
     * <b>The retreat:</b> the offside line counts the goalkeeper again, so the two halves of the
     * retreat rule agree. <b>The restarts:</b> a restart nobody can take no longer switches the
     * offside check off for the rest of the match. A stored match from -3 must never be read as -1.
     */
    public static final String VERSION = "2026.10.19-1";

    /**
     * Every constant that can change a scoreline, read live.
     *
     * <p>Ordered by name so the fingerprint does not depend on declaration order, and so a diff of two
     * fingerprints is readable.
     */
    public static Map<String, String> rules() {
        Map<String, String> r = new LinkedHashMap<>();

        // --- Decision gates. The five numbers that decide how often each thing happens. ---
        r.put("decision.shotGate", num(CleanDecisionEngine.SHOT_FREQUENCY_GATE));
        r.put("decision.clearGate", num(CleanDecisionEngine.CLEAR_FREQUENCY_GATE));
        r.put("decision.thruGate", num(CleanDecisionEngine.THRU_FREQUENCY_GATE));
        r.put("decision.deliveryGate", num(CleanDecisionEngine.DELIVERY_FREQUENCY_GATE));
        r.put("decision.offsideHardLimit", num(CleanDecisionEngine.OFFSIDE_HARD_LIMIT));

        // --- The offside rule itself. ---
        //
        // OFFSIDE_WHISTLE_MARGIN decides whether a delivery is flagged, and
        // OFFSIDE_VAR_HOLD_FLOOR decides whether the position is held for VAR or waved through.
        // Both were MISSING, so a stored match could not say which offside rule produced it - and
        // the deferred review (a goal by a marginally offside player) was not even reachable until
        // 2026-10-19, so nothing in the fingerprint could have shown it either.
        r.put("offside.whistleMargin", num(OffsideService.OFFSIDE_WHISTLE_MARGIN));
        r.put("offside.varHoldFloor", num(OffsideService.OFFSIDE_VAR_HOLD_FLOOR));

        // --- Ball physics. How far it goes and who can touch it. ---
        r.put("ball.groundDecel", num(BallPhysicsEngine.GROUND_DECEL));
        r.put("ball.airDecel", num(BallPhysicsEngine.AIR_DECEL));
        r.put("ball.maxSpeed", num(BallPhysicsEngine.MAX_BALL_SPEED));
        r.put("ball.minLaunchSpeed", num(BallPhysicsEngine.MIN_LAUNCH_SPEED));
        r.put("ball.onBallEps", num(BallPhysicsEngine.ON_BALL_EPS));
        r.put("ball.receiveR", num(BallPhysicsEngine.RECEIVE_R));
        r.put("ball.interceptR", num(BallPhysicsEngine.INTERCEPT_R));
        r.put("ball.shotBlockR", num(BallPhysicsEngine.SHOT_BLOCK_R));

        // --- Duels. How close a player must be to contest the ball. ---
        r.put("duel.dribbleR", num(DuelEngine.DRIBBLE_DUEL_RADIUS));
        r.put("duel.pressDribbleR", num(DuelEngine.PRESS_DRIB_DUEL_RADIUS));
        r.put("duel.receivePassR", num(DuelEngine.RECEIVE_PASS_RADIUS));
        r.put("duel.shotBlockR", num(DuelEngine.SHOT_BLOCK_RADIUS));
        r.put("duel.cooldownTicks", String.valueOf(DuelEngine.DUEL_COOLDOWN_TICKS));
        r.put("duel.dribbleCooldownTicks", String.valueOf(DuelEngine.DUEL_COOLDOWN_TICKS));

        // --- Movement. Who can get where first. ---
        r.put("move.playerSpeedBase", num(MovementEngine.PLAYER_SPEED_BASE));
        r.put("move.chaseSprint", num(MovementEngine.CHASE_SPRINT_MULTIPLIER));
        r.put("move.carrierFree", num(MovementEngine.CARRIER_FACTOR_FREE));
        r.put("move.carrierPressure", num(MovementEngine.CARRIER_FACTOR_PRESSURE));
        r.put("move.minDistance", num(MovementEngine.MIN_PLAYER_DISTANCE));
        r.put("move.carrierFreeRadius", num(MovementEngine.CARRIER_FREE_RADIUS));
        r.put("move.typeAPressRadius", num(MovementEngine.CARRIER_TYPE_A_PRESS_RADIUS));

        // --- Defending. Who goes where when the other team has the ball. ---
        //
        // These were MISSING until 2026-10-18 and that was a real hole, not a theoretical one:
        // rewriting the two threat-override rules moved a match from 41.0 shots to 34.6 and did not
        // move this string at all. A fingerprint that cannot see the defending rules is a
        // fingerprint that will quietly read a stale match as current.
        r.put("defend.pressRangeA", num(ThreatOverrideEngine.RANGE_A));
        r.put("defend.pressRangeB", num(ThreatOverrideEngine.RANGE_B));
        r.put("defend.carrierSpace", num(ThreatOverrideEngine.CARRIER_SPACE_RADIUS));
        r.put("defend.dangerZone", num(ThreatOverrideEngine.DANGER_ZONE_ROW));
        r.put("defend.offsideRetreatTicks", String.valueOf(ThreatOverrideEngine.OFFSIDE_RETREAT_THRESHOLD));
        r.put("defend.retreatBuffer", num(ThreatOverrideEngine.RETREAT_BUFFER));
        r.put("defend.dropShare", num(DefensiveShape.DROP_SHARE));
        r.put("defend.widthKept", num(DefensiveShape.WIDTH_KEPT));
        r.put("defend.shiftTowardBall", num(DefensiveShape.SHIFT_TOWARD_BALL));

        // --- Discipline and the clock. ---
        r.put("discipline.penaltyFromBoxFoul", num(DisciplineService.PENALTY_FROM_BOX_FOUL));
        r.put("discipline.straightRed", num(DisciplineService.STRAIGHT_RED_RATE));
        r.put("discipline.yellow", num(DisciplineService.YELLOW_RATE));
        r.put("clock.maxStoppageTicks", String.valueOf(StoppageClock.MAX_STOPPAGE_TICKS));
        r.put("clock.maxAddedPerHalf", String.valueOf(StoppageClock.MAX_ADDED_PER_HALF));
        r.put("clock.goalTicks", String.valueOf(StoppageClock.Reason.GOAL.ticks()));
        r.put("clock.injuryTicks", String.valueOf(StoppageClock.Reason.INJURY.ticks()));

        // --- Home advantage. The one number the forecaster and the engine share. ---
        r.put("home.advantageOvr", num(MatchEngineRules.HOME_ADVANTAGE_OVR));
        r.put("home.advantageXg", num(MatchEngineRules.HOME_ADVANTAGE_XG));
        r.put("home.advantageDuelPower", num(MatchEngineRules.HOME_ADVANTAGE_DUEL_POWER));

        return new TreeMap<>(r);
    }

    /**
     * A short hash of the version and every rule in force.
     *
     * <p>12 hex characters. Long enough that two different rulesets will not collide by accident,
     * short enough to sit in a log line and a UI column.
     */
    public static String fingerprint() {
        return fingerprintOf(VERSION, rules());
    }

    /**
     * The hash itself, over any version and any rule set.
     *
     * <p>Package-private so the property that matters can be tested rather than asserted: a tuning
     * change must move the fingerprint even when {@link #VERSION} did not. That is the whole reason
     * the fingerprint exists, and it is not observable from {@link #fingerprint()} alone — a test that
     * only checks the public method is checking that a hash returns a string.
     */
    static String fingerprintOf(String version, Map<String, String> rules) {
        StringBuilder sb = new StringBuilder(version);
        new TreeMap<>(rules).forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM. Falling back to the raw string would make the column
            // hold a 400-character value, so the honest answer is to fail here rather than write
            // something that looks like a fingerprint and is not one.
            throw new IllegalStateException("SHA-256 is required but unavailable", impossible);
        }
    }

    /** What goes on the match row: {@code 2026.10.18-1@a1b2c3d4e5f6}. */
    public static String stamp() {
        return VERSION + "@" + fingerprint();
    }

    /** A one-line summary for a log or a report header. */
    public static String describe() {
        return "engine " + VERSION + " (" + fingerprint() + ", " + rules().size() + " rules)";
    }

    /** Renders a double without a trailing {@code .0} on whole numbers, so {@code 3} reads as {@code 3}. */
    private static String num(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 1_000_000d) / 1_000_000d);
    }
}