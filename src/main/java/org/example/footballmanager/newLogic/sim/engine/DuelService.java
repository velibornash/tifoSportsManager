package org.example.footballmanager.newLogic.sim.engine;

import org.example.footballmanager.newLogic.sim.model.MatchState;
import org.example.footballmanager.newLogic.sim.model.Player;
import org.example.footballmanager.newLogic.sim.model.Position;
import org.example.footballmanager.newLogic.sim.recording.MatchRecorder;
import org.example.footballmanager.newLogic.sim.result.ProposalStatsCollector;
import org.example.footballmanager.newLogic.sim.restarts.RestartManager;
import org.example.footballmanager.newLogic.sim.rules.DisciplineService;
import org.example.footballmanager.newLogic.sim.rules.VARService;
import org.example.footballmanager.newLogic.sim.util.SimulationRandom;

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

        for (Player opponent : state.getPlayers()) {
            if (opponent.getTeam().equals(carrier.getTeam())) continue;
            if (opponent.isUnavailable() || opponent.isLocked()) continue;

            DuelEngine.DuelType duelType = duelEngine.checkDuel(carrier, opponent, state);
            if (duelType != null) {
                Player winner = duelEngine.resolveDuel(carrier, opponent, duelType, state);

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
                    break; // the restart already replaced the duel outcome — stop contesting
                }

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
                stats.onDuelWon(winner.getId(), (winner == carrier ? opponent : carrier).getId());

                // ONE contest per tick. The loop used to keep going after a duel
                // resolved, so a carrier surrounded by opponents could win five
                // duels in the same tick — the ball never moved and the match
                // stalled. One contest, then normal play resumes.
                break;
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
                String offMsg = "*** OFFSIDE by " + fouled.getLabel()
                        + " - no penalty, indirect free kick " + defending
                        + " (offside is a prohibited action, so it takes precedence)";
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
            // A discipline VAR review is a real stoppage, so the replay viewer is
            // told the review started (VAR_IN_PROGRESS -> blocking review overlay)
            // and then how it ended (typed VAR_<TYPE>_CONFIRMED/_OVERTURNED).
            // The held-live offside check deliberately does NOT emit
            // VAR_IN_PROGRESS — play continues during it by design.
            appendDisciplineEvent("VAR_IN_PROGRESS",
                    "VAR review: " + varType + " (" + res.description() + ")",
                    offender.getTeam(), offender, fouled,
                    null, null, null, null, varType, null);
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
