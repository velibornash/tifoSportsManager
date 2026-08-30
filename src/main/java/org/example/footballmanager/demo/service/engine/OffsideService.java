package org.example.footballmanager.demo.service.engine;

import org.example.footballmanager.demo.service.MatchState;
import org.example.footballmanager.demo.service.model.*;
import org.example.footballmanager.demo.service.recording.MatchEvent;
import org.example.footballmanager.demo.service.recording.MatchRecorder;
import org.example.footballmanager.demo.service.result.ActionLogService;
import org.example.footballmanager.demo.service.result.MatchStatsCollector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Handles offside checks + VAR review + indirect free kick awarding.
 *
 * Extracted from MatchSimulator.executeDecision() (Phase 2) to eliminate
 * the duplicated ~30-line offside handling block that appeared in both
 * PASS and THRU branches.
 *
 * This service DOES mutate MatchState (stats, consecutive offside counts,
 * ball carrier via actionEngine.giveBallTo).
 */
public class OffsideService {

    // Duration of a full VAR offside review in match ticks.
    // Per user: fixed ~90 match seconds ≈ 150-180 ticks (harder calls 2-5 match
    // minutes). We use a fixed value in that range for the initial version.
    private static final int VAR_OFFSIDE_TICKS = 165;

    private final VARService varService;
    private final FootballRulesService rulesService;
    private final ActionLogService logger;
    private final PlayerSelectionEngine selection;
    private final MatchStatsCollector stats;
    private final MatchRecorder recorder;

    public OffsideService(VARService varService, FootballRulesService rulesService,
                          ActionLogService logger, PlayerSelectionEngine selection,
                          MatchStatsCollector stats, MatchRecorder recorder) {
        this.varService = varService;
        this.rulesService = rulesService;
        this.logger = logger;
        this.selection = selection;
        this.stats = stats;
        this.recorder = recorder;
    }

    /**
     * Track offside POSITION for every outfield teammate at a forward-pass
     * moment, regardless of whether the pass is actually aimed at them.
     *
     * Purpose (threat-override offside retreat): a player who keeps hovering in
     * an offside position (even when the ball never goes to them) accumulates a
     * consecutive count. After 3 consecutive forward-pass moments in an offside
     * position, the threat override drops them back toward their own goal until
     * they are onside, then the counter resets. This prevents a single attacker
     * from permanently loitering offside.
     *
     * Being onside during a forward-pass moment resets a player's streak.
     *
     * @param carrierTeam team about to play a forward pass
     * @param passOrigin  position of the ball / carrier when the pass is played
     */
    public void trackOffsidePositions(MatchState state, String carrierTeam, Position passOrigin) {
        for (Player p : state.getPlayers()) {
            if (!carrierTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole())) continue;
            if (p == state.getCarrier()) continue;
            if (p.isSentOff() || p.isInjured()) continue;
            boolean inOffsidePosition = isPlayerInOffsidePosition(state, p, passOrigin);
            if (inOffsidePosition) {
                p.incrementConsecutiveOffside();
            } else {
                p.resetConsecutiveOffside();
            }
        }
    }

    /**
     * Is this player currently standing in an offside position at a forward-pass
     * moment? Mirrors FootballRulesService.isOffside but WITHOUT the requirement
     * that the pass be aimed at this player — used to detect a player simply
     * loitering offside ahead of the defensive line.
     */
    private boolean isPlayerInOffsidePosition(MatchState state, Player player, Position passOrigin) {
        boolean home = "HOME".equals(player.getTeam());

        // Must be a forward pass moment (attacking toward opponent goal)
        boolean forward = home
                ? player.getPosition().getRow() > passOrigin.getRow()
                : player.getPosition().getRow() < passOrigin.getRow();
        if (!forward) return false;

        // Must be in the opponent half
        boolean inOpponentHalf = home
                ? player.getPosition().getRow() >= 4
                : player.getPosition().getRow() <= 4;
        if (!inOpponentHalf) return false;

        // FIFA Rule 11: offside if fewer than 2 opponents (incl. GK) goal-side
        String defendingTeam = home ? "AWAY" : "HOME";
        int opponentsGoalSide = 0;
        for (Player opp : state.getPlayers()) {
            if (!defendingTeam.equals(opp.getTeam())) continue;
            if (opp.isLocked() || opp.isSentOff() || opp.isInjured()) continue;
            boolean goalSide = home
                    ? opp.getPosition().getRow() > player.getPosition().getRow()
                    : opp.getPosition().getRow() < player.getPosition().getRow();
            if (goalSide) opponentsGoalSide++;
        }
        return opponentsGoalSide < 2;
    }

    /**
     * Result of an offside check, telling the caller what to do.
     */
    public record OffsideResult(boolean confirmed, boolean wasChecked) {}

    /**
     * Calculate offside margin: how far past the second-to-last opponent the receiver is.
     * Positive = offside, negative = onside. Uses GK + all defenders for the line.
     */
    public double calculateOffsideMargin(Player receiver, String carrierTeam, MatchState state) {
        boolean home = "HOME".equals(receiver.getTeam());
        String defendingTeam = home ? "AWAY" : "HOME";

        List<Double> opponentRows = new ArrayList<>();
        for (Player p : state.getPlayers()) {
            if (!defendingTeam.equals(p.getTeam())) continue;
            if (p.isSentOff() || p.isInjured()) continue;
            opponentRows.add(p.getPosition().getRow());
        }
        if (opponentRows.size() < 2) return 10.0;
        opponentRows.sort(home ? Comparator.reverseOrder() : Comparator.naturalOrder());
        double lineRow = opponentRows.get(1);
        return home
                ? receiver.getPosition().getRow() - lineRow
                : lineRow - receiver.getPosition().getRow();
    }

    /**
     * Check if a receiver is offside on a forward pass.
     *
     * Per corePrinciples §47.8: the pass CONTINUES even if receiver is offside.
     * Positions are captured at pass start; receiver touches ball; then offside event fires.
     * For small margins, VAR reviews after the action.
     *
     * Margin-based rules:
     * - margin > 0.5 cells: clear offside — immediate referee call
     * - 0 < margin <= 0.5: close call — play continues, VAR reviews after next action
     * - -0.7 < margin <= 0: tight onside — VAR confirms onside after delay
     * - margin <= -0.7: clear onside, no VAR
     */
    public OffsideResult checkOffside(Player receiver, Position passOrigin, Position ballPos,
                                       String carrierTeam, MatchState state, ActionEngine actionEngine) {
        if (state.isKickoffActionPending() || state.isSetPiecePending()) {
            return new OffsideResult(false, false);
        }

        double margin = calculateOffsideMargin(receiver, carrierTeam, state);

        // Onside receiver (no offside). If they are hugging the line (within
        // 0.8 cells of the second-to-last defender), hold a pending ONSIDE_CHECK:
        // per user rule a shot/goal that follows is reviewed and confirmed. If the
        // receiver is clearly onside (> 0.8 clear) there is nothing to do.
        if (!rulesService.isOffside(receiver, passOrigin, ballPos)) {
            if (margin > -0.8) {
                state.setPendingVARReview("ONSIDE_CHECK", receiver, carrierTeam);
                state.setOffsideDeferred(true);
                state.setOffsideDeferredMargin(margin);
                state.setOffsideLedToGoal(false);
                state.setOffsideDeferredActionCount(state.getActionCount() + 1);
                return new OffsideResult(false, true); // pass continues; VAR may confirm a goal
            }
            return new OffsideResult(false, false);
        }

        // Clear offside (> 0.5 cells past the line) — an obvious call, the
        // referee whistles immediately. No VAR, no deferral.
        if (margin > 0.5) {
            return confirmOffside(receiver, carrierTeam, state, actionEngine, "clear offside (margin=" + String.format("%.2f", margin) + ")");
        }

        // Marginal offside (0 < margin <= 0.5) — HOLD the flag. Play continues
        // only to observe the NEXT action: if that action ends in a GOAL we run a
        // full VAR review (was the shooter offside?); otherwise, per the user rule,
        // we whistle a PLAIN offside only when that next action ATTACKED (a shot
        // or meaningful forward pass). Harmless sideways/backward continuations are
        // let go. VAR is NEVER invoked for a bare offside.
        if (margin > 0) {
            state.setPendingVARReview("OFFSIDE", receiver, carrierTeam);
            state.setOffsideDeferred(true);
            state.setOffsideDeferredMargin(margin);
            state.setOffsideLedToGoal(false);
            state.setOffsideDeferredActionCount(state.getActionCount() + 1);
            return new OffsideResult(false, true); // pass continues, receiver touches ball
        }

        // Clear onside (margin <= -0.8) — no call, no deferral, no VAR.
        return new OffsideResult(false, false);
    }

    /**
     * Resolve a held (deferred) offside. Called at the NEXT action boundary.
     *
     * User rule:
     * - If the move's next action ended in a GOAL, that goal path performs the
     *   VAR itself (via {@link #resolveOffsideVAROnGoal}). By the time we get
     *   here with a pending review still held, the next action did NOT produce a
     *   goal, so we whistle a PLAIN offside immediately — never a VAR for a bare
     *   offside.
     */
    public void resolvePendingVAROffside(MatchState state, ActionEngine actionEngine) {
        if (!state.hasPendingVARReview()) return;
        String reviewType = state.getPendingVARReviewType();
        Player receiver = state.getPendingVARReviewPlayer();
        String carrierTeam = state.getPendingVARReviewTeam();
        if (receiver == null) { state.clearPendingVARReview(); return; }

        if (!state.isOffsideDeferred()) {
            state.clearPendingVARReview();
            return;
        }

        // Don't resolve until at least the NEXT action has actually begun
        // (the offside-prone pass itself is one action; we wait one more).
        if (state.getActionCount() <= state.getOffsideDeferredActionCount()) {
            return;
        }

        state.setOffsideDeferred(false);

        // A pending ONSIDE_CHECK is a tight-onside marker, not an offside: if the
        // move's next action was not a goal (goal path consumes it), there is
        // nothing to whistle — clear it.
        if ("ONSIDE_CHECK".equals(state.getPendingVARReviewType())) {
            state.clearPendingVARReview();
            return;
        }

        // If a goal had been scored in the move, the goal path already consumed
        // this pending review synchronously — nothing left to whistle.
        if (state.isOffsideLedToGoal() || !state.hasPendingVARReview()) {
            state.clearPendingVARReview();
            return;
        }

        // User rule: marginal offside is whistled ONLY when the deferred (next)
        // action actually ATTACKED — a shot or a forward pass. If the receiver
        // merely continued sideways/backward/short, no offside offence follows
        // the flag; let play run on and drop the pending call (no whistle, no
        // stat, no free kick).
        if (!state.isOffsideDeferredDecisionForward()) {
            state.clearPendingVARReview();
            return;
        }

        // No goal followed the flagged pass → plain offside (no VAR).
        confirmOffside(receiver, carrierTeam, state, actionEngine,
                "offside (no goal — plain call)");
        state.clearPendingVARReview();
    }

    /**
     * Called from MatchSimulator when a goal is scored while a marginal offside
     * flag is still being held. This is the ONLY situation in which VAR reviews
     * an offside: we determine whether the SHOOTER was offside, then the goal is
     * confirmed (return true, count it) or disallowed (return false).
     */
    public boolean resolveOffsideVAROnGoal(MatchState state, Player shooter, String shootingTeam, ActionEngine actionEngine) {
        if (!state.hasPendingVARReview()) {
            return true; // no held offside — goal stands
        }
        double margin = state.getOffsideDeferredMargin();
        Player receiver = state.getPendingVARReviewPlayer();
        String defendingTeam = "HOME".equals(shootingTeam) ? "AWAY" : "HOME";

        // TIGHT ONSIDE CHECK (user rule): the scorer was onside but within 0.8
        // cells of the offside line when the pass was played — VAR reviews and
        // CONFIRMS the goal stands.
        if ("ONSIDE_CHECK".equals(state.getPendingVARReviewType())) {
            state.setOffsideDeferred(false);
            varService.logVARReviewStarted(shootingTeam,
                    "ONSIDE — " + receiver.getLabel()
                            + " was ONSIDE (defending: " + defendingTeam
                            + ") — reviewing imminent goal");
            varService.recordVARDecisionAtTick("VAR_GOAL_CONFIRMED",
                    (receiver.getLabel()) + " onside margin=" + String.format("%.2f", margin),
                    state.getSimulationTick() + Math.max(1, VAR_OFFSIDE_TICKS / 2));
            logger.logInfo(state, "VAR CONFIRMED GOAL — " + receiver.getLabel()
                    + " was ONSIDE (tight, margin=" + String.format("%.2f", margin) + ")",
                    "VAR", receiver);
            state.clearPendingVARReview();
            return true; // goal stands
        }

        // VAR IN PROGRESS — non-blocking overlay, play flows during review.
        // Include BOTH teams so the viewer knows which side is attacking
        // and which is defending during the review.
        String reviewDetail = "OFFSIDE — " + shooter.getLabel()
                + " (goal review) — attacking: " + shootingTeam
                + " defending: " + defendingTeam;
        varService.logVARReviewStarted(shootingTeam, reviewDetail);

        // Marginal offside (0 < margin <= 0.5) held to a goal: per user rule the
        // move WAS offside, so the goal is ALWAYS disallowed — a hard rule, not a
        // probability. (On the compressed pitch the held receiver was the shooter;
        // margin > 0 means the scorer was offside when the pass was played.)
        boolean overturned = false;

        // Record the decision ~VAR_OFFSIDE_TICKS later so the viewer shows the
        // full review duration before the CONFIRMED / OVERTURNED verdict.
        long decisionTick = state.getSimulationTick() + Math.max(1, VAR_OFFSIDE_TICKS / 2);
        varService.recordVARDecisionAtTick("VAR_OFFSIDE_CONFIRMED",
                shooter.getLabel() + " margin=" + String.format("%.2f", margin), decisionTick);
        logger.logInfo(state, "VAR CONFIRMED offside: " + shooter.getLabel()
                + " — GOAL DISALLOWED (margin=" + String.format("%.2f", margin) + ")",
                "VAR", shooter);
        // Offside confirmed → indirect free kick for the defending team.
        confirmOffside(receiver, shootingTeam, state, actionEngine,
                "VAR confirmed offside (margin=" + String.format("%.2f", margin) + ")");

        state.clearPendingVARReview();
        return overturned;
    }

    private OffsideResult confirmOffside(Player receiver, String carrierTeam,
                                          MatchState state, ActionEngine actionEngine, String reason) {
        stats.onOffside(carrierTeam);
        receiver.incrementConsecutiveOffside();

        String defendingTeam = "HOME".equals(carrierTeam) ? "AWAY" : "HOME";
        Position offsidePos = receiver.getPosition();

        // Stop any active action immediately — the pass/shot is void
        actionEngine.complete("OFFSIDE — action void");
        state.setAction(null);

        // Place ball at offside spot and clear carrier state
        state.getBall().setPosition(offsidePos);
        state.getBall().setTarget(null);
        state.getBall().setCarrier(null);
        state.setCarrier(null);
        state.clearActiveChasers();
        state.setLastTouchTeam(defendingTeam);

        // Unlock all outfield players so they can reposition
        for (Player p : state.getPlayers()) {
            if (p.isLocked() && !"GK".equals(p.getRole()) && !p.isSentOff() && !p.isInjured()) {
                p.setLocked(false);
            }
        }

        // PUSH-BACK: opponents of the defending team (i.e. the attacking team)
        // within 1 cell of the ball are pushed toward their OWN goal so the
        // free-kick taker has a clean path. teleport is NOT used here — players
        // are slid to the minimum restart distance. The set-piece walk block in
        // MatchSimulator then lets the nearest defending-team player WALK to the
        // ball. Only as a last resort (RESTART_WALK_MAX_TICKS timeout) is
        // teleport used.
        String attackingTeam = carrierTeam;
        double ownGoalRow = "HOME".equals(attackingTeam) ? 7.0 : 1.0;
        double dir = Math.signum(ownGoalRow - offsidePos.getRow());
        if (Math.abs(dir) < 1e-6) dir = 1.0; // fallback if ball already at goal line
        for (Player p : state.getPlayers()) {
            if (!attackingTeam.equals(p.getTeam())) continue;
            if ("GK".equals(p.getRole()) || p.isSentOff() || p.isInjured()) continue;
            if (p == receiver) continue; // offside player stays — they caused it
            double dist = SimUtils.distance(p.getPosition(), offsidePos);
            if (dist < MovementEngine.MIN_RESTART_DISTANCE) {
                double push = MovementEngine.MIN_RESTART_DISTANCE - dist + 0.1;
                double pushedRow = offsidePos.getRow() + push * dir;
                pushedRow = SimUtils.clamp(pushedRow, 1.0, 7.0);
                p.setPosition(new Position(pushedRow, p.getPosition().getColumn()));
                p.setTarget(null);
            }
        }

        // Find nearest non-GK player from defending team to take the indirect FK.
        // The taker WALKS to the ball (target set here; MatchSimulator set-piece
        // walk block moves them). Teleport only as fallback after timeout.
        Player freeKickTaker = selection.nearestNonGoalkeeperTo(offsidePos, defendingTeam);
        if (freeKickTaker != null) {
            state.setFreeKickTaker(freeKickTaker);
            freeKickTaker.setTarget(offsidePos);
        }

        // --- OFFSIDE OVERLAY EVENT ---
        // Record a dedicated OFFSIDE event carrying team name, offending player
        // name, and YELLOW_FLAG outcome so the viewer renders the offside
        // overlay (yellow flag 🚩 + player name + team + margin) for ~3.5s.
        // Description format: "offside <player> (margin=X.XX)" so the viewer's
        // regex (/^.*offside\s+/ then strip parens) extracts the player name.
        double marginForDisplay = calculateOffsideMargin(receiver, carrierTeam, state);
        String desc = "INDIRECT FREE KICK for " + defendingTeam
                + " — offside " + receiver.getLabel()
                + " (margin=" + String.format(java.util.Locale.US, "%.2f", marginForDisplay) + ")";
        recorder.appendEvent(new MatchEvent(
                state.getSimulationTick(), state.getRound(),
                (String) null, "OFFSIDE",
                desc,
                receiver.getTeam(),
                receiver.getId(), receiver.getLabel(),
                freeKickTaker != null ? freeKickTaker.getId() : (String) null,
                Double.valueOf(offsidePos.getRow()), Double.valueOf(offsidePos.getColumn()),
                (Integer) null, "YELLOW_FLAG"));

        logger.logInfo(state, "OFFSIDE " + receiver.getTeam() + " — " + receiver.getLabel()
                + " caught offside on pass from carrier (" + reason + ")"
                + " — indirect free kick for " + defendingTeam
                + " — " + (freeKickTaker != null ? freeKickTaker.getLabel() + " walking to ball" : "no taker"),
                "OFFSIDE", receiver);

        state.setSetPiecePending(true);
        state.setActionDelayTicks(5); // hold so the overlay is visible + taker walks in
        return new OffsideResult(true, true);
    }
}
