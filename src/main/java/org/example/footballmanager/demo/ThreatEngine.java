package org.example.footballmanager.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ThreatEngine — defensive override layer for the demo simulation.
 *
 * <p>Runs <em>after</em> the playmaking decision and <em>before</em> movement,
 * and may replace the normal tactical movement target of <b>our non-carrier
 * players</b>, reacting to threats (press) and to offside safety. It is a
 * two-scope safety layer (the spec's "two scopes"):</p>
 * <ol>
 *   <li><b>{@link #pressTarget(Player, Position)}</b> — non-carrier movement override:
 *       Threat A / B / C plus offside retreat.</li>
 *   <li><b>{@link #overrideCarrierPass(DecisionOption)}</b> — carrier pass
 *       safety: if a pass targets an offside receiver, redirect to a legal
 *       receiver, or — when none exists — schedule an offside violation
 *       restart to the opposing team at the exact receiving position.</li>
 * </ol>
 *
 * <p><b>Out of scope (forbidden to touch, per the spec):</b>
 * PlaymakingDecisionEngine, VisionFilter, OptionSelector, ActionEngine,
 * MovementEngine, DuelEngine, ExecutionQuality and all existing
 * pass/shot/carry/duel formulas. ThreatEngine only decides the <i>desired
 * movement target</i> and the offside safety flag; actual movement (max 1 cell
 * per tick) is performed by {@link MovementEngine#oneCellToward(Position, Position)}.</p>
 *
 * <p><b>Test safety:</b> the layer is OFF by default. {@link #noop()} returns a
 * disabled instance; every existing test builds {@code SimulationEngine} via the
 * no-threat ctors (3-arg / 4-arg) or {@link DemoSimulationFactory#create}, so the
 * 8 existing demo tests are byte-for-byte unaffected. The layer is opted into
 * only through {@code new SimulationEngine(..., boolean threatOverride)} with
 * {@code true} (used by {@code DemoSimulationFactory.createWithThreatOverride} /
 * {@code TacticalGridDemo}).</p>
 *
 * @see THREAT_OVERRIDE_SPEC.md
 */
public final class ThreatEngine {

    /** Press distance floors/ceilings (cells). */
    private static final double PRESS_FLOOR = 0.5;   // below duel radius -> already resolving in DuelEngine
    private static final double PRESS_A_MAX = 1.5;   // Type A: defensive-third, <= 1.5
    private static final double PRESS_B_MAX = 1.0;   // Type B: ball carrier, <= 1.0
    private static final double PRESS_C_MAX = 1.0;   // Type C: local proximity, <= 1.0  -- NARROW
    private static final double ISOLATION_RADIUS = 1.0; // "no teammate within one cell"
    private static final double OFFSIDE_HOLD_TICKS = 60; // matches the existing throw-in restart hold (SimulationEngine L612)

    private final SimulationState state;
    private final PlayerSelectionEngine selection;
    private final boolean enabled;

    /**
     * Type C (local-proximity) override is temporarily DISABLED (§4): the
     * classification code ({@link #nearestTypeC}) stays but is gated by this
     * flag, which defaults to false on the active engine so existing Type-C
     * behaviour is turned off — not deleted. Re-enable per-match via
     * {@link #setTypeCEnabled(boolean)} (exposed through SimulationEngine).
     */
    private boolean typeCEnabled = false;

    /** Active threat/safety layer (enabled = true). */
    public ThreatEngine(SimulationState state, PlayerSelectionEngine selection) {
        this(state, selection, true);
    }

    private ThreatEngine(SimulationState state, PlayerSelectionEngine selection, boolean enabled) {
        this.state = state;
        this.selection = selection;
        this.enabled = enabled;
    }

    /** Disabled threat layer — safe no-op for existing tests (default OFF). */
    public static ThreatEngine noop() {
        return new ThreatEngine(null, null, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** (§4) Temporarily disable / re-enable the Type C local-proximity override. */
    public void setTypeCEnabled(boolean typeCEnabled) {
        this.typeCEnabled = typeCEnabled;
    }

    // ==================================================================
    // 1. Non-carrier movement override (Threat A/B/C + offside retreat)
    // ==================================================================

    /**
     * Desired movement target for a non-carrier player, replacing the normal
     * tactical cell when a threat/offside-safety condition applies. Returns
     * {@code null} when the player should follow the ordinary tactical target.
     *
     * Priority (spec §5):
     *   1. OFFSIDE SAFETY   — retreat toward own goal until on a safe row
     *   2. TYPE A           — isolated opponent in our defensive third (<= 1.5)
     *   3. TYPE B           — isolated opponent ball carrier (<= 1.0)
     *   4. TYPE C           — any opponent in immediate proximity (<= 1.0)
     *   5. {@code null}     — ordinary tactical target
     *
     * <p>One-defender-per-threat dedup (§5/§9) is resolved statelessly: a player
     * reacts to a threat only if it is the closest <i>eligible</i> team-mate for
     * that threat. Iteration order is therefore irrelevant.</p>
     */
    public Position pressTarget(Player p, Position normalCell) {
        if (!enabled) return null;
        if (p == null || p == state.getCarrier()) return null;

        // Goalkeeper (§10): may leave the goal to chase ONLY when he is clearly
        // the closest team-mate to the ball. When a team-mate is closer, the
        // keeper stays home (returns null -> tactical goal-line anchor). The
        // "clear immediately + return to own goal line" is already enforced by
        // ActionEngine.executeCarry (GK -> executeClearance) and the GK role
        // anchor (ActionEngine.goalPositionFor / goalExitPositionFor). `selection`
        // is non-null here because the no-op engine early-returns on !enabled,
        // so the only callers reaching this line have a real PlayerSelectionEngine.
        if ("GK".equals(p.getRole())) {
            Position ballPos = state.getBall().getPosition();
            Player closer = selection.closestTeamTo(ballPos, p.getTeam(), p);
            if (closer != null
                    && MovementEngine.distance(closer.getPosition(), ballPos)
                    <= MovementEngine.distance(p.getPosition(), ballPos)) {
                return null;
            }
        }

        // 1. Offside safety has priority over every pressing behaviour (§5, §10).
        if (isOffside(p)) {
            Position retreat = offsideRetreatTarget(p);
            logOffsideRetreat(p, retreat);
            return retreat;
        }

        // 2-4. Threats, in priority order A > B > C.
        Player threatA = nearestTypeA(p);
        if (threatA != null) {
            logThreatDetected("A", "DEFENSIVE_THIRD_ISOLATED", p, threatA, normalCell);
            return threatA.getPosition();
        }
        Player threatB = nearestTypeB(p);
        if (threatB != null) {
            logThreatDetected("B", "ISOLATED_BALL_CARRIER", p, threatB, normalCell);
            return threatB.getPosition();
        }
        if (typeCEnabled) {
            Player threatC = nearestTypeC(p);
            if (threatC != null) {
                logThreatDetected("C", "LOCAL_PROXIMITY", p, threatC, normalCell);
                return threatC.getPosition();
            }
        }
        return null;
    }

    // ==================================================================
    // 2. Carrier pass safety override (offside-aware)
    // ==================================================================

    /**
     * Final legality check on the carrier's pass decision (spec §0 scope 2).
     * A non-null {@link DecisionOption#getTarget()} is, by the DecisionOption
     * ctor contract and {@code executeDecisionOption}, produced only for
     * PASS/THRU — so no explicit type check is needed (avoids enum-token drift).
     *
     * If a PASS/THRU targets an offside receiver:
     *   <ul>
     *     <li>there is an on-side team-mate -> {@link Kind#PASS_LEGAL}: return a
     *         replacement {@code DecisionOption} targeting the legal receiver
     *         (closest on-side team-mate to the original destination);</li>
     *     <li>otherwise -> {@link Kind#VIOLATION}: schedule an offside-restart
     *         (opponent takes a throw-in-style restart from the exact receiving
     *         position; the illegal pass is not executed).</li>
     *   </ul>
     * The "poor playmaking can still slip through" caveat is respected because
     * {@link #isOffside} only flags clear FIFA violations — borderline/uncertain
     * positions are treated as on-side and the pass proceeds unchanged.
     */
    public PassResult overrideCarrierPass(DecisionOption decision) {
        if (!enabled) return PassResult.KEEP;
        if (decision == null) return PassResult.KEEP;
        Player receiver = decision.getTarget();
        if (receiver == null) return PassResult.KEEP; // only PASS/THRU carry a receiver target
        Player carrier = state.getCarrier();
        if (carrier == null) return PassResult.KEEP;
        if (!receiver.getTeam().equals(carrier.getTeam())) return PassResult.KEEP; // not a team-mate pass
        if (!isOffside(receiver)) return PassResult.KEEP; // receiver on-side -> normal pass

        // Receiver is offside. (spec §16) Prefer a legal receiver.
        Player legal = legalReceiver(carrier, receiver.getPosition());
        if (legal != null) {
            DecisionOption replacement = new DecisionOption(DecisionType.PASS, legal,
                    decision.getScore(), "offside_avoided: legal receiver selected");
            state.log(String.format(Locale.ROOT,
                    "OFFSIDE SAFETY | PASS_TO_LEGAL | PASSER=%s | OFFSIDE_RECEIVER=%s | LEGAL=%s",
                    carrier.getLabel(), receiver.getLabel(), legal.getLabel()));
            return PassResult.legal(replacement);
        }

        // No legal receiver: offside violation. Restart to the opponents at the
        // EXACT receiving position (spec §13-§15). The illegal pass is not executed.
        setupOffsideViolation(receiver, carrier);
        return PassResult.VIOLATION;
    }

    /** Outcome of the carrier-pass safety check. */
    public static final class PassResult {
        public enum Kind { KEEP, PASS_LEGAL, VIOLATION }
        public final Kind kind;
        public final DecisionOption replacement; // non-null iff PASS_LEGAL

        private PassResult(Kind kind, DecisionOption replacement) {
            this.kind = kind;
            this.replacement = replacement;
        }

        public static final PassResult KEEP = new PassResult(Kind.KEEP, null);
        public static final PassResult VIOLATION = new PassResult(Kind.VIOLATION, null);

        public static PassResult legal(DecisionOption replacement) {
            return new PassResult(Kind.PASS_LEGAL, replacement);
        }
    }

    // ==================================================================
    // Offside detection & safety (FIFA second-to-last defender)
    // ==================================================================

    /**
     * FIFA offside rule (spec §11, FIFA second-to-last-defender, team-relative):
     * HOME attacks toward row 7, AWAY toward row 1. A player is offside when:
     * (1) in the opponent's half (row > 4 for HOME, row < 4 for AWAY) AND
     * (2) level with or ahead of the ball AND
     * (3) fewer than two opponents are between the player and the opponent's goal
     *     (i.e. the player is beyond the second-to-last defender).
     * The ball carrier is never offside. The result is cached on the player
     * ({@link Player#setOffside(boolean)}) for logging/UI.
     */
    private boolean isOffside(Player p) {
        if (p == null) return false;
        if (p == state.getCarrier()) {
            p.setOffside(false);
            return false;
        }
        boolean home = SimulationState.TEAM_HOME.equals(p.getTeam());
        double ballRow = state.getBall().getPosition().getRow();
        double row = rowOf(p);

        if (home) {
            if (row <= 4.0) { p.setOffside(false); return false; }        // own half -> immune
            if (row < ballRow) { p.setOffside(false); return false; }      // behind ball
            boolean offside = countOpponentsBetween(p, true) < 2;          // toward row 7
            p.setOffside(offside);
            return offside;
        } else {
            if (row >= 4.0) { p.setOffside(false); return false; }         // own half -> immune
            if (row > ballRow) { p.setOffside(false); return false; }      // behind ball
            boolean offside = countOpponentsBetween(p, false) < 2;         // toward row 1
            p.setOffside(offside);
            return offside;
        }
    }

    /** Count opponents strictly between p and the opponent's goal. */
    private long countOpponentsBetween(Player p, boolean towardRow7) {
        long n = 0;
        double row = rowOf(p);
        for (Player o : state.getPlayers()) {
            if (o == p || o.getTeam().equals(p.getTeam())) continue;
            if (towardRow7) { if (rowOf(o) > row) n++; }
            else            { if (rowOf(o) < row) n++; }
        }
        return n;
    }

    /**
     * Safe retreat target for an offside attacker (spec §12): a row just behind
     * the second-to-last defender, so two opponents are then between the player
     * and the goal. The retreat is purely toward own goal (row axis only — no
     * lateral shove) and bounded to one cell/tick by
     * {@link MovementEngine#oneCellToward}; it compounds across ticks. If fewer
     * than two opponents exist, retreat to the centre line (exit opponent's half).
     */
    private Position offsideRetreatTarget(Player p) {
        boolean home = SimulationState.TEAM_HOME.equals(p.getTeam());
        List<Player> opponents = opponentsOf(p.getTeam());
        double safeRow;
        if (opponents.size() >= 2) {
            if (home) {
                opponents.sort((a, b) -> Double.compare(rowOf(b), rowOf(a))); // toward row 7
                double d2 = rowOf(opponents.get(1));                            // second-to-last
                safeRow = clamp17(d2 - 1.0);
            } else {
                opponents.sort((a, b) -> Double.compare(rowOf(a), rowOf(b))); // toward row 1
                double d2 = rowOf(opponents.get(1));                            // second-to-last
                safeRow = clamp17(d2 + 1.0);
            }
        } else {
            safeRow = 4.0; // exit opponent's half (centre line)
        }
        // Column unchanged -> pure goal-ward retreat (no lateral shove, spec §13).
        return new Position(safeRow, p.getPosition().getColumn());
    }

    private static double clamp17(double v) {
        return Math.max(1.0, Math.min(7.0, v));
    }

    private static double rowOf(Player p) {
        return p.getPosition().getRow();
    }

    private static String fmtPos(Position pos) {
        if (pos == null) return "null";
        return String.format(Locale.ROOT, "%.2f,%.2f", pos.getRow(), pos.getColumn());
    }

    // ==================================================================
    // §19 logging (exact tokens)
    // ==================================================================

    private void logThreatDetected(String type, String reason, Player defender, Player opponent, Position normalCell) {
        double dist = MovementEngine.distance(defender.getPosition(), opponent.getPosition());
        boolean carrier = opponent == state.getCarrier();
        state.log(String.format(Locale.ROOT,
                "THREAT DETECTED | TYPE=%s | DEFENDER=%s | OPPONENT=%s | CARRIER=%s | DIST=%.2f | NORMAL=%s | OVERRIDE=toward(OPPONENT) | REASON=%s",
                type, defender.getLabel(), opponent.getLabel(), carrier ? "yes" : "no",
                dist, fmtPos(normalCell), reason));
    }

    private void logOffsideRetreat(Player p, Position retreat) {
        Position ball = state.getBall().getPosition();
        state.log(String.format(Locale.ROOT,
                "OFFSIDE DETECTED | PLAYER=%s | POSITION=%s | BALL=%s | ACTION=RETREAT",
                p.getLabel(), fmtPos(p.getPosition()), fmtPos(ball)));
    }

    // ==================================================================
    // Threat classification helpers
    // ==================================================================

    /**
     * Type A (spec §6): closest opponent in OUR defensive third, isolated
     * (no team-mate within 1 cell) and within [0.5, 1.5] — and we are the
     * closest eligible team-mate to that opponent (§5 dedup). Returns null
     * otherwise. The ball carrier may qualify as A when it sits in our
     * defensive third; B handles the carrier elsewhere.
     */
    private Player nearestTypeA(Player p) {
        double best = Double.MAX_VALUE;
        Player chosen = null;
        for (Player o : opponentsOf(p.getTeam())) {
            if (!inDefensiveThird(o, p.getTeam())) continue;      // A1
            if (!isolated(o)) continue;                            // A2
            double d = MovementEngine.distance(p.getPosition(), o.getPosition());
            if (d < PRESS_FLOOR || d > PRESS_A_MAX) continue;      // A3
            if (!isClosestOurPlayerTo(p, o, PRESS_FLOOR, PRESS_A_MAX)) continue; // §5 dedup
            if (d < best) { best = d; chosen = o; }
        }
        return chosen;
    }

    /**
     * Type B (spec §7): the opponent ball carrier, isolated and within
     * [0.5, 1.0] — and we are the closest eligible team-mate (§5 dedup).
     */
    private Player nearestTypeB(Player p) {
        Player carrier = state.getCarrier();
        if (carrier == null) return null;
        if (carrier.getTeam().equals(p.getTeam())) return null; // our own carrier is not a threat
        if (!isolated(carrier)) return null;                     // B2
        double d = MovementEngine.distance(p.getPosition(), carrier.getPosition());
        if (d < PRESS_FLOOR || d > PRESS_B_MAX) return null;     // B3
        if (!isClosestOurPlayerTo(p, carrier, PRESS_FLOOR, PRESS_B_MAX)) return null;
        return carrier;
    }

    /**
     * Type C (spec §8, deliberately NARROW): closest opponent within 1 cell
     * (effectively [0.5, 1.0], excluding the sub-duel-radius range), where we
     * are the closest eligible team-mate (§5 dedup). Falls back only when A/B
     * did not apply to this player.
     */
    private Player nearestTypeC(Player p) {
        double best = Double.MAX_VALUE;
        Player chosen = null;
        for (Player o : opponentsOf(p.getTeam())) {
            double d = MovementEngine.distance(p.getPosition(), o.getPosition());
            if (d < PRESS_FLOOR || d > PRESS_C_MAX) continue;     // §8 (<= 1.0; 0.5 = duel floor)
            if (!isClosestOurPlayerTo(p, o, PRESS_FLOOR, PRESS_C_MAX)) continue;
            if (d < best) { best = d; chosen = o; }
        }
        return chosen;
    }

    /** Opponent is in our defensive third: HOME rows 1-3, AWAY rows 5-7. */
    private boolean inDefensiveThird(Player o, String ourTeam) {
        if (SimulationState.TEAM_HOME.equals(ourTeam)) {
            return rowOf(o) <= 3.0;
        } else {
            return rowOf(o) >= 5.0;
        }
    }

    /** True when o has no team-mate (same team, excluding o) within r cells. */
    private boolean isolated(Player o) {
        return teammateCountWithin(o, ISOLATION_RADIUS) == 0;
    }

    private int teammateCountWithin(Player o, double r) {
        int n = 0;
        for (Player t : state.getPlayers()) {
            if (t.getTeam().equals(o.getTeam()) && t != o
                    && MovementEngine.distance(t.getPosition(), o.getPosition()) <= r) {
                n++;
            }
        }
        return n;
    }

    /**
     * True when p is the closest eligible team-mate of p to threat o within
     * [lo, hi] (stateless, order-independent — implements §5/§9 dedup without
     * shared per-tick state).
     */
    private boolean isClosestOurPlayerTo(Player p, Player o, double lo, double hi) {
        double myDist = MovementEngine.distance(p.getPosition(), o.getPosition());
        if (myDist < lo || myDist > hi) return false;
        Player carrier = state.getCarrier();
        for (Player p2 : state.getPlayers()) {
            if (!p2.getTeam().equals(p.getTeam())) continue; // our team only
            if (p2 == p) continue;
            if (p2 == carrier) continue;                     // carrier does not press itself
            if (p2.isLocked()) continue;                     // locked = busy (e.g. receiving)
            double d2 = MovementEngine.distance(p2.getPosition(), o.getPosition());
            if (d2 >= lo && d2 <= hi && d2 < myDist) return false;
        }
        return true;
    }

    /** All opponents of the given team. */
    private List<Player> opponentsOf(String team) {
        List<Player> out = new ArrayList<>();
        for (Player x : state.getPlayers()) {
            if (!x.getTeam().equals(team)) out.add(x);
        }
        return out;
    }

    // ==================================================================
    // Offside violation -> opponent restart (reuses existing restart lifecycle)
    // ==================================================================

    /**
     * Offside violation (spec §13-§15): the ball stays at the EXACT receiving
     * location of the offside player and the nearest opponent takes a restart
     * from there. This sets the same fields the existing throw-in / out-of-bounds
     * restart path sets — {@code pendingRestartPosition} +
     * {@code pendingRestartPlayer} + {@code restartHoldTicks(60)} — which
     * {@link SimulationEngine} resolves (advanceInternal restart-resolver +
     * {@code startRestart}) on the next tick. No action engine call is made.
     */
    private void setupOffsideViolation(Player receiver, Player carrier) {
        Position exactPos = receiver.getPosition();
        String receiverTeam = receiver.getTeam();
        String opponentTeam = SimulationState.TEAM_HOME.equals(receiverTeam) ? "AWAY" : SimulationState.TEAM_HOME;
        Player restartPlayer = selection.closestTeamTo(exactPos, opponentTeam);
        state.setPendingRestartPosition(exactPos);              // ball placed at the EXACT spot
        state.setPendingRestartPlayer(restartPlayer);           // opponent collects it
        state.setRestartPassToHomeGoalkeeper(false);
        state.setRestartHoldTicks((int) Math.round(OFFSIDE_HOLD_TICKS));
        state.log(String.format(Locale.ROOT,
                "OFFSIDE VIOLATION | PLAYER=%s | RECEIVE_POSITION=%s | RESTART=OPPONENT",
                receiver.getLabel(), fmtPos(exactPos)));
    }

    /**
     * Legal receiver (spec §16/§17): the closest on-side team-mate (excluding the
     * carrier) to the original intended destination. Returns null when no legal
     * receiver exists (which triggers the violation restart above).
     */
    private Player legalReceiver(Player carrier, Position intendedPos) {
        String team = carrier.getTeam();
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player t : state.getPlayers()) {
            if (t == carrier) continue;
            if (!t.getTeam().equals(team)) continue;
            if (isOffside(t)) continue; // on-side only
            double d = MovementEngine.distance(t.getPosition(), intendedPos);
            if (d < bestDist) { bestDist = d; best = t; }
        }
        return best;
    }
}
