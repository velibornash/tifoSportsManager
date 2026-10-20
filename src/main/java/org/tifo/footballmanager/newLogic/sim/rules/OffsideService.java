package org.tifo.footballmanager.newLogic.sim.rules;

import org.tifo.footballmanager.newLogic.sim.engine.ActionLogService;
import org.tifo.footballmanager.newLogic.sim.engine.EngineInterfaces;
import org.tifo.footballmanager.newLogic.sim.model.ActionType;
import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.tifo.footballmanager.newLogic.sim.restarts.RestartManager;
import org.tifo.footballmanager.newLogic.sim.result.ProposalStatsCollector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OffsideService: per-pass FIFA Law 11 check with margin bands and VAR
 * integration (proposal surface port).
 *
 * <p><b>The bands, as the code applies them.</b> The margin is how far the receiver is beyond the
 * second-to-last defender of the side he is attacking; the goalkeeper counts, because Law 11 says
 * "level with the second-last opponent" and means every one of them. 1 cell = 14 m.
 *
 * <pre>
 *   margin &gt; 0.30             the receiver is FLAGGED as the ball is played, the delivery flies
 *                              normally, and the WHISTLE fires when he touches the ball (user rule
 *                              2026-09-23: the ball never teleports and never accelerates). The
 *                              offence happens at the RECEPTION, not at the pass.
 *   -0.8 &lt; margin &lt;= 0.30   the check is HELD: the delivery is let play on, and the position is
 *                              reviewed only if the receiver goes on to score.
 *   margin &lt;= -0.8            clear onside: no call, no review.
 * </pre>
 *
 * <p>The two cut-offs are {@link #OFFSIDE_WHISTLE_MARGIN} and {@link #OFFSIDE_VAR_HOLD_FLOOR}.
 *
 * <p><b>This header used to describe bands the code did not have.</b> It said 0.5 and 0: a CLEAR band
 * above 0.5 and a MARGINAL band from 0 to 0.5 with "the same flag-and-whistle-at-reception". The
 * code has neither — it splits the 0-to-0.5 band at 0.30, where above it the receiver is flagged and
 * below it the check is held for VAR. A reader could not tell which band a given margin landed in,
 * and the 0.30 in the code was an undocumented intermediate that appeared nowhere in the header.
 * Found 2026-10-19.
 */
public class OffsideService implements EngineInterfaces.OffsideService {

    /**
     * Diagnostic flag (ProposalPhysicsDiagnostic): print a trace for every PASS
     * offside check — receiver position, second-to-last defender's distance from
     * the goal line, and whether the receiver was actually forward of the ball /
     * in the opponent's half (FIFA Law 11 preconditions). Behavior is unchanged;
     * this is stdout-only diagnostics.
     */
    public static boolean TRACE = false;

    /**
     * How far beyond the second-to-last defender (in cells, 1 cell = 14 m) a
     * receiver must be before the flag counts as an offence at the moment he
     * touches the ball.
     *
     * The decision layer never targets a receiver beyond
     * {@code OFFSIDE_HARD_LIMIT} (0.5 cells = 7 m) and only risks a pass into
     * the band below it when the carrier's playmaking is poor, so the whistle
     * only has to catch that residual case. 0.30 cells = 4.2 m: a pass played to
     * someone inside that is let play on, anything beyond it is flagged at the
     * reception (the "action after the pass" rule, not a pass-moment kill).
     *
     * Measured history: the band used to be 0 (a centimetre beyond the line),
     * which flagged almost every forward pass and produced 22 offsides a match;
     * then 0.2, which produced none at all. 0.30 with a playmaking-gated decision
     * layer keeps it in the real 2-4 range.
     */
    public static final double OFFSIDE_WHISTLE_MARGIN = 0.30;

    /**
     * The floor of the band that is HELD for VAR, in cells.
     *
     * <p>A receiver between this margin and {@link #OFFSIDE_WHISTLE_MARGIN} is not flagged: the
     * delivery is let play on, and the position is reviewed only if he goes on to score. Below it he
     * is plainly onside and there is nothing to review.
     *
     * <p>It was a literal {@code -0.8} in two places, so a reader of the code could not see it was a
     * band edge at all, and the engine fingerprint could not name it.
     */
    public static final double OFFSIDE_VAR_HOLD_FLOOR = -0.8;

    private final MatchState state;
    private final VARService varService;
    private final MatchRecorder recorder;
    private final RestartManager restartManager;
    /** Optional: when wired, every confirmed offside is counted for the team
     *  that was caught (TeamStats.offsides used to be hardcoded 0). */
    private ProposalStatsCollector stats;

    public OffsideService(MatchState state, VARService varService) {
        this(state, varService, null, null);
    }

    public OffsideService(MatchState state, VARService varService,
                         MatchRecorder recorder, RestartManager restartManager) {
        this.state = state;
        this.varService = varService;
        this.recorder = recorder;
        this.restartManager = restartManager;
    }

    public void setStats(ProposalStatsCollector stats) {
        this.stats = stats;
    }

    @Override
    public void trackOffsidePositions(MatchState state) {
        Player carrier = state.getCarrier();
        if (carrier == null) return;
        Position origin = carrier.getPosition();
        for (Player p : state.getPlayers()) {
            if ("GK".equals(p.getRole())) continue;
            if (p == carrier) continue;
            if (p.isSentOff() || p.isInjured()) continue;
            boolean home = "HOME".equals(p.getTeam());
            String defendingTeam = home ? "AWAY" : "HOME";

            boolean forward = home
                    ? p.getPosition().getRow() > origin.getRow()
                    : p.getPosition().getRow() < origin.getRow();
            if (!forward) { p.resetConsecutiveOffside(); continue; }

            int opponentsGoalSide = 0;
            for (Player opp : state.getPlayers()) {
                if (!defendingTeam.equals(opp.getTeam())) continue;
                if (opp.isSentOff() || opp.isInjured()) continue;
                boolean goalSide = home
                        ? opp.getPosition().getRow() > p.getPosition().getRow()
                        : opp.getPosition().getRow() < p.getPosition().getRow();
                if (goalSide) opponentsGoalSide++;
            }
            if (opponentsGoalSide < 2) {
                p.incrementConsecutiveOffside();
            } else {
                p.resetConsecutiveOffside();
            }
        }
    }

    @Override
    public OffsideResult checkOffside(Player receiver, Position passOrigin, MatchState state) {
        if (state.isKickoffPending() || state.getSetPieceType() != null) {
            return new OffsideResult(false, false);
        }

        double margin = calculateOffsideMargin(receiver, passOrigin, state);

        if (TRACE) {
            boolean home = "HOME".equals(receiver.getTeam());
            List<Double> defRows = new ArrayList<>();
            String defendingTeam = home ? "AWAY" : "HOME";
            for (Player opp : state.getPlayers()) {
                if (!defendingTeam.equals(opp.getTeam())) continue;
                if (opp.isSentOff() || opp.isInjured()) continue;
                defRows.add(opp.getPosition().getRow());
            }
            defRows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
            double lineRow = defRows.size() < 2
                    ? (home ? 0.0 : 9.0)
                    : defRows.get(1);
            double goalLineDist = home ? 8.0 - lineRow : lineRow - 1.0;
            boolean forwardOfBall = home
                    ? receiver.getPosition().getRow() > passOrigin.getRow()
                    : receiver.getPosition().getRow() < passOrigin.getRow();
            boolean inOppHalf = home
                    ? receiver.getPosition().getRow() > 4.5
                    : receiver.getPosition().getRow() < 4.5;
            String band = margin > 0.5 ? "CLEAR-OFF(flag@recv)"
                    : margin > OFFSIDE_WHISTLE_MARGIN ? "FLAG-OFF(whistle@recv)"
                    : margin > 0 ? "MARG-OFF(flag@recv)"
                    : margin > OFFSIDE_VAR_HOLD_FLOOR ? "TIGHT-ON(VAR hold)" : "ONSIDE";
            // Route through ActionLogService (tag OFF) instead of a raw println:
            // the trace then lands in target/proposal-app.log and match.json like
            // every other engine line, instead of bypassing the logging service.
            ActionLogService logger = state.getActionLogger();
            if (logger == null) {
                System.out.printf("[OFF-TRACE] pass %s(%s) -> receiver %s at (%.2f,%.2f) | margin %+.3f -> %s%n",
                        state.getCarrier() == null ? "?" : state.getCarrier().getLabel(),
                        "HOME".equals(receiver.getTeam()) ? "H" : "A",
                        receiver.getLabel(), receiver.getPosition().getRow(),
                        receiver.getPosition().getColumn(), margin, band);
            } else {
                logger.log("OFF", "OFF-TRACE pass "
                        + (state.getCarrier() == null ? "?" : state.getCarrier().getLabel())
                        + "(" + ("HOME".equals(receiver.getTeam()) ? "H" : "A") + ") -> receiver "
                        + receiver.getLabel() + " at " + String.format("(%.2f,%.2f)",
                                receiver.getPosition().getRow(), receiver.getPosition().getColumn())
                        + " | ball at strike " + String.format("(%.2f,%.2f)",
                                passOrigin.getRow(), passOrigin.getColumn())
                        + " | last-2-def row "
                        + String.format("%.2f", defRows.isEmpty() ? Double.NaN : defRows.get(0))
                        + " & 2nd-last " + String.format("%.2f", lineRow)
                        + " (" + String.format("%.2f", goalLineDist) + " cells off goal line)"
                        + " | forward-of-ball:" + forwardOfBall + " in-opp-half:" + inOppHalf
                        + " | margin " + String.format("%+.3f", margin) + " -> " + band);
            }
        }

        if (margin > OFFSIDE_WHISTLE_MARGIN) {
            // CLEAR + MARGINAL bands (user rule 2026-09-23): the pass is NOT
            // blocked and the ball is NOT teleported to the receiver at
            // pass-moment — that is what made the ball "suddenly accelerate".
            // The receiver is FLAGGED so the whistle fires only when he actually
            // touches the ball (the direct reception = the "action after the
            // pass" the user asked for). If any defender/opponent gets there
            // first the flag is cleared and play continues (no offense).
            state.setOffsideFlaggedReceiver(receiver);
            return new OffsideResult(false, true);
        }

        if (margin > OFFSIDE_VAR_HOLD_FLOOR) {
            state.setPendingVARReview("ONSIDE_CHECK", receiver,
                    "HOME".equals(receiver.getTeam()) ? "AWAY" : "HOME");
            state.setOffsideDeferred(true);
            state.setOffsideDeferredMargin(margin);
            state.setOffsideLedToGoal(false);
            state.setOffsideDeferredActionCount(state.getOffsideDeferredActionCount() + 1);
            return new OffsideResult(false, true);
        }

        return new OffsideResult(false, false);
    }

    @Override
    public boolean isOffsideCheckedDelivery(ActionType type) {
        return type == ActionType.PASS
                || type == ActionType.CROSS
                || type == ActionType.CENTER;
    }

    /**
     * Whether a held offside check is waiting on this player.
     *
     * <p>Pure state: a held check, its type, and whether the player it names is the one who scored.
     *
     * <p>Until this existed the held check was cancelled a tick after it was created — nothing ever said a
     * goal had been scored, because {@code setOffsideLedToGoal(true)} had no writer — so the whole
     * deferred branch of {@link #resolvePendingVAROffside} was unreachable and a goal from a marginally
     * offside position stood unquestioned. Found 2026-10-19.
     */
    @Override
    public boolean isDeferredOffsideScorer(Player shooter, MatchState state) {
        if (shooter == null || state == null || !state.isOffsideDeferred()) {
            return false;
        }
        if (!"ONSIDE_CHECK".equals(state.getPendingVARReviewType())) {
            return false;
        }
        Player receiver = state.getPendingVARReviewPlayer();
        return receiver != null && receiver.getId() != null && receiver.getId().equals(shooter.getId());
    }

    @Override
    public boolean resolvePendingVAROffside(MatchState state) {
        if (!state.hasPendingVARReview()) {
            return false;
        }
        Player receiver = state.getPendingVARReviewPlayer();
        if (receiver == null) {
            state.clearPendingVARReview();
            state.setOffsideDeferred(false);
            return false;
        }

        if (!state.isOffsideDeferred()) {
            state.clearPendingVARReview();
            return false;
        }

        if (state.isOffsideLedToGoal()) {
            boolean offside = varService == null || varService.checkOffside(receiver,
                    state.getBall().getPosition(), state);
            if (varService != null) {
                recordVarDecision(receiver, varService.getLastVARDecision(),
                        "goal review for " + receiver.getLabel());
            }
            if (offside) {
                confirmOffside(receiver, carrierTeam(receiver, state), state,
                        "VAR offside on goal - goal disallowed");
                return true;
            }
            state.clearPendingVARReview();
            state.setOffsideDeferred(false);
            return false;
        }

        if (state.isOffsideDeferredDecisionForward()) {
            confirmOffside(receiver, carrierTeam(receiver, state), state,
                    "offside (deferred, forward-attack)");
            return true;
        }

        state.clearPendingVARReview();
        state.setOffsideDeferred(false);
        return false;
    }

    private OffsideResult confirmOffside(Player receiver, String carrierTeam, MatchState state,
                                         String reason) {
        String attackingTeam = carrierTeam != null ? carrierTeam : receiver.getTeam();
        String defendingTeam = "HOME".equals(attackingTeam) ? "AWAY" : "HOME";
        state.setRestartTeam(defendingTeam);
        state.clearPassContext();
        state.setOffsideFlaggedReceiver(null);
        state.setCarrier(null);
        state.getBall().setPosition(receiver.getPosition());
        if (recorder != null) {
            recorder.appendEvent(state.getMatchTicks(), "OFFSIDE",
                    reason + " by " + receiver.getLabel(), receiver, null);
        }
        if (stats != null) stats.onOffside(receiver.getTeam());
        if (restartManager != null) {
            restartManager.handleOffsideFreeKick(state, receiver.getPosition());
        }
        state.clearPendingVARReview();
        state.setOffsideDeferred(false);
        return new OffsideResult(true, true);
    }

    private void recordVarDecision(Player subject, String decision, String description) {
        if (recorder == null || decision == null
                || "NONE".equals(decision) || "NO_REVIEW".equals(decision)) return;
        // Typed event name so the replay viewer's VAR verdict banner fires: the
        // viewer matches VAR_OFFSIDE_CONFIRMED / VAR_OFFSIDE_OVERTURNED, not a
        // bare "VAR" type.
        String suffix = decision.toUpperCase().contains("OVERTURN") ? "OVERTURNED" : "CONFIRMED";
        recorder.appendEvent(state.getMatchTicks(), "VAR_OFFSIDE_" + suffix,
                "VAR " + decision + " — " + description, subject.getTeam(), subject, null,
                null, null, null, null, null, null, null, null, "OFFSIDE", decision);
    }

    private String carrierTeam(Player receiver, MatchState state) {
        return state.getCarrierTeam();
    }

    /**
     * Distance (in cells) by which {@code receiver} is beyond the offside line
     * right now, measured against the current positions of the defending team.
     *
     * <p>Used by the two whistle sites ({@code BallResultHandler} and
     * {@code DuelService}) to put a {@code margin=} on the offside event the
     * viewer already knows how to render. The pass-moment margin is the
     * authoritative one, but it is not retained on the state for the clear and
     * marginal bands, so the whistle re-measures against the (now possibly
     * moved) defenders. That is the same thing the frozen {@code demo/} module
     * does at its confirm site.
     */
    public static double offsideMargin(Player receiver, MatchState state) {
        boolean home = "HOME".equals(receiver.getTeam());
        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> rows = new ArrayList<>();
        for (Player opp : state.getPlayers()) {
            if (!defendingTeam.equals(opp.getTeam())) continue;
            if (opp.isSentOff() || opp.isInjured()) continue;
            rows.add(opp.getPosition().getRow());
        }
        if (rows.size() < 2) return home ? 10.0 : -10.0;
        rows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
        double lineRow = rows.get(1);
        return home
                ? receiver.getPosition().getRow() - lineRow
                : lineRow - receiver.getPosition().getRow();
    }

    private double calculateOffsideMargin(Player receiver, Position passOrigin, MatchState state) {
        boolean home = "HOME".equals(receiver.getTeam());
        double receiverRow = receiver.getPosition().getRow();

        // FIFA Law 11 preconditions (a player can only be offside if BOTH hold):
        //  (1) the receiver is in the OPPONENT'S half at the moment the pass is played,
        //  (2) the receiver is FORWARD OF THE BALL (closer to the opponents' goal line).
        // Backward / level passes and own-half receivers are always onside.
        boolean inOppHalf = home ? receiverRow > 4.5 : receiverRow < 4.5;
        boolean forwardOfBall = home
                ? receiverRow > passOrigin.getRow()
                : receiverRow < passOrigin.getRow();
        if (!inOppHalf || !forwardOfBall) {
            return -5.0; // clear onside, no whistle, no VAR hold
        }

        String defendingTeam = home ? "AWAY" : "HOME";
        List<Double> rows = new ArrayList<Double>();
        for (Player opp : state.getPlayers()) {
            if (!defendingTeam.equals(opp.getTeam())) continue;
            if (opp.isSentOff() || opp.isInjured()) continue;
            rows.add(opp.getPosition().getRow());
        }
        if (rows.size() < 2) return home ? 10.0 : -10.0;
        rows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
        double lineRow = rows.get(1);
        return home
                ? receiverRow - lineRow
                : lineRow - receiverRow;
    }
}

