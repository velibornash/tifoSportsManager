package org.example.footballmanager.newLogic.sim.rules;

import org.example.footballmanager.newLogic.sim.engine.ActionLogService;
import org.example.footballmanager.newLogic.sim.engine.EngineInterfaces;
import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OffsideService: per-pass FIFA Law 11 check with margin bands and VAR
 * integration (proposal surface port).
 *
 * Band rules (margin = receiver forward of the second-to-last defender incl. GK):
 *   margin > 0.5        CLEAR offside — receiver flagged at pass-moment, pass flies
 *                       normally, WHISTLE fires when he touches the ball (user rule
 *                       2026-09-23: the ball never teleports/accelerates)
 *   0 < margin <= 0.5   MARGINAL — same flag-and-whistle-at-reception (the "action
 *                       after the pass" IS the reception the user asked for)
 *   -0.8 < margin <= 0  tight onside margin, check held; VAR confirms ONSIDE only
 *                       if the next action was a GOAL
 *   margin <= -0.8      clear onside, no call
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
                    : margin > -0.8 ? "TIGHT-ON(VAR hold)" : "ONSIDE";
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

        if (margin > -0.8) {
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
    public void resolvePendingVAROffside(MatchState state) {
        if (!state.hasPendingVARReview()) return;
        Player receiver = state.getPendingVARReviewPlayer();
        if (receiver == null) {
            state.clearPendingVARReview();
            state.setOffsideDeferred(false);
            return;
        }

        if (!state.isOffsideDeferred()) {
            state.clearPendingVARReview();
            return;
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
                return;
            }
            state.clearPendingVARReview();
            state.setOffsideDeferred(false);
            return;
        }

        if (state.isOffsideDeferredDecisionForward()) {
            confirmOffside(receiver, carrierTeam(receiver, state), state,
                    "offside (deferred, forward-attack)");
            return;
        }

        state.clearPendingVARReview();
        state.setOffsideDeferred(false);
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

