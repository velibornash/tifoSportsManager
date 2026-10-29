package org.tifo.footballmanager.newLogic.sim.engine;

import org.tifo.footballmanager.newLogic.sim.model.MatchState;
import org.tifo.footballmanager.newLogic.sim.model.Player;
import org.tifo.footballmanager.newLogic.sim.model.Position;
import org.tifo.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.tifo.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.tifo.footballmanager.newLogic.sim.restarts.RestartManager;
import org.tifo.footballmanager.newLogic.sim.rules.DisciplineService;
import org.tifo.footballmanager.newLogic.sim.rules.OffsideService;
import org.tifo.footballmanager.newLogic.sim.rules.VARService;
import org.tifo.footballmanager.newLogic.sim.util.SimulationRandom;

/**
 * Detects and resolves a deterministic single-opponent duel for the current
 * ball carrier. Every non-carrier opponent of the opposite team is checked;
 * if a duel fires ({@link DuelEngine#checkDuel}), it is resolved and its
 * result applied. The duels layer of the orchestrator is just this delegate.
 *
 * When the defender wins a contest cleanly the duel outcome applies; when the
 * contest is a FOUL the referee play is redirected: the duel result is skipped
 * and the game restarts from the foul spot (free kick / penalty) for the fouled
 * team.
 */
public class DuelService {

    private final MatchState state;
    private final MatchRecorder recorder;
    private final ProposalStatsCollector stats;
    private final DuelEngine duelEngine = new DuelEngine();
    private final DisciplineService discipline;
    private final RestartManager restartManager;

    public DuelService(MatchState state, MatchRecorder recorder,
                       ProposalStatsCollector stats,
                       RestartManager restartManager,
                       VARService varService) {
        this.state = state;
        this.recorder = recorder;
        this.stats = stats;
        this.restartManager = restartManager;
        this.discipline = new DisciplineService(state, varService);
    }

    public void detectAndResolveDuels() {
        if (state.getCarrier() == null) return;
        Player carrier = state.getCarrier();

        // Pick the most dangerous qualifying opponent (F-DU-3, owner decision
        // 2026-10-22): among all opponents that can contest the ball this tick,
        // the one nearest the carrier's own goal line is the threat — the ball is
        // behind him and our net is in front of him. List order used to decide
        // this by accident (HOME-first, formation order), so a full-back could be
        // preferred to a striker.
        boolean carrierHome = "HOME".equals(carrier.getTeam());
        Player chosenOpponent = null;
        DuelEngine.DuelType chosenType = null;
        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(carrier.getTeam())) continue;
            if (opponent.isUnavailable() || opponent.isLocked()) continue;

            DuelEngine.DuelType duelType = duelEngine.checkDuel(carrier, opponent, state);
            if (duelType == null) continue;

            if (chosenOpponent == null) {
                chosenOpponent = opponent;
                chosenType = duelType;
                continue;
            }
            double chosenRow = chosenOpponent.getPosition().getRow();
            double oppRow = opponent.getPosition().getRow();
            // nearest to our own goal line: min row for HOME (goal at row 1),
            // max row for AWAY (goal at row 8).
            boolean oppMoreDangerous = carrierHome ? (oppRow < chosenRow) : (oppRow > chosenRow);
            if (oppMoreDangerous) {
                chosenOpponent = opponent;
                chosenType = duelType;
            }
        }

        if (chosenOpponent != null) {
            Player opponent = chosenOpponent;
            DuelEngine.DuelType duelType = chosenType;
            Player winner = duelEngine.resolveDuel(carrier, opponent, duelType, state);

            // Emit DUEL_START when a duel is first triggered, so the viewer can render
            // the converging circles and the yellow ring that appears when opponents touch.
            recorder.appendEvent(state.getMatchTicks(), "DUEL_START",
                    "DUEL START " + carrier.getLabel() + " vs " + opponent.getLabel(),
                    carrier, opponent);

            // Discipline — only on a genuine defensive contest where the DEFENDER
            // won (tackle/dribble/receive). Evaluated BEFORE applyDuelResult so
            // state.getCarrier() still points at the fouled attacker for the foul
            // position/penalty-box checks. A foul replaces the duel's ball outcome:
            // the game restarts from the foul spot for the fouled team.
            boolean foulAwarded = false;
            if (winner == opponent && (duelType == DuelEngine.DuelType.DRIBBLE
                    || duelType == DuelEngine.DuelType.TACKLE
                    || duelType == DuelEngine.DuelType.RECEIVE_PASS)) {
                foulAwarded = evaluateDiscipline(opponent, carrier);
            }
            if (foulAwarded) {
                // the restart already replaced the duel outcome — stop contesting
            } else {
                if (winner != carrier) {
                    state.clearPassContext();
                }
                duelEngine.applyDuelResult(state, winner, winner == carrier ? opponent : carrier);
                String duelMsg = "DUEL " + duelType + " won by " + winner.getLabel()
                        + " (" + carrier.getLabel() + p(carrier.getPosition())
                        + " v " + opponent.getLabel() + p(opponent.getPosition()) + ")"
                        + " ball" + p(state.getBall().getPosition());
                log("DUL", duelMsg);
                recorder.appendEvent(state.getMatchTicks(), "DUEL", duelMsg, state);
                // Emit DUEL_RESOLVED so the viewer can render the yellow→orange ring,
                // the winner's movement onto the ball, and the loser's cooldown.
                recorder.appendEvent(state.getMatchTicks(), "DUEL_RESOLVED",
                        "DUEL RESOLVED " + winner.getLabel() + " won, " + opponent.getLabel() + " locked for "
                                + DuelEngine.LOSER_LOCK_TICKS + " ticks",
                        winner, opponent);
                stats.onDuelWon(winner.getId(), (winner == carrier ? opponent : carrier).getId());
            }
        }
    }

    /** @return true if a foul was awarded — the restart replaced the duel outcome. */
    private boolean evaluateDiscipline(Player offender, Player fouled) {
        state.setLastTouchPlayer(offender);
        DisciplineService.DisciplineResult res = discipline.evaluateFoul(state);
        if (!res.foul()) return false;

        stats.onFoul(offender.getTeam(), offender.getId());
        state.clearPassContext();

        if (res.penalty()) {
            // OFFSIDE HAS PRIORITY OVER A PENALTY (user rule 2026-09-26).
            //
            // If the ball was played to this attacker while he was in an offside position, the
            // whole phase is a prohibited action: the defender cannot concede a penalty for a foul
            // on a player who had no right to be contesting the ball. The offside stands and the
            // penalty is never created.
            //
            // This is the cause of the seed-123 defect: a pass was played to a flagged receiver,
            // a foul was called in the box on the very next tick, and the offside indirect free kick
            // then landed on top of the penalty one tick later and silently erased it. Suppressing
            // the penalty here fixes the root rather than papering over the collision.
            //
            // NOTE: this is a deliberate divergence from Law 11, which penalises "whichever offence
            // occurs first" - under the Laws, an attacker fouled BEFORE playing the ball still gets
            // a penalty. Flagged here because it is a game-design choice, not a reading of the Law.
            Player flagged = state.getOffsideFlaggedReceiver();
            if (flagged != null && flagged == fouled) {
                state.setOffsideFlaggedReceiver(null);
                state.setCarrier(null);
                state.getBall().stop();
                String defending = "HOME".equals(fouled.getTeam()) ? "AWAY" : "HOME";
                state.setRestartTeam(defending);
                double offMargin = OffsideService.offsideMargin(fouled, state);
                String offMarginText = String.format(java.util.Locale.US, "%.2f", offMargin)
                        + " cell, " + Math.round(offMargin * 14) + " m";
                String offMsg = "*** OFFSIDE by " + fouled.getLabel()
                        + " - no penalty, indirect free kick " + defending
                        + " (offside is a prohibited action, so it takes precedence)"
                        + " (margin=" + offMarginText + ")";
                log("ORC", offMsg);
                appendDisciplineEvent("OFFSIDE", offMsg, fouled.getTeam(), fouled, offender,
                        null, null, null, null, null, null);
                stats.onOffside(fouled.getTeam());
                restartManager.handleOffsideFreeKick(state, fouled.getPosition());
                return true;
            }

            restartManager.handlePenalty(state, fouled.getTeam());
            Player taker = state.getRestartTaker();
            String msg = "Penalty awarded to " + fouled.getTeam()
                    + " — foul by " + offender.getLabel();
            log("FOU", msg);
            appendDisciplineEvent("PENALTY_AWARDED", msg, fouled.getTeam(),
                    taker != null ? taker : fouled, offender, null, true,
                    taker != null ? taker.getId() : null,
                    taker != null ? taker.getLabel() : null, null, null);
            stats.onPenalty(fouled.getTeam());
        } else {
            Position spot = fouled.getPosition() != null
                    ? fouled.getPosition() : state.getBall().getPosition();
            restartManager.handleFreeKick(state, spot, fouled.getTeam());
            String msg = "Free kick: foul by " + offender.getLabel()
                    + " on " + fouled.getLabel();
            log("FOU", msg);
            appendDisciplineEvent("FOUL", msg, offender.getTeam(), offender, fouled,
                    null, null, null, null, null, null);
        }

        if (res.yellowCard()) {
            stats.onYellowCard(offender.getTeam(), offender.getId());
            String msg = "Yellow card: " + offender.getLabel();
            log("CARD", msg);
            appendDisciplineEvent("YELLOW_CARD", msg, offender.getTeam(), offender, fouled,
                    "YELLOW", null, null, null, null, null);
        }
        if (res.redCard()) {
            stats.onRedCard(offender.getTeam(), offender.getId());
            String msg = "Red card: " + offender.getLabel();
            log("CARD", msg);
            appendDisciplineEvent("RED_CARD", msg, offender.getTeam(), offender, fouled,
                    "RED", null, null, null, null, null);
        }

        if (!"NONE".equals(res.varDecision()) && !"NO_REVIEW".equals(res.varDecision())) {
            String varType = varType(res.varDecision());
            String msg = "VAR " + res.varDecision() + " — " + res.description();
            log("VAR", msg);
            // The replay viewer is told the review started (VAR_IN_PROGRESS ->
            // blocking review overlay) and then how it ended (typed
            // VAR_<TYPE>_CONFIRMED/_OVERTURNED). VAR_IN_PROGRESS is emitted by
            // VARService.logVARReviewStarted, which every VAR review routes
            // through, so it is not duplicated here.
            appendDisciplineEvent("VAR_" + varType + "_" + decisionSuffix(res.varDecision()),
                    msg, offender.getTeam(), offender, fouled,
                    null, null, null, null, varType, res.varDecision());
        }
        return true;
    }

    private void appendDisciplineEvent(String type, String description, String team,
                                       Player acting, Player target,
                                       String cardType, Boolean penaltyFoul,
                                       String takerId, String takerName,
                                       String varType, String varDecision) {
        recorder.appendEvent(state.getMatchTicks(), type, description, team, acting, target,
                null, null, null, null, cardType, penaltyFoul, takerId, takerName,
                varType, varDecision);
    }

    private String varType(String decision) {
        if (decision == null) return "VAR";
        int separator = decision.indexOf('_');
        return separator > 0 ? decision.substring(0, separator) : decision;
    }

    /** CONFIRMED / OVERTURNED suffix for the typed VAR event name. */
    private String decisionSuffix(String decision) {
        String upper = decision == null ? "" : decision.toUpperCase();
        if (upper.contains("OVERTURN")) return "OVERTURNED";
        return "CONFIRMED";
    }

    private void log(String tag, String msg) {
        // Route through the shared action logger so the compact-console filter
        // and the full-log file (target/proposal-app.log) apply uniformly.
        state.getActionLogger().log(tag, msg);
    }

    private String p(Position pos) {
        return pos == null ? "?" : "(%.1f,%.1f)".formatted(pos.getRow(), pos.getColumn());
    }
}
